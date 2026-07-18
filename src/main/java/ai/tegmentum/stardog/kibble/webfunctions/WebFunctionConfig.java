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
        final ai.tegmentum.webassembly4j.api.config.WebAssemblyConfigBuilder builder =
                WebAssemblyConfig.builder()
                        .resourceLimits(resourceLimitsFromSystemProperties())
                        .fuelLimit(getLong(PROP_FUEL_LIMIT).orElse(0L))
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
        getLong(PROP_FUEL_LIMIT).ifPresent(builder::fuelLimit);
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
