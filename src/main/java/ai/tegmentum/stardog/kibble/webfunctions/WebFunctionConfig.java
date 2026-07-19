package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.webassembly4j.api.config.ComponentConfig;
import ai.tegmentum.webassembly4j.api.config.ResourceLimits;
import ai.tegmentum.webassembly4j.api.config.WebAssemblyConfig;
import ai.tegmentum.webassembly4j.provider.wasmtime.config.WasmtimeConfig;

import java.util.OptionalLong;

public final class WebFunctionConfig {

    public static final String PROP_FUEL_LIMIT       = "webfunctions.fuel.limit";
    public static final String PROP_MAX_MEMORY_BYTES = "webfunctions.memory.max.bytes";
    public static final String PROP_TIMEOUT_MILLIS   = "webfunctions.timeout.millis";
    public static final String PROP_MAX_EXEC_MILLIS  = "webfunctions.exec.max.millis";
    public static final String PROP_MAX_INSTANCES    = "webfunctions.max.instances";
    public static final String PROP_MAX_TABLE_ELEMS  = "webfunctions.table.max.elements";
    public static final String PROP_ENGINE_MODE      = "webfunctions.engine.mode";
    public static final String PROP_ENGINE_PROVIDER  = "webfunctions.engine.provider";
    public static final String PROP_ENGINE_ID        = "webfunctions.engine.id";

    // v0.3.0 host-callback config.
    public static final String PROP_CALLBACK_MAX_DEPTH = "webfunctions.callback.max.depth";
    public static final String PROP_CALLBACK_MAX_ROWS  = "webfunctions.callback.max.rows";
    public static final String PROP_CALLBACK_ENABLED   = "webfunctions.callback.enabled";

    public static final int DEFAULT_CALLBACK_MAX_DEPTH = 100;
    public static final int DEFAULT_CALLBACK_MAX_ROWS  = 100_000;

    public static final String DEFAULT_ENGINE_PROVIDER = "wasmtime";

    // Fuel metering Phase 1 — defensive-only layer.
    //
    // Off by default so existing deployments continue unchanged
    // (fuel-implementation.md §8 Phase 1). Turn on to opt into typed
    // WF_PER_INVOCATION_TRAP / WF_HOST_CALLBACK_TOLL_EXHAUSTED SPARQL
    // error mapping backed by a per-invocation fuel cap and a per-
    // host-callback toll. See:
    //   ~/git/stardog-webfunction-wit/docs/design/fuel-metering.md
    //   ~/git/stardog-webfunction-wit/docs/design/fuel-implementation.md
    //     §4 (per-invocation flow), §5 (error surface), §6 (config surface),
    //     §8 Phase 1 (scope of this landing).
    //
    // Phase 2+ keys — per-user/org quota, rate limiting, attribution log
    // to disk, KernelBackedFuelStateStore — are NOT landed here.
    public static final String PROP_FUEL_ENABLED              = "webfunctions.fuel.enabled";
    public static final String PROP_FUEL_PER_INVOCATION_MAX   = "webfunctions.fuel.per-invocation.max";
    public static final String PROP_FUEL_HOST_CALLBACK_TOLL   = "webfunctions.fuel.host-callback-toll";

    public static final long DEFAULT_FUEL_PER_INVOCATION_MAX  = 100_000L;
    public static final long DEFAULT_FUEL_HOST_CALLBACK_TOLL  = 1_000L;

    // Fuel metering Phase 2 — per-user commercial quota + state store.
    // All keys optional; defaults keep Phase 1 behavior when Phase 2 opt-in
    // config isn't set (per-user.monthly=0 short-circuits the quota check
    // entirely).
    public static final String PROP_FUEL_PER_USER_MONTHLY       = "webfunctions.fuel.per-user.monthly";
    public static final String PROP_FUEL_STATE_FLUSH_INTERVAL   = "webfunctions.fuel.state-store.flush-interval";
    public static final String PROP_FUEL_STATE_DATABASE_NAME    = "webfunctions.fuel.state-store.database-name";

    public static final long   DEFAULT_FUEL_PER_USER_MONTHLY     = 0L;        // unlimited
    public static final long   DEFAULT_FUEL_STATE_FLUSH_INTERVAL = 60_000L;   // 60s — mirrors QueryLog UPDATE_INTERVAL
    public static final String DEFAULT_FUEL_STATE_DATABASE_NAME  = "system-webfunctions-fuel";

    public enum EngineMode { MODULE, COMPONENT }

