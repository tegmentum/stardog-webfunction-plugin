package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.webassembly4j.api.config.ComponentConfig;
import ai.tegmentum.webassembly4j.api.config.ResourceLimits;
import ai.tegmentum.webassembly4j.api.config.WebAssemblyConfig;
import ai.tegmentum.webassembly4j.provider.wasmtime.config.WasmtimeConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;

public final class WebFunctionConfig {

    public static final String PROP_FUEL_LIMIT       = "webfunctions.fuel.limit";
    public static final String PROP_MAX_MEMORY_BYTES = "webfunctions.memory.max.bytes";
    public static final String PROP_TIMEOUT_MILLIS   = "webfunctions.timeout.millis";
    public static final String PROP_MAX_EXEC_MILLIS  = "webfunctions.exec.max.millis";
    public static final String PROP_MAX_INSTANCES    = "webfunctions.max.instances";
    public static final String PROP_MAX_TABLE_ELEMS  = "webfunctions.table.max.elements";
    public static final String PROP_ENGINE_PROVIDER  = "webfunctions.engine.provider";
    public static final String PROP_ENGINE_ID        = "webfunctions.engine.id";

    // v0.3.0 host-callback config.
    public static final String PROP_CALLBACK_MAX_DEPTH = "webfunctions.callback.max.depth";
    public static final String PROP_CALLBACK_MAX_ROWS  = "webfunctions.callback.max.rows";
    public static final String PROP_CALLBACK_ENABLED   = "webfunctions.callback.enabled";

    public static final int DEFAULT_CALLBACK_MAX_DEPTH = 100;
    public static final int DEFAULT_CALLBACK_MAX_ROWS  = 100_000;

    // wasm-callbacks multi-level nesting cap. Task-279 MVP landed
    // single-level nesting (root → callee, no deeper); this key extends
    // the cap to a configurable depth. Default 8 — deep enough for
    // realistic composition chains (e.g. router-extension → auth-check
    // → tenant-config → data-fetch → post-process) without permitting
    // pathological runaway recursion. Enforced at
    // {@link CallbackContext#enterWasmCall(String)} (depth cap) and
    // (cycle detection) — both surface {@link CallbackContext.WasmNestingException}
    // which the WIT boundary maps to the {@code nesting-not-permitted}
    // arm the F4 tightening added.
    public static final String PROP_WASM_CALLBACKS_MAX_NESTING_DEPTH =
            "webfunctions.wasm-callbacks.max-nesting-depth";

    public static final int DEFAULT_WASM_CALLBACKS_MAX_NESTING_DEPTH = 8;

    public static final String DEFAULT_ENGINE_PROVIDER = "wasmtime";

    // Sink registry — Wave A (in-memory only). Comma-separated list of
    // sink names registered at plugin startup by
    // {@link WebFunctionServiceModule.SinkRegistryStarter} and made
    // available to the sink-family host callbacks
    // (sink-callbacks / sink-query-callbacks / document-sink-callbacks).
    //
    // Empty (unset) => no sinks registered => every sink-family callback
    // returns the interface's `no-such-sink` arm. The set of sinks is
    // immutable at runtime — there is no runtime `register-sink` WIT
    // method by design; sinks are a substrate concept the operator
    // declares once at boot.
    public static final String PROP_SINK_NAMES = "webfunctions.sink.names";

    // Fulltext-index registry — Wave C (in-memory only). Comma-separated
    // list of index names registered at plugin startup by
    // {@link WebFunctionServiceModule.FulltextRegistryStarter} and made
    // available to fulltext-callbacks (insert-documents /
    // delete-documents / search-index).
    //
    // Empty (unset) => no indexes registered => every fulltext callback
    // returns the interface's `no-such-index` arm. Same immutable-at-
    // runtime discipline as PROP_SINK_NAMES — indexes are declared at
    // boot; there is no WIT-side registration method by design.
    public static final String PROP_FULLTEXT_INDEXES = "webfunctions.fulltext.indexes";

