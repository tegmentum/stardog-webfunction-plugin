package ai.tegmentum.stardog.kibble.webfunctions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RDF vocabulary constants for the capability-policy store.
 *
 * <p>Fragment sub-vocabulary rooted at the stable {@code wf:} namespace
 * mandated by {@code CLAUDE.md}'s identifier-stability rule (the
 * {@code semantalytics.com/2021/03/ns/stardog/webfunction/} IRI predates
 * the semantalytics → tegmentum org rename and MUST NOT change).
 *
 * <p>Predicates ({@code cap:trusted}, {@code cap:allowInterface},
 * {@code cap:allowMethod}, {@code cap:allowHost}) key the SELECT the
 * {@link CapabilityPolicyStore} issues per instantiation to project a
 * {@link PolicyTriples} row.
 *
 * <p>Interface + method IRIs enumerate the known host-callback surfaces
 * so admin tooling can point-and-click a policy statement rather than
 * mint IRI strings by hand.
 *
 * <p>{@link #interfaceIriFor(String)} and {@link #wireNameFor(String)}
 * bridge wire-format interface names (kebab-case, e.g.
 * {@code "graph-callbacks"}) and the CamelCase IRI fragment identifiers
 * used in the RDF policy triples.
 */
public final class CapabilityVocabulary {

    /**
     * Fragment sub-namespace under the stable {@code wf:} root. Predicates
     * and enumerated IRIs live below the {@code #} separator so a single
     * {@code cap:} SPARQL prefix serves the whole vocabulary.
     */
    public static final String NAMESPACE =
            "http://semantalytics.com/2021/03/ns/stardog/webfunction/capability#";

    // Predicates.
    public static final String CAP_TRUSTED           = NAMESPACE + "trusted";
    public static final String CAP_ALLOW_INTERFACE   = NAMESPACE + "allowInterface";
    public static final String CAP_ALLOW_METHOD      = NAMESPACE + "allowMethod";
    public static final String CAP_ALLOW_HOST        = NAMESPACE + "allowHost";
    // Phase 5 — fine-grained per-argument allowlists on axes not covered
    // by interface/method/host. allowHttpPath is a string-prefix match on
    // "host+path" (e.g. "api.acme.com/public/"); allowWasmCallee is an
    // exact IRI match on the invoke-wasm callee load URL (any scheme —
    // ipfs://, https://, file://, ...).
    public static final String CAP_ALLOW_HTTP_PATH   = NAMESPACE + "allowHttpPath";
    public static final String CAP_ALLOW_WASM_CALLEE = NAMESPACE + "allowWasmCallee";

    // Interface IRIs — enumerate the 11 known host-callback surfaces from
    // Phase 1's callback wiring so admin tooling has a stable target set.
    public static final String IFACE_GRAPH_CALLBACKS          = NAMESPACE + "GraphCallbacks";
    public static final String IFACE_HTTP_CALLBACKS           = NAMESPACE + "HttpCallbacks";
    public static final String IFACE_WASM_CALLBACKS           = NAMESPACE + "WasmCallbacks";
    public static final String IFACE_FULLTEXT_CALLBACKS       = NAMESPACE + "FulltextCallbacks";
    public static final String IFACE_SINK_CALLBACKS           = NAMESPACE + "SinkCallbacks";
    public static final String IFACE_TRACKER_SINK_CALLBACKS   = NAMESPACE + "TrackerSinkCallbacks";
    public static final String IFACE_PREPARED_QUERY_CALLBACKS = NAMESPACE + "PreparedQueryCallbacks";
    public static final String IFACE_OBSERVABILITY_CALLBACKS  = NAMESPACE + "ObservabilityCallbacks";
    public static final String IFACE_DOCUMENT_SINK_CALLBACKS  = NAMESPACE + "DocumentSinkCallbacks";
    public static final String IFACE_SINK_QUERY_CALLBACKS     = NAMESPACE + "SinkQueryCallbacks";
    public static final String IFACE_FAST_PATH_CALLBACKS      = NAMESPACE + "FastPathCallbacks";

    // Method IRIs — <Interface>_<Method> convention so an admin can grep
    // the vocabulary for the interface prefix and see every method
    // constant that belongs to it.
    public static final String METHOD_GRAPH_CALLBACKS_EXECUTE_QUERY   = NAMESPACE + "GraphCallbacks_ExecuteQuery";
    public static final String METHOD_GRAPH_CALLBACKS_EXECUTE_UPDATE  = NAMESPACE + "GraphCallbacks_ExecuteUpdate";
    public static final String METHOD_HTTP_CALLBACKS_GET              = NAMESPACE + "HttpCallbacks_Get";
    public static final String METHOD_HTTP_CALLBACKS_POST             = NAMESPACE + "HttpCallbacks_Post";
    public static final String METHOD_WASM_CALLBACKS_INVOKE           = NAMESPACE + "WasmCallbacks_Invoke";
    public static final String METHOD_WASM_CALLBACKS_INVOKE_SERVICE   = NAMESPACE + "WasmCallbacks_InvokeService";

    /**
     * Bidirectional map — wire-format interface name → IRI. Deterministic
     * iteration for stable audit output.
     */
    private static final Map<String, String> WIRE_TO_IRI;
    private static final Map<String, String> IRI_TO_WIRE;

    static {
        final Map<String, String> w2i = new LinkedHashMap<>();
        w2i.put("graph-callbacks",          IFACE_GRAPH_CALLBACKS);
        w2i.put("http-callbacks",           IFACE_HTTP_CALLBACKS);
        w2i.put("wasm-callbacks",           IFACE_WASM_CALLBACKS);
        w2i.put("fulltext-callbacks",       IFACE_FULLTEXT_CALLBACKS);
        w2i.put("sink-callbacks",           IFACE_SINK_CALLBACKS);
        w2i.put("tracker-sink-callbacks",   IFACE_TRACKER_SINK_CALLBACKS);
        w2i.put("prepared-query-callbacks", IFACE_PREPARED_QUERY_CALLBACKS);
        w2i.put("observability-callbacks",  IFACE_OBSERVABILITY_CALLBACKS);
        w2i.put("document-sink-callbacks",  IFACE_DOCUMENT_SINK_CALLBACKS);
        w2i.put("sink-query-callbacks",     IFACE_SINK_QUERY_CALLBACKS);
        w2i.put("fast-path-callbacks",      IFACE_FAST_PATH_CALLBACKS);
        WIRE_TO_IRI = Collections.unmodifiableMap(w2i);

        final Map<String, String> i2w = new LinkedHashMap<>();
        for (final Map.Entry<String, String> e : w2i.entrySet()) {
            i2w.put(e.getValue(), e.getKey());
        }
        IRI_TO_WIRE = Collections.unmodifiableMap(i2w);
    }

    private CapabilityVocabulary() {}

    /**
     * Resolve a wire-format interface name (e.g. {@code "graph-callbacks"})
     * to its IRI, or {@code null} if the name is not in the enumerated
     * set. Callers that need to accept unknown-but-syntactically-valid
     * names can fall back to {@link #syntheticInterfaceIri(String)}.
     */
    public static String interfaceIriFor(final String wireName) {
        if (wireName == null) return null;
        return WIRE_TO_IRI.get(wireName);
    }

    /**
     * Resolve an interface IRI back to its wire-format name, or
     * {@code null} if the IRI is not in the enumerated set. Callers that
     * received the IRI from the store and want to compare against a
     * {@link CapabilityGrant#allowsInterface(String)} use this.
     */
    public static String wireNameFor(final String iri) {
        if (iri == null) return null;
        return IRI_TO_WIRE.get(iri);
    }

    /**
     * Build an interface IRI from a wire-format name that is not in the
     * enumerated set (forward-compat with a new host-callback interface
     * shipped ahead of a vocabulary refresh). Converts kebab-case to
     * CamelCase — {@code "new-callbacks"} becomes
     * {@code "<NAMESPACE>NewCallbacks"}. Deterministic and stable across
     * JVMs.
     */
    public static String syntheticInterfaceIri(final String wireName) {
        if (wireName == null || wireName.isEmpty()) return null;
        return NAMESPACE + kebabToPascal(wireName);
    }

    /**
     * The immutable snapshot of the wire-name → IRI map. Callers walking
     * the entire enumerated surface (admin tooling, tests) use this.
     */
    public static Map<String, String> knownInterfaces() {
        return WIRE_TO_IRI;
    }

    /**
     * Snapshot of the enumerated wire-name interface set — a view over
     * {@link #knownInterfaces()}'s keys.
     */
    public static Set<String> knownInterfaceWireNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(WIRE_TO_IRI.keySet()));
    }

    private static String kebabToPascal(final String s) {
        final StringBuilder out = new StringBuilder(s.length());
        boolean upper = true;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '-' || c == '_') {
                upper = true;
                continue;
            }
            if (upper) {
                out.append(Character.toUpperCase(c));
                upper = false;
            } else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }
}
