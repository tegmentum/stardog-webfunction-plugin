package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capability-ask CA2 — {@link CapabilityAsk} data model +
 * {@link CapabilityAskParser} Turtle parser.
 *
 * <p>Round-trips minimal Turtle documents through the parser and
 * asserts the projected {@link CapabilityAsk} carries the expected
 * per-axis sets. Covers: empty ask, all axes populated, IRI vs literal
 * object shapes, rationale attached, unknown-predicate ignore path,
 * relative IRI resolution against the extension base URL,
 * {@link CapabilityAsk#declaresInterface} + {@link CapabilityAsk#declaresMethod}
 * lookup used by the warn-on-undeclared diagnostic.
 */
public class TestCapabilityAsk {

    private static final String NS = CapabilityVocabulary.NAMESPACE;

    private static byte[] turtle(final String body) {
        final String doc =
                "@prefix cap: <" + NS + "> .\n"
                + body;
        return doc.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void emptyRecordIsEmpty() {
        assertThat(CapabilityAsk.EMPTY.isEmpty()).isTrue();
        assertThat(CapabilityAsk.EMPTY.asksInterfaces()).isEmpty();
        assertThat(CapabilityAsk.EMPTY.rationale()).isEmpty();
    }

    @Test
    public void collectionsAreDefensivelyCopied() {
        final Set<String> mut = new java.util.LinkedHashSet<>();
        mut.add("http-callbacks");
        final CapabilityAsk ask = new CapabilityAsk(
                mut, Set.of(), Set.of(), Set.of(), Set.of(), Optional.empty());
        mut.add("mutated-after");
        assertThat(ask.asksInterfaces()).containsExactly("http-callbacks");
    }

    @Test
    public void parseTurtleWithAllAxesPopulated() throws IOException {
        final byte[] doc = turtle(
                "<> a cap:CapabilityAsk ;\n"
                + "    cap:asksInterface cap:HttpCallbacks, cap:GraphCallbacks ;\n"
                + "    cap:asksMethod cap:HttpCallbacks_Get ;\n"
                + "    cap:asksHost \"api.acme.com\" ;\n"
                + "    cap:asksHttpPath \"api.acme.com/oauth/\" ;\n"
                + "    cap:asksWasmCallee <ipfs://QmCallee> ;\n"
                + "    cap:asksRationale \"OAuth token exchange\" .\n");

        final CapabilityAsk ask = CapabilityAskParser.parse(
                doc, new URL("http://example.org/QmExtension"));

        assertThat(ask.asksInterfaces()).containsExactlyInAnyOrder(
                NS + "HttpCallbacks", NS + "GraphCallbacks");
        assertThat(ask.asksMethods()).containsExactly(NS + "HttpCallbacks_Get");
        assertThat(ask.asksHosts()).containsExactly("api.acme.com");
        assertThat(ask.asksHttpPaths()).containsExactly("api.acme.com/oauth/");
        assertThat(ask.asksWasmCallees()).containsExactly("ipfs://QmCallee");
        assertThat(ask.rationale()).contains("OAuth token exchange");
    }

    @Test
    public void parseTurtleWithNoAskPredicatesReturnsEmpty() throws IOException {
        // Parseable Turtle that carries no cap:asks* triples — should
        // return CapabilityAsk.EMPTY (the memo §6 fail-open case: parse
        // succeeded, no ask content).
        final byte[] doc = turtle(
                "<> <http://example/other> <http://example/whatever> .\n");

        final CapabilityAsk ask = CapabilityAskParser.parse(doc, null);

        assertThat(ask).isEqualTo(CapabilityAsk.EMPTY);
        assertThat(ask.isEmpty()).isTrue();
    }

    @Test
    public void parserIgnoresUnknownPredicates() throws IOException {
        final byte[] doc = turtle(
                "<> cap:asksHost \"api.acme.com\" ;\n"
                + "    <http://example/random> \"garbage\" ;\n"
                + "    cap:notARealPredicate <http://example/whatever> .\n");

        final CapabilityAsk ask = CapabilityAskParser.parse(doc, null);

        assertThat(ask.asksHosts()).containsExactly("api.acme.com");
        assertThat(ask.asksInterfaces()).isEmpty();
        assertThat(ask.asksMethods()).isEmpty();
    }

    @Test
    public void parseTurtleAcceptsMissingRationale() throws IOException {
        final byte[] doc = turtle(
                "<> cap:asksInterface cap:HttpCallbacks .\n");

        final CapabilityAsk ask = CapabilityAskParser.parse(doc, null);

        assertThat(ask.rationale()).isEmpty();
        assertThat(ask.asksInterfaces()).containsExactly(NS + "HttpCallbacks");
    }

    @Test
    public void malformedTurtleThrows() {
        final byte[] doc = "this is not @# valid turtle }} <><".getBytes(StandardCharsets.UTF_8);
        try {
            CapabilityAskParser.parse(doc, null);
            org.junit.Assert.fail("expected IOException on malformed Turtle");
        } catch (IOException | RuntimeException expected) {
            // ok — either checked or unchecked from the parser is
            // acceptable; caller in StardogWasmInstance wraps both.
        }
    }

    @Test
    public void declaresInterfaceMatchesEitherIriOrWireName() {
        final CapabilityAsk askByIri = new CapabilityAsk(
                Set.of(CapabilityVocabulary.IFACE_HTTP_CALLBACKS),
                Set.of(), Set.of(), Set.of(), Set.of(), Optional.empty());
        // Ask stored as IRI, caller checks wire name — must translate.
        assertThat(askByIri.declaresInterface("http-callbacks")).isTrue();
        assertThat(askByIri.declaresInterface("graph-callbacks")).isFalse();

        final CapabilityAsk askByWire = new CapabilityAsk(
                Set.of("graph-callbacks"),
                Set.of(), Set.of(), Set.of(), Set.of(), Optional.empty());
        assertThat(askByWire.declaresInterface("graph-callbacks")).isTrue();
    }

    @Test
    public void declaresMethodMatchesBareOrVocabularyForm() {
        // Vocabulary form (Interface_Method under cap:).
        final CapabilityAsk askVocab = new CapabilityAsk(
                Set.of(),
                Set.of(CapabilityVocabulary.METHOD_HTTP_CALLBACKS_GET),
                Set.of(), Set.of(), Set.of(), Optional.empty());
        assertThat(askVocab.declaresMethod("http-callbacks", "get")).isTrue();
        assertThat(askVocab.declaresMethod("http-callbacks", "post")).isFalse();

        // Bare form the author might write as a shortcut.
        final CapabilityAsk askBare = new CapabilityAsk(
                Set.of(),
                Set.of("graph-callbacks/execute-query"),
                Set.of(), Set.of(), Set.of(), Optional.empty());
        assertThat(askBare.declaresMethod("graph-callbacks", "execute-query")).isTrue();
    }

    @Test
    public void parseWithBaseUrlResolvesRelativeIRIs() throws IOException {
        // The <> subject resolves against the extension URL; the parser
        // only needs to make the base URL available to Stark. This test
        // just proves the parse-with-base path doesn't crash on <>.
        final byte[] doc = turtle(
                "<> cap:asksInterface cap:HttpCallbacks .\n");

        final CapabilityAsk ask = CapabilityAskParser.parse(
                doc, new URL("http://example.org/QmSomeCid"));

        assertThat(ask.asksInterfaces()).containsExactly(NS + "HttpCallbacks");
    }
}