    // Tracker-sink registry — Wave B (SQLite JDBC-backed). Path to the
    // on-disk SQLite database that {@link SqliteTrackerBackend} opens at
    // plugin startup and comma-separated list of sink names permitted
    // to `register-tracker-tables` against it. Both keys optional; when
    // {@link #PROP_TRACKER_SQLITE_PATH} is unset the starter no-ops
    // (backend stays closed; every tracker-sink-callbacks dispatch
    // returns `no-such-sink` — same mirror-of-config discipline the
    // sink / fulltext registries use).
    //
    // The path may be `:memory:` for ephemeral tests. Production
    // wiring resolves against ${stardog.home}/webfunctions/tracker.sqlite
    // (the caller sets this via -D at plugin install time).
    public static final String PROP_TRACKER_SQLITE_PATH  = "webfunctions.tracker.sqlite.path";
    public static final String PROP_TRACKER_SQLITE_SINKS = "webfunctions.tracker.sqlite.sinks";

    // Compose Wave B — optional URL prefix operators can set to override
    // the default {@code sha256://<hex>} scheme emitted in composition
    // RDF. When set, {@link ai.tegmentum.stardog.kibble.webfunctions.compose.ComposeAdmin}
    // emits {@code <prefix><digest-hex>} as the artifact URL in the
    // {@code comp:hasArtifact} triple instead of {@code sha256://<hex>}.
    //
    // The plugin still persists composed bytes to the local blob store
    // (${stardog.home}/webfunctions-compose/artifacts/<hex>.wasm) — this
    // key only rewrites the RDF-facing URL. The operator is responsible
    // for making the resulting URL fetchable (upload out-of-band or via
    // a separate pipeline). MVP is emit-only, not PUT.
    //
    // Example: setting the prefix to
    // {@code https://cdn.example.com/artifacts/} yields
    // {@code https://cdn.example.com/artifacts/<hex>} in RDF. Empty or
    // unset preserves the default {@code sha256://<hex>} emission.
    public static final String PROP_COMPOSE_ARTIFACT_URL_PREFIX = "webfunctions.compose.artifact-url-prefix";

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

    // Phase 6 — durable disk backing for both fuel and capability audit
    // rings. Shared infrastructure: both rings pipe rows through the same
    // NdjsonRotatingFileAuditSink shape driven by these keys. Every key
    // is optional; default state (enabled=false) preserves in-memory-only
    // ring behavior exactly. See:
    //   ~/git/stardog-webfunction-wit/docs/design/capability-implementation.md
    //     §14 Phase 6 (audit disk backing, shared with fuel).
    //   ~/git/stardog-webfunction-wit/docs/design/fuel-implementation.md
    //     — attribution-log Phase 6 backing.
    public static final String PROP_AUDIT_DISK_ENABLED         = "webfunctions.audit.disk.enabled";
    public static final String PROP_AUDIT_DISK_DIRECTORY       = "webfunctions.audit.disk.directory";
    public static final String PROP_AUDIT_DISK_ROTATE_BYTES    = "webfunctions.audit.disk.rotate-bytes";
    public static final String PROP_AUDIT_DISK_MAX_FILES       = "webfunctions.audit.disk.max-files";
    public static final String PROP_AUDIT_DISK_QUEUE_CAPACITY  = "webfunctions.audit.disk.queue-capacity";
    public static final String PROP_AUDIT_DISK_FSYNC_POLICY    = "webfunctions.audit.disk.fsync-policy";
    public static final String PROP_AUDIT_DISK_GZIP_ROTATED    = "webfunctions.audit.disk.gzip-rotated";

    public static final long    DEFAULT_AUDIT_DISK_ROTATE_BYTES   = 100_000_000L; // 100 MB
    public static final int     DEFAULT_AUDIT_DISK_MAX_FILES      = 10;
    public static final int     DEFAULT_AUDIT_DISK_QUEUE_CAPACITY = 100_000;
    public static final String  DEFAULT_AUDIT_DISK_FSYNC_POLICY   = "per-second";
    public static final boolean DEFAULT_AUDIT_DISK_GZIP_ROTATED   = true;

    private WebFunctionConfig() {}

    public static String engineProvider() {
        final String raw = System.getProperty(PROP_ENGINE_PROVIDER);
        return (raw == null || raw.isEmpty()) ? DEFAULT_ENGINE_PROVIDER : raw.trim();
    }