    private WebFunctionConfig() {}

    public static String engineProvider() {
        final String raw = System.getProperty(PROP_ENGINE_PROVIDER);
        return (raw == null || raw.isEmpty()) ? DEFAULT_ENGINE_PROVIDER : raw.trim();
    }

    public static java.util.Optional<String> engineId() {
        final String raw = System.getProperty(PROP_ENGINE_ID);
        return (raw == null || raw.isEmpty()) ? java.util.Optional.empty() : java.util.Optional.of(raw.trim());
    }

    public static EngineMode engineMode() {
        final String raw = System.getProperty(PROP_ENGINE_MODE);
        if (raw == null || raw.isEmpty()) {
            return EngineMode.MODULE;
        }
        switch (raw.trim().toLowerCase()) {
            case "component": return EngineMode.COMPONENT;
            case "module":    return EngineMode.MODULE;
            default:
                throw new IllegalArgumentException(
                        PROP_ENGINE_MODE + " must be 'module' or 'component' (was: '" + raw + "')");
        }
    }

    public static WebAssemblyConfig fromSystemProperties() {
        // When fuel metering (Phase 1) is enabled, force engine-level fuel
        // consumption on so the store can enforce the per-invocation cap set
        // in componentConfigFromSystemProperties(). The legacy
        // webfunctions.fuel.limit is still honored as an engine-level default
        // when fuel.enabled=false, per the back-compat guarantee in the memo.
        final long engineFuelLimit;
        if (fuelEnabled()) {
            engineFuelLimit = fuelPerInvocationMax();
        } else {
            engineFuelLimit = getLong(PROP_FUEL_LIMIT).orElse(0L);
        }
        final ai.tegmentum.webassembly4j.api.config.WebAssemblyConfigBuilder builder =
                WebAssemblyConfig.builder()
                        .resourceLimits(resourceLimitsFromSystemProperties())
                        .fuelLimit(engineFuelLimit)
                        .timeoutMillis(getLong(PROP_TIMEOUT_MILLIS).orElse(0L));

        engineId().ifPresent(builder::engine);

        // Provider-specific config. Only wasmtime needs a component-model opt-in;
        // other providers ship component-model support enabled by default (or not
        // at all — we discover at build time via engine.capabilities()).
        if ("wasmtime".equalsIgnoreCase(engineProvider())) {
            builder.engineConfig(WasmtimeConfig.builder()
                    .wasmComponentModel(engineMode() == EngineMode.COMPONENT)
                    .build());
        }

        return builder.build();
    }

    /**
     * Build a {@link ComponentConfig} from the same
     * {@code webfunctions.*} system properties {@link
     * #resourceLimitsFromSystemProperties()} reads, so the per-component
     * ceiling (memory / fuel / table-elements) applied at instantiation
     * time matches the engine-level limits.
     *
     * <p>Wasmtime enforces the memory ceiling at
     * component-instantiation time rather than at engine level: the
     * host must pass a {@code ComponentConfig} with
     * {@code maxMemoryBytes(...)} for the guest's linear-memory grow
     * to trap at the configured page count. Component tests that
     * exercise memory-hungry paths depend on this ceiling being set
     * per instance.
     */
    public static ComponentConfig componentConfigFromSystemProperties() {
        final ComponentConfig.Builder builder = ComponentConfig.builder();
        getLong(PROP_MAX_MEMORY_BYTES).ifPresent(builder::maxMemoryBytes);
        // Phase 1: when fuel.enabled=true, apply the per-invocation cap at
        // component instantiation. Otherwise, honor the legacy fuel.limit as
        // it did pre-Phase-1 so opt-out deployments behave identically.
        if (fuelEnabled()) {
            builder.fuelLimit(fuelPerInvocationMax());
        } else {
            getLong(PROP_FUEL_LIMIT).ifPresent(builder::fuelLimit);
        }
        getLong(PROP_MAX_TABLE_ELEMS).ifPresent(builder::maxTableElements);
        getLong(PROP_MAX_INSTANCES).ifPresent(builder::maxInstances);
        return builder.build();
    }

    static ResourceLimits resourceLimitsFromSystemProperties() {
        final OptionalLong maxMemory   = getLong(PROP_MAX_MEMORY_BYTES);
        final OptionalLong maxExecMs   = getLong(PROP_MAX_EXEC_MILLIS);
        final OptionalLong maxInst     = getLong(PROP_MAX_INSTANCES);
        final OptionalLong maxTableEls = getLong(PROP_MAX_TABLE_ELEMS);

        return new ResourceLimits() {
            @Override public OptionalLong maxMemoryBytes()         { return maxMemory; }
            @Override public OptionalLong maxTableElements()       { return maxTableEls; }
            @Override public OptionalLong maxInstances()           { return maxInst; }
            @Override public OptionalLong maxExecutionTimeMillis() { return maxExecMs; }
        };
    }

