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

    // Fuel-metering Phase 1 — in-memory attribution ring. Opt-in even when
    // fuel is enabled: a Phase 1 deployment that only wants typed error
    // mapping (no diagnostic buffer) leaves this off. Disk-backed audit
    // trail (Phase 6) will land as a separate `attribution-log.path` key.
    public static final String PROP_ATTRIBUTION_LOG_ENABLED   = "webfunctions.fuel.attribution-log.enabled";
    public static final String PROP_ATTRIBUTION_LOG_CAPACITY  = "webfunctions.fuel.attribution-log.capacity";

    public static final int DEFAULT_ATTRIBUTION_LOG_CAPACITY  = 10_000;

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

    // Capability-policy Phase 1 keys. Every key is optional and defaults
    // keep the plugin behaviorally identical to pre-capability deployments
    // (webfunctions.capability.enabled=false disables all Phase 1 work).
    // See:
    //   ~/git/stardog-webfunction-wit/docs/design/capability-policy.md
    //   ~/git/stardog-webfunction-wit/docs/design/capability-implementation.md
    //     §5 (resolver flow), §7 (enforcer flow), §8 (permission strings),
    //     §12 (default policy — DENY anonymous, audit enabled).
    public static final String PROP_CAPABILITY_ENABLED                   = "webfunctions.capability.enabled";
    public static final String PROP_CAPABILITY_ANONYMOUS_POLICY          = "webfunctions.capability.anonymous-policy";
    public static final String PROP_CAPABILITY_UNKNOWN_EXTENSION_POLICY  = "webfunctions.capability.unknown-extension-policy";
    public static final String PROP_CAPABILITY_POLICY_STORE_DATABASE     = "webfunctions.capability.policy-store.database-name";
    public static final String PROP_CAPABILITY_AUDIT_ENABLED             = "webfunctions.capability.audit.enabled";
    public static final String PROP_CAPABILITY_AUDIT_CAPACITY            = "webfunctions.capability.audit.capacity";

    public static final int    DEFAULT_CAPABILITY_AUDIT_CAPACITY         = 100_000;
    public static final String DEFAULT_CAPABILITY_POLICY_STORE_DATABASE  = "system-webfunctions-capability";

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
     * Master flag for the in-memory attribution ring (Phase 1 diagnostic).
     * Off by default so a Phase 1 deployment that only wants typed error
     * mapping can opt out of the ring's cost — the append is a no-op when
     * this returns {@code false}. Disk-backed audit trail lands under a
     * separate {@code webfunctions.fuel.attribution-log.path} key in Phase 6.
     */
    public static boolean attributionLogEnabled() {
        final String raw = System.getProperty(PROP_ATTRIBUTION_LOG_ENABLED);
        return raw != null && !raw.isEmpty() && Boolean.parseBoolean(raw.trim());
    }

    /**
     * Bounded ring capacity — most recent N invocations retained; older rows
     * evicted FIFO. Default 10 000 rows. Read on each append so live-tuning
     * via {@link System#setProperty} takes effect without JVM restart.
     */
    public static int attributionLogCapacity() {
        final long raw = getLong(PROP_ATTRIBUTION_LOG_CAPACITY).orElse(DEFAULT_ATTRIBUTION_LOG_CAPACITY);
        if (raw <= 0L) return DEFAULT_ATTRIBUTION_LOG_CAPACITY;
        if (raw > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) raw;
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

    /**
     * Master gate for capability-policy Phase 1. Off by default so existing
     * deployments continue unchanged. When enabled,
     * {@link CapabilityEnforcer#preInvocation} resolves the effective grant at
     * component instantiation and {@link CapabilityEnforcer#perCallback}
     * checks every host-callback dispatch. See capability-implementation.md §12.
     */
    public static boolean isCapabilityEnabled() {
        final String raw = System.getProperty(PROP_CAPABILITY_ENABLED);
        return raw != null && !raw.isEmpty() && Boolean.parseBoolean(raw.trim());
    }

    /**
     * Name of the Stardog-managed database that persists capability
     * policy triples. Default {@code system-webfunctions-capability}.
     * Mirrors the {@code webfunctions.fuel.state-store.database-name}
     * pattern from Phase 2 fuel metering.
     */
    public static String capabilityPolicyDatabaseName() {
        final String raw = System.getProperty(PROP_CAPABILITY_POLICY_STORE_DATABASE);
        return raw == null || raw.isEmpty()
                ? DEFAULT_CAPABILITY_POLICY_STORE_DATABASE
                : raw.trim();
    }

    /**
     * Behavior when the policy store has no policy triples for the
     * extension URL. {@code deny} is the prod default per the refactor
     * brief; {@code permit} treats the extension as pre-capability
     * (grants everything the component declares); {@code inherit} same
     * as {@code permit} in Phase 1 (kept as a distinct value so a future
     * phase can distinguish inherited-from-parent from open-permit).
     */
    public static CapabilityPolicyResolver.UnknownExtensionPolicy unknownExtensionPolicy() {
        final String raw = System.getProperty(PROP_CAPABILITY_UNKNOWN_EXTENSION_POLICY);
        if (raw == null || raw.isEmpty()) {
            return CapabilityPolicyResolver.DEFAULT_UNKNOWN_EXTENSION_POLICY;
        }
        switch (raw.trim().toLowerCase()) {
            case "deny":    return CapabilityPolicyResolver.UnknownExtensionPolicy.DENY;
            case "permit":  return CapabilityPolicyResolver.UnknownExtensionPolicy.PERMIT;
            case "inherit": return CapabilityPolicyResolver.UnknownExtensionPolicy.INHERIT;
            default:
                throw new IllegalArgumentException(
                        PROP_CAPABILITY_UNKNOWN_EXTENSION_POLICY
                                + " must be 'deny', 'permit', or 'inherit' (was: '"
                                + raw + "')");
        }
    }

    /**
     * Behavior when the invocation happens without an authenticated Shiro
     * subject (unit tests, embedded direct-instantiation, misconfigured
     * server). {@code deny} is the prod default per implementation memo §12.
     * Values: {@code deny}, {@code permit}, {@code inherit}.
     */
    public static CapabilityPolicyResolver.AnonymousPolicy getAnonymousPolicy() {
        final String raw = System.getProperty(PROP_CAPABILITY_ANONYMOUS_POLICY);
        if (raw == null || raw.isEmpty()) {
            return CapabilityPolicyResolver.DEFAULT_ANONYMOUS_POLICY;
        }
        switch (raw.trim().toLowerCase()) {
            case "deny":    return CapabilityPolicyResolver.AnonymousPolicy.DENY;
            case "permit":  return CapabilityPolicyResolver.AnonymousPolicy.PERMIT;
            case "inherit": return CapabilityPolicyResolver.AnonymousPolicy.INHERIT;
            default:
                throw new IllegalArgumentException(
                        PROP_CAPABILITY_ANONYMOUS_POLICY
                                + " must be 'deny', 'permit', or 'inherit' (was: '"
                                + raw + "')");
        }
    }

    /**
     * Master gate for the capability audit ring. Default on per
     * implementation memo §12 — capability audit is load-bearing for the
     * security story, opposite of the fuel-attribution ring default.
     */
    public static boolean isAuditEnabled() {
        final String raw = System.getProperty(PROP_CAPABILITY_AUDIT_ENABLED);
        if (raw == null || raw.isEmpty()) return true;
        return Boolean.parseBoolean(raw.trim());
    }

    /**
     * Bounded capacity of the capability audit ring. Default 100_000 rows
     * per implementation memo §12 — capability rows land per host-callback
     * dispatch, one order of magnitude denser than fuel-attribution rows
     * which land per invocation.
     */
    public static int getAuditCapacity() {
        final long raw = getLong(PROP_CAPABILITY_AUDIT_CAPACITY).orElse(DEFAULT_CAPABILITY_AUDIT_CAPACITY);
        if (raw <= 0L) return DEFAULT_CAPABILITY_AUDIT_CAPACITY;
        if (raw > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) raw;
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