    public static java.util.Optional<String> engineId() {
        final String raw = System.getProperty(PROP_ENGINE_ID);
        return (raw == null || raw.isEmpty()) ? java.util.Optional.empty() : java.util.Optional.of(raw.trim());
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
        // at all — we discover at build time via engine.capabilities()). The
        // plugin is component-only, so the opt-in is always on.
        if ("wasmtime".equalsIgnoreCase(engineProvider())) {
            builder.engineConfig(WasmtimeConfig.builder()
                    .wasmComponentModel(true)
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

    /**
     * Plugin-side execution deadline in milliseconds, sourced from
     * {@link #PROP_MAX_EXEC_MILLIS}. Consumed by {@link CallbackContext}
     * at bind time to stamp an effective deadline on the invocation; every
     * host-callback dispatch then compares {@link System#nanoTime()} against
     * the stamp and throws {@link WfBudgetError.DeadlineExceeded} on trip.
     *
     * <p>Empty {@link OptionalLong} when the property is unset — no
     * plugin-side deadline; the invocation still honors the outer query's
     * Stardog {@code ExecutionMonitor.isCancelled()}, checked at the same
     * dispatch chokepoint.
     *
     * <p>Reuses {@link #PROP_MAX_EXEC_MILLIS} intentionally — the same
     * property already feeds {@link #resourceLimitsFromSystemProperties()}
     * as the substrate-level engine ceiling, so operators express a single
     * "maximum execution time" value and the plugin applies it at both the
     * substrate and the host-callback dispatch layers.
     */
    public static OptionalLong execMaxMillis() {
        return getLong(PROP_MAX_EXEC_MILLIS);
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
     * Maximum wasm-callbacks nesting depth. Root extension counts as
     * depth 0; each nested {@code invoke-wasm} / {@code invoke-wasm-service}
     * dispatch bumps the depth by 1. Reaching depth {@code max + 1}
     * throws {@link CallbackContext.WasmNestingException} which the
     * WIT boundary maps to the {@code nesting-not-permitted} arm.
     *
     * <p>Default {@link #DEFAULT_WASM_CALLBACKS_MAX_NESTING_DEPTH}
     * ({@code 8}). Values ≤ 0 fall back to the default (rather than
     * silently disabling nesting altogether — an operator that wants
     * single-level nesting configures {@code 1}, not {@code 0}).
     */
    public static int wasmCallbacksMaxNestingDepth() {
        final long raw = getLong(PROP_WASM_CALLBACKS_MAX_NESTING_DEPTH)
                .orElse((long) DEFAULT_WASM_CALLBACKS_MAX_NESTING_DEPTH);
        if (raw <= 0L) return DEFAULT_WASM_CALLBACKS_MAX_NESTING_DEPTH;
        if (raw > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) raw;
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

    /**
     * Master gate for the Phase 6 audit disk backing. Off by default —
     * when {@code false}, both fuel and capability rings pipe rows
     * through a {@link NoopAuditSink} and behavior matches pre-Phase-6
     * exactly (in-memory ring only, no disk I/O, no writer thread).
     * Opt in by setting to {@code true} to spin up the two rotating-file
     * sinks at plugin install time.
     */
    public static boolean auditDiskEnabled() {
        final String raw = System.getProperty(PROP_AUDIT_DISK_ENABLED);
        return raw != null && !raw.isEmpty() && Boolean.parseBoolean(raw.trim());
    }

    /**
     * Directory under which the two rotating log files live (one per
     * ring type: {@code audit-fuel.log*} and {@code audit-capability.log*}).
     * Default: {@code ${stardog.home}/logs/webfunctions-audit} — leverages
     * the existing Stardog logging directory convention. Falls back to
     * {@code ${java.io.tmpdir}/webfunctions-audit} when {@code stardog.home}
     * is unset (unit tests, embedded direct-instantiation).
     */
    public static String auditDiskDirectory() {
        final String raw = System.getProperty(PROP_AUDIT_DISK_DIRECTORY);
        if (raw != null && !raw.isEmpty()) return raw.trim();
        final String home = System.getProperty("stardog.home");
        if (home != null && !home.isEmpty()) {
            return home + "/logs/webfunctions-audit";
        }
        final String tmp = System.getProperty("java.io.tmpdir", "/tmp");
        return tmp + "/webfunctions-audit";
    }

    /**
     * Byte threshold at which the active file rotates. Default 100 MB —
     * balances index-line cardinality against compressed-per-file size
     * for downstream ELK ingestion. Values ≤ 0 fall back to the default.
     */
    public static long auditDiskRotateBytes() {
        final long raw = getLong(PROP_AUDIT_DISK_ROTATE_BYTES).orElse(DEFAULT_AUDIT_DISK_ROTATE_BYTES);
        return raw <= 0 ? DEFAULT_AUDIT_DISK_ROTATE_BYTES : raw;
    }

    /**
     * Maximum number of log files retained per ring — the active file
     * plus up to {@code maxFiles - 1} rotated (possibly gzipped) files.
     * Default 10. Retention beyond rotation is an operator concern —
     * rm the oldest rotated files as needed.
     */
    public static int auditDiskMaxFiles() {
        final long raw = getLong(PROP_AUDIT_DISK_MAX_FILES).orElse((long) DEFAULT_AUDIT_DISK_MAX_FILES);
        if (raw < 1L) return DEFAULT_AUDIT_DISK_MAX_FILES;
        if (raw > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) raw;
    }

    /**
     * Bounded queue capacity between the ring's append hot path and the
     * sink's writer thread. Default 100_000 rows — sized so a 10-second
     * burst at 10k rows/second lands entirely in the queue without the
     * drop-oldest path activating. Overflow drops the oldest queued row
     * (not the request-path row about to be enqueued) so recent rows
     * are preserved.
     */
    public static int auditDiskQueueCapacity() {
        final long raw = getLong(PROP_AUDIT_DISK_QUEUE_CAPACITY).orElse((long) DEFAULT_AUDIT_DISK_QUEUE_CAPACITY);
        if (raw < 1L) return DEFAULT_AUDIT_DISK_QUEUE_CAPACITY;
        if (raw > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) raw;
    }

    /**
     * Durability policy for the active file. Values (case-insensitive):
     * {@code per-row} (fsync after every row — strongest, slow),
     * {@code per-second} (default — scheduled 1s fsync, matches other
     * observability tools' cadence), {@code never} (OS page cache only —
     * fastest, lossy on crash).
     */
    public static NdjsonRotatingFileAuditSink.FsyncPolicy auditDiskFsyncPolicy() {
        final String raw = System.getProperty(PROP_AUDIT_DISK_FSYNC_POLICY);
        final String value = raw == null || raw.isEmpty()
                ? DEFAULT_AUDIT_DISK_FSYNC_POLICY : raw.trim().toLowerCase();
        switch (value) {
            case "per-row":    return NdjsonRotatingFileAuditSink.FsyncPolicy.PER_ROW;
            case "per-second": return NdjsonRotatingFileAuditSink.FsyncPolicy.PER_SECOND;
            case "never":      return NdjsonRotatingFileAuditSink.FsyncPolicy.NEVER;
            default:
                throw new IllegalArgumentException(
                        PROP_AUDIT_DISK_FSYNC_POLICY
                                + " must be 'per-row', 'per-second', or 'never' (was: '"
                                + raw + "')");
        }
    }

    /**
     * Whether rotated files (only the {@code .N} slots, never the active
     * file) get compressed via gzip. Default {@code true} — cuts disk
     * footprint by ~10x on NDJSON. Set {@code false} for filesystems
     * where operators want to grep rotated files directly.
     */
    public static boolean auditDiskGzipRotated() {
        final String raw = System.getProperty(PROP_AUDIT_DISK_GZIP_ROTATED);
        if (raw == null || raw.isEmpty()) return DEFAULT_AUDIT_DISK_GZIP_ROTATED;
        return Boolean.parseBoolean(raw.trim());
    }

    /**
     * Names of sinks registered at plugin startup by
     * {@link WebFunctionServiceModule.SinkRegistryStarter}. Parsed
     * from the comma-separated {@link #PROP_SINK_NAMES} system property;
     * blank / whitespace-only entries are dropped and every remaining
     * entry is trimmed. Duplicate names surface as an
     * {@link IllegalStateException} at
     * {@link SinkRegistry#register(String)} time — caught eagerly at
     * install rather than silently swallowing later state resets.
     *
     * <p>Empty list when the property is unset or contains only blanks
     * — Wave A default: no sinks, every sink callback returns
     * {@code no-such-sink}.
     */
    public static List<String> getSinkNames() {
        return parseCommaList(PROP_SINK_NAMES);
    }

    /**
     * Names of fulltext indexes registered at plugin startup by
     * {@link WebFunctionServiceModule.FulltextRegistryStarter}. Parsed
     * from the comma-separated {@link #PROP_FULLTEXT_INDEXES} system
     * property with the same trim / drop-blanks discipline as
     * {@link #getSinkNames()}. Duplicate names surface as an
     * {@link IllegalStateException} at
     * {@link InMemoryFulltextRegistry#register(String)} time.
     *
     * <p>Empty list when the property is unset or contains only blanks
     * — Wave C default: no indexes, every fulltext callback returns
     * {@code no-such-index}.
     */
    public static List<String> getFulltextIndexNames() {
        return parseCommaList(PROP_FULLTEXT_INDEXES);
    }

    /**
     * Path to the SQLite database backing the tracker-sink registry
     * (Wave B). Empty {@link java.util.Optional} when the property is
     * unset — {@code SqliteTrackerBackendStarter} treats that as "no
     * tracker-sink wiring; leave the backend closed" and every
     * tracker-sink-callbacks dispatch surfaces {@code no-such-sink}.
     *
     * <p>{@code :memory:} yields a per-JVM ephemeral database — useful
     * for tests and unit-scale demos. Production wiring resolves to
     * {@code ${stardog.home}/webfunctions/tracker.sqlite} but the
     * plugin does NOT assume {@code stardog.home}; the caller sets the
     * path explicitly at install time.
     */
    public static java.util.Optional<String> getTrackerSqlitePath() {
        final String raw = System.getProperty(PROP_TRACKER_SQLITE_PATH);
        if (raw == null || raw.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(raw.trim());
    }

    /**
     * Names of sinks permitted to register tracker tables against the
     * Wave B SQLite backend. Parsed with the same comma-list discipline
     * as {@link #getSinkNames()} / {@link #getFulltextIndexNames()}. A
     * guest that references a name not in this list surfaces
     * {@code no-such-sink} even when {@link #getTrackerSqlitePath()} is
     * present — the allowlist is the operator's fence against schema
     * declarations by rogue extensions.
     */
    public static List<String> getTrackerSqliteSinks() {
        return parseCommaList(PROP_TRACKER_SQLITE_SINKS);
    }

    /**
     * Optional URL prefix that overrides the default {@code sha256://}
     * scheme emitted in composition RDF's {@code comp:hasArtifact}
     * triple. When present, {@link ai.tegmentum.stardog.kibble.webfunctions.compose.ComposeAdmin}
     * concatenates {@code prefix + digest-hex} to build the RDF-facing
     * artifact URL; otherwise the canonical {@code sha256://<hex>} form
     * is emitted.
     *
     * <p>Local persistence is unaffected — bytes still land at
     * {@code ${stardog.home}/webfunctions-compose/artifacts/<hex>.wasm}
     * regardless of the prefix. Operator is responsible for making the
     * emitted URL fetchable (out-of-band upload); no PUT path is wired
     * in MVP. URL scheme is not validated — any string the operator
     * supplies is accepted verbatim.
     *
     * <p>{@link java.util.Optional#empty()} when the property is unset,
     * empty, or whitespace-only; same trim discipline as
     * {@link #getTrackerSqlitePath()}.
     */
    public static java.util.Optional<String> getArtifactUrlPrefix() {
        final String raw = System.getProperty(PROP_COMPOSE_ARTIFACT_URL_PREFIX);
        if (raw == null) return java.util.Optional.empty();
        final String trimmed = raw.trim();
        if (trimmed.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(trimmed);
    }

    /** Shared comma-list parser for the two config-driven registry
     *  keys. Blank / whitespace-only pieces are dropped and every
     *  remaining piece is trimmed. Returns an empty list when the
     *  property is unset. */
    private static List<String> parseCommaList(final String propertyKey) {
        final String raw = System.getProperty(propertyKey);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> out = new ArrayList<>();
        for (final String piece : raw.split(",")) {
            final String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
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
