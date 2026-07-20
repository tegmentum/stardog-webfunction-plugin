package ai.tegmentum.stardog.kibble.webfunctions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wave A — in-memory registry backing the Stardog plugin's implementation
 * of {@code sink-callbacks}, {@code sink-query-callbacks}, and
 * {@code document-sink-callbacks}. Mirrors the shape of Oxigraph's
 * {@code InMemorySinkImpl} (see
 * {@code ~/git/oxigraph-webfunction-plugin/crates/host-callbacks-impl/src/lib.rs})
 * scaled down to the four MVP callbacks the substrate memo commits to.
 *
 * <p><b>Singleton, config-driven.</b> Sinks are registered at plugin
 * startup by {@link WebFunctionServiceModule} from the comma-separated
 * {@link WebFunctionConfig#PROP_SINK_NAMES} system property and are
 * immutable thereafter — there is deliberately no runtime
 * {@code register-sink} WIT method. A guest that references an
 * unregistered sink name gets the interface's {@code no-such-sink}
 * error arm.
 *
 * <p><b>Thread-safety.</b> Outer map is a {@link ConcurrentHashMap}.
 * Each {@link SinkEntry}'s internals are lock-free
 * ({@link java.util.concurrent.ConcurrentLinkedDeque} for the quad
 * accumulator, {@link ConcurrentHashMap} for documents). Registration
 * is idempotent per-name; a duplicate {@link #register} call for the
 * same name throws {@link IllegalStateException} to catch config typos
 * eagerly rather than silently drop accumulated state.
 *
 * <p><b>Test discipline.</b> {@link #reset()} clears the singleton
 * between tests. Production paths never call it — the registry's
 * lifetime is the plugin's, mirroring how the Oxigraph reference impl
 * treats its {@code InMemorySinkImpl}.
 */
public final class SinkRegistry {

    public static final SinkRegistry INSTANCE = new SinkRegistry();

    private final ConcurrentHashMap<String, SinkEntry> sinks = new ConcurrentHashMap<>();

    private SinkRegistry() {}

    /** Ambient singleton accessor. Prefer {@link #INSTANCE} at call
     *  sites; this getter exists so tests targeting a specific injection
     *  shape can obtain the instance uniformly. */
    public static SinkRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Register a sink under the given name. Called from startup after
     * reading {@link WebFunctionConfig#getSinkNames()}.
     *
     * @throws IllegalArgumentException when {@code name} is null / blank.
     * @throws IllegalStateException    when a sink with the given name
     *                                  is already registered. Duplicates
     *                                  are a config-time error (typos)
     *                                  and surface loudly rather than
     *                                  silently reset accumulated state.
     */
    public SinkEntry register(final String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "sink name must be a non-blank string (was: '" + name + "')");
        }
        final SinkEntry created = new SinkEntry(name);
        final SinkEntry prev = sinks.putIfAbsent(name, created);
        if (prev != null) {
            throw new IllegalStateException(
                    "sink '" + name + "' is already registered — duplicate entry in "
                    + WebFunctionConfig.PROP_SINK_NAMES + "?");
        }
        return created;
    }

    /** Lookup by name. Returns empty for unregistered names — callers
     *  translate that into the interface's {@code no-such-sink} error
     *  arm. */
    public Optional<SinkEntry> sink(final String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(sinks.get(name));
    }

    /** Enumerate registered sink names, sorted for deterministic
     *  ordering. Backs {@code sink-callbacks::list-sinks}. */
    public List<String> sinkNames() {
        final List<String> names = new ArrayList<>(sinks.keySet());
        Collections.sort(names);
        return names;
    }

    /** Whether a sink with this name is registered. */
    public boolean contains(final String name) {
        return name != null && sinks.containsKey(name);
    }

    /** Clear every registration and its accumulated state. Test-only —
     *  production wiring never invokes this. */
    public void reset() {
        sinks.clear();
    }
}
