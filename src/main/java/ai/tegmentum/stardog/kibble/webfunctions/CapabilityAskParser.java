package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.common.base.Options;
import com.stardog.stark.Literal;
import com.stardog.stark.Statement;
import com.stardog.stark.Value;
import com.stardog.stark.io.ParserOptions;
import com.stardog.stark.io.RDFFormats;
import com.stardog.stark.io.RDFParsers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Parses the Turtle payload embedded in the {@code
 * stardog.capability-ask} wasm custom section into a
 * {@link CapabilityAsk}.
 *
 * <p>Uses Stardog's own Turtle parser (Stark) from the plugin classpath
 * — no new dependency, no runtime allocation of an RDF4J graph model.
 * The parser reads statements into a {@link java.util.Set}, then walks
 * them once, projecting predicate IRIs from the {@code cap:asks*}
 * family onto the corresponding {@link CapabilityAsk} axis.
 *
 * <p>Relative IRIs in the source resolve against the extension URL as
 * the base — the memo (§4) commits authors to writing {@code <>} for
 * "this document" and letting the plugin re-anchor at load time. Stark
 * exposes this via {@link ParserOptions#baseIRI}.
 *
 * <p>Parse failures throw {@link IOException} up to the caller in
 * {@link StardogWasmInstance}, which per capability-ask memo §6 catches
 * and logs a warning ("unparseable capability ask; admin cannot review
 * via SPARQL") rather than failing the extension load. Ask insertion is
 * best-effort; grants still gate execution.
 */
public final class CapabilityAskParser {

    private static final String P_ASKS_INTERFACE   = CapabilityVocabulary.NAMESPACE + "asksInterface";
    private static final String P_ASKS_METHOD      = CapabilityVocabulary.NAMESPACE + "asksMethod";
    private static final String P_ASKS_HOST        = CapabilityVocabulary.NAMESPACE + "asksHost";
    private static final String P_ASKS_HTTP_PATH   = CapabilityVocabulary.NAMESPACE + "asksHttpPath";
    private static final String P_ASKS_WASM_CALLEE = CapabilityVocabulary.NAMESPACE + "asksWasmCallee";
    private static final String P_ASKS_RATIONALE   = CapabilityVocabulary.NAMESPACE + "asksRationale";

    private CapabilityAskParser() {}

    /**
     * Parse Turtle bytes into an ask. Base URL is used to resolve
     * relative IRIs in the source; when {@code null}, Stark's default
     * base ({@code "http://localhost/"}) is used, which is fine for
     * predicates that are always absolute and object literals that are
     * plain strings — the failure mode is that a relative object IRI
     * (rare in ask docs) would resolve against the wrong base.
     *
     * @param turtleBytes Turtle-encoded ask document — typically the
     *                    payload of the {@code stardog.capability-ask}
     *                    custom section, but the parser doesn't care
     *                    about provenance.
     * @param baseUrl     the extension's load URL, so {@code <>} in the
     *                    source becomes an IRI keyed off it. Optional.
     * @return the parsed ask, or {@link CapabilityAsk#EMPTY} when the
     *         Turtle carried no {@code cap:asks*} triples at all.
     * @throws IOException on Turtle parse failure.
     */
    public static CapabilityAsk parse(final byte[] turtleBytes,
                                       final URL baseUrl) throws IOException {
        Objects.requireNonNull(turtleBytes, "turtleBytes");

        final Options options = baseUrl == null
                ? ParserOptions.defaults()
                : ParserOptions.baseIRI(baseUrl.toString());

        final Set<Statement> triples;
        try (ByteArrayInputStream in = new ByteArrayInputStream(turtleBytes)) {
            triples = RDFParsers.read(in, RDFFormats.TURTLE, options);
        }

        final Set<String> interfaces  = new LinkedHashSet<>();
        final Set<String> methods     = new LinkedHashSet<>();
        final Set<String> hosts       = new LinkedHashSet<>();
        final Set<String> httpPaths   = new LinkedHashSet<>();
        final Set<String> wasmCallees = new LinkedHashSet<>();
        String rationale = null;

        for (final Statement stmt : triples) {
            final String p = stmt.predicate().toString();
            final Value o = stmt.object();
            if (P_ASKS_INTERFACE.equals(p)) {
                interfaces.add(valueToString(o));
            } else if (P_ASKS_METHOD.equals(p)) {
                methods.add(valueToString(o));
            } else if (P_ASKS_HOST.equals(p)) {
                hosts.add(valueToString(o));
            } else if (P_ASKS_HTTP_PATH.equals(p)) {
                httpPaths.add(valueToString(o));
            } else if (P_ASKS_WASM_CALLEE.equals(p)) {
                wasmCallees.add(valueToString(o));
            } else if (P_ASKS_RATIONALE.equals(p)) {
                // Last-writer-wins on multiple rationales; the memo
                // §4 encourages one per document.
                rationale = valueToString(o);
            }
            // Any other predicate is silently ignored — the ask
            // vocabulary is flat by design (§4), and admin review picks
            // up nonsense that parses but doesn't map to a known axis.
        }

        if (interfaces.isEmpty() && methods.isEmpty() && hosts.isEmpty()
                && httpPaths.isEmpty() && wasmCallees.isEmpty()
                && rationale == null) {
            return CapabilityAsk.EMPTY;
        }
        return new CapabilityAsk(
                interfaces, methods, hosts, httpPaths, wasmCallees,
                Optional.ofNullable(rationale));
    }

    /**
     * String representation of an RDF term for ask storage: literal
     * lexical form for literals, IRI/bnode ID for resources. Preserves
     * the shape the author wrote in Turtle without dragging RDF term
     * types into the ask data model.
     */
    private static String valueToString(final Value v) {
        if (v instanceof Literal) return ((Literal) v).label();
        return v.toString();
    }
}
