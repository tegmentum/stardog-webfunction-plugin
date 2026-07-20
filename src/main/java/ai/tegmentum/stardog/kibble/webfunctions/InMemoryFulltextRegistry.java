package ai.tegmentum.stardog.kibble.webfunctions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wave C — in-memory registry backing the Stardog plugin's
 * implementation of {@code fulltext-callbacks}. Mirrors the shape of
 * Oxigraph's {@code InMemoryFulltextImpl} (see
 * {@code ~/git/oxigraph-webfunction-plugin/crates/host-callbacks-impl/src/lib.rs})
 * scaled down to the three MVP callbacks the substrate memo commits to:
 * {@code insert-documents}, {@code delete-documents},
 * {@code search-index}.
 *
 * <p><b>Singleton, config-driven.</b> Indexes are registered at plugin
 * startup by {@link WebFunctionServiceModule} from the comma-separated
 * {@code webfunctions.fulltext.indexes} system property and are
 * immutable thereafter — there is deliberately no runtime
 * {@code register-index} WIT method. A guest that references an
 * unregistered index name gets the interface's {@code no-such-index}
 * error arm. This mirrors the Wave A {@link SinkRegistry} exactly.
 *
 * <p><b>Thread-safety.</b> Outer map is a {@link ConcurrentHashMap}.
 * Each {@link FulltextIndex}'s document map is likewise a
 * {@link ConcurrentHashMap}. Registration is idempotent per-name; a
 * duplicate {@link #register} call for the same name throws
 * {@link IllegalStateException} to catch config typos eagerly rather
 * than silently drop accumulated documents.
 *
 * <p><b>Test discipline.</b> {@link #reset()} clears the singleton
 * between tests. Production paths never call it — the registry's
 * lifetime is the plugin's, mirroring how the Oxigraph reference impl
 * treats its {@code InMemoryFulltextImpl}.
 *
 * <p><b>Not BITES.</b> Wave C explicitly does NOT bridge to Stardog's
 * BITES full-text search: the BITES admin surface is not accessible
 * from a web-function-plugin thread today, and the substrate contract
 * is engine-neutral — the reference in-memory backing is enough to
 * prove the WIT boundary round-trips and unblock guest migration. A
 * follow-on wave lands the BITES bridge behind the same interface with
 * no guest-side change.
 */
public final class InMemoryFulltextRegistry {

    public static final InMemoryFulltextRegistry INSTANCE = new InMemoryFulltextRegistry();

    private final ConcurrentHashMap<String, FulltextIndex> indexes = new ConcurrentHashMap<>();

    private InMemoryFulltextRegistry() {}

    /** Ambient singleton accessor. Prefer {@link #INSTANCE} at call
     *  sites; this getter exists so tests targeting a specific injection
     *  shape can obtain the instance uniformly. */
    public static InMemoryFulltextRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Register an index under the given name. Called from startup after
     * reading the operator-provided list from
     * {@code webfunctions.fulltext.indexes}.
     *
     * @throws IllegalArgumentException when {@code name} is null / blank.
     * @throws IllegalStateException    when an index with the given name
     *                                  is already registered. Duplicates
     *                                  are a config-time error (typos)
     *                                  and surface loudly rather than
     *                                  silently reset accumulated docs.
     */
    public FulltextIndex register(final String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "fulltext index name must be a non-blank string (was: '" + name + "')");
        }
        final FulltextIndex created = new FulltextIndex(name);
        final FulltextIndex prev = indexes.putIfAbsent(name, created);
        if (prev != null) {
            throw new IllegalStateException(
                    "fulltext index '" + name + "' is already registered — "
                    + "duplicate entry in webfunctions.fulltext.indexes?");
        }
        return created;
    }

    /** Lookup by name. Returns empty for unregistered names — callers
     *  translate that into the interface's {@code no-such-index} error
     *  arm. */
    public Optional<FulltextIndex> index(final String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(indexes.get(name));
    }

    /** Enumerate registered index names, sorted for deterministic
     *  ordering. Not part of the WIT surface today (no
     *  {@code list-indexes} method) — exposed for operator-side
     *  introspection and test assertions. */
    public List<String> indexNames() {
        final List<String> names = new ArrayList<>(indexes.keySet());
        Collections.sort(names);
        return names;
    }

    /** Whether an index with this name is registered. */
    public boolean contains(final String name) {
        return name != null && indexes.containsKey(name);
    }

    /** Clear every registration and its accumulated state. Test-only —
     *  production wiring never invokes this. */
    public void reset() {
        indexes.clear();
    }
}