    public static int callbackMaxDepth() {
        return (int) getLong(PROP_CALLBACK_MAX_DEPTH).orElse(DEFAULT_CALLBACK_MAX_DEPTH);
    }

    public static int callbackMaxRows() {
        return (int) getLong(PROP_CALLBACK_MAX_ROWS).orElse(DEFAULT_CALLBACK_MAX_ROWS);
    }

    public static boolean callbackEnabled() {
        final String raw = System.getProperty(PROP_CALLBACK_ENABLED);
        return raw == null || raw.isEmpty() || Boolean.parseBoolean(raw.trim());
    }

    /**
     * Master flag for fuel metering Phase 1 (defensive-only layer).
     *
     * <p>Off by default so existing deployments continue unchanged. When
     * enabled, per-invocation fuel cap ({@link #fuelPerInvocationMax}) is
     * applied at component instantiation, and each host-callback dispatch
     * charges a fixed {@link #fuelHostCallbackToll} toll deducted from the
     * per-invocation budget. Traps are surfaced as typed
     * {@link WfBudgetError} SPARQL errors.
     *
     * @see #fuelPerInvocationMax()
     * @see #fuelHostCallbackToll()
     */
    public static boolean fuelEnabled() {
        final String raw = System.getProperty(PROP_FUEL_ENABLED);
        return raw != null && !raw.isEmpty() && Boolean.parseBoolean(raw.trim());
    }

    /**
     * Per-invocation fuel cap when {@link #fuelEnabled()} is true.
     * Default 100_000 units — the same order of magnitude as the wasmtime
     * examples per fuel-implementation.md §6. Deployments recalibrate once
     * customer workloads are observed.
     */
    public static long fuelPerInvocationMax() {
        return getLong(PROP_FUEL_PER_INVOCATION_MAX).orElse(DEFAULT_FUEL_PER_INVOCATION_MAX);
    }

    /**
     * Fixed fuel toll charged before each host-callback dispatches, when
     * {@link #fuelEnabled()} is true. Default 1_000 units per
     * fuel-metering.md §7. Uniform across callback types at MVP;
     * per-callback tolls are Phase-2 refinement.
     */
    public static long fuelHostCallbackToll() {
        return getLong(PROP_FUEL_HOST_CALLBACK_TOLL).orElse(DEFAULT_FUEL_HOST_CALLBACK_TOLL);
    }

    /**
     * Per-user monthly commercial quota in fuel units.
     *
     * <p>Default 0 (unlimited) — Phase-2 opt-in. When {@link #fuelEnabled()}
     * is true AND this value is {@code > 0}, the invocation entry point
     * loads the caller's {@link UserFuelState}, checks the monthly cap,
     * and post-invocation increments the running counter through the
     * configured {@link FuelStateStore}. See {@code fuel-implementation.md}
     * §4 steps 3-6 and §8 Phase 2.
     */
    public static long fuelPerUserMonthly() {
        return getLong(PROP_FUEL_PER_USER_MONTHLY).orElse(DEFAULT_FUEL_PER_USER_MONTHLY);
    }

    /**
     * Flush interval in milliseconds for the write-behind cache in
     * {@link KernelBackedFuelStateStore}. Default 60_000 ms — mirrors the
     * QueryLog {@code UPDATE_INTERVAL} shape (see §7a.2 ecosystem precedent).
     */
    public static long fuelStateFlushIntervalMillis() {
        return getLong(PROP_FUEL_STATE_FLUSH_INTERVAL).orElse(DEFAULT_FUEL_STATE_FLUSH_INTERVAL);
    }

    /**
     * Name of the Stardog-managed database that persists fuel state; see
     * §7a.4 recommendation. Default {@code system-webfunctions-fuel}.
     */
    public static String fuelStateDatabaseName() {
        final String raw = System.getProperty(PROP_FUEL_STATE_DATABASE_NAME);
        return raw == null || raw.isEmpty() ? DEFAULT_FUEL_STATE_DATABASE_NAME : raw.trim();
    }

    private static OptionalLong getLong(final String key) {
        final String raw = System.getProperty(key);
        if (raw == null || raw.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
