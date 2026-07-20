package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Engine-level unit tests for {@link SinkSparqlEngine}.
 *
 * <p>Drives the per-invocation MemoryStore lifecycle directly against
 * a {@link SinkEntry} pre-populated with WIT quads (same
 * {@link TestSinkCallbacks#quad(String, String, String)} helper the
 * write-path tests use). Pure JVM — no Stardog / wasm engine startup —
 * so it runs unconditionally in {@link WasmTestSuite}.
 */
public class TestSinkSparqlEngine {

    @Before
    public void setUp() {
        SinkRegistry.INSTANCE.reset();
    }

    @After
    public void tearDown() {
        SinkRegistry.INSTANCE.reset();
    }

    @Test
    public void happyPathSelectStarReturnsThreeSolutions() {
        final SinkEntry alpha = SinkRegistry.INSTANCE.register("alpha");
        alpha.addQuad(TestSinkCallbacks.quad("http://ex/s1", "http://ex/p", "http://ex/o1"));
        alpha.addQuad(TestSinkCallbacks.quad("http://ex/s2", "http://ex/p", "http://ex/o2"));
        alpha.addQuad(TestSinkCallbacks.quad("http://ex/s1", "http://ex/q", "http://ex/o3"));

        final List<ComponentVal> flat = SinkSparqlEngine.INSTANCE.evaluate(
                alpha, "SELECT ?s ?p ?o WHERE { ?s ?p ?o }");
        // Flat list == 3 solutions x 3 bindings each = 9 entries.
        assertThat(flat).hasSize(9);
        // First binding of first row projects ?s -> http://ex/s* iri.
        assertThat(flat.get(0).asRecord().get("variable").asString()).isEqualTo("s");
        assertThat(flat.get(0).asRecord().get("value").asVariant().getCaseName())
                .isEqualTo("named-node");
    }

    @Test
    public void bgpFilterReducesToMatchingSolution() {
        final SinkEntry alpha = SinkRegistry.INSTANCE.register("alpha");
        alpha.addQuad(TestSinkCallbacks.quad("http://ex/s1", "http://ex/p", "http://ex/o1"));
        alpha.addQuad(TestSinkCallbacks.quad("http://ex/s2", "http://ex/p", "http://ex/o2"));

        final List<ComponentVal> flat = SinkSparqlEngine.INSTANCE.evaluate(alpha,
                "SELECT ?o WHERE { <http://ex/s1> <http://ex/p> ?o }");
        // One row x one binding (?o).
        assertThat(flat).hasSize(1);
        assertThat(flat.get(0).asRecord().get("variable").asString()).isEqualTo("o");
        assertThat(flat.get(0).asRecord().get("value").asVariant()
                .getPayload().orElseThrow().asString())
                .isEqualTo("http://ex/o1");
    }

    @Test
    public void emptySinkReturnsEmptyList() {
        final SinkEntry alpha = SinkRegistry.INSTANCE.register("alpha");
        final List<ComponentVal> flat = SinkSparqlEngine.INSTANCE.evaluate(
                alpha, "SELECT ?s WHERE { ?s ?p ?o }");
        assertThat(flat).isEmpty();
    }

    @Test
    public void noResultsPatternReturnsEmptyList() {
        final SinkEntry alpha = SinkRegistry.INSTANCE.register("alpha");
        alpha.addQuad(TestSinkCallbacks.quad("http://ex/s1", "http://ex/p", "http://ex/o1"));
        final List<ComponentVal> flat = SinkSparqlEngine.INSTANCE.evaluate(
                alpha, "SELECT ?s WHERE { <http://ex/gone> ?p ?o }");
        assertThat(flat).isEmpty();
    }

    @Test
    public void malformedSparqlRaisesSyntaxError() {
        final SinkEntry alpha = SinkRegistry.INSTANCE.register("alpha");
        final Throwable t = catchThrowable(() ->
                SinkSparqlEngine.INSTANCE.evaluate(alpha, "not valid sparql at all"));
        assertThat(t).isInstanceOf(SinkSparqlEngine.SyntaxError.class);
    }

    @Test
    public void constructQueryRaisesSyntaxErrorNotSelectShape() {
        // WIT contract: execute-sink-select is SELECT-only. CONSTRUCT
        // parses fine as a ParsedGraphQuery but does NOT satisfy the
        // ParsedTupleQuery type gate.
        final SinkEntry alpha = SinkRegistry.INSTANCE.register("alpha");
        final Throwable t = catchThrowable(() ->
                SinkSparqlEngine.INSTANCE.evaluate(alpha,
                        "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }"));
        assertThat(t).isInstanceOf(SinkSparqlEngine.SyntaxError.class);
        assertThat(t.getMessage()).contains("SELECT");
    }

    @Test
    public void literalObjectRoundTripsAsBindingValue() {
        final SinkEntry alpha = SinkRegistry.INSTANCE.register("alpha");
        // Load a quad with a literal object (built directly since
        // TestSinkCallbacks.quad only makes named-node triples).
        final java.util.Map<String, ComponentVal> fields = new java.util.LinkedHashMap<>();
        fields.put("subject", ComponentVal.variant("named-node",
                ComponentVal.string("http://ex/s1")));
        fields.put("predicate", ComponentVal.variant("named-node",
                ComponentVal.string("http://ex/label")));
        final java.util.Map<String, ComponentVal> litFields = new java.util.LinkedHashMap<>();
        litFields.put("value", ComponentVal.string("hello"));
        litFields.put("datatype", ComponentVal.none());
        litFields.put("language", ComponentVal.none());
        fields.put("object", ComponentVal.variant("literal",
                ComponentVal.record(litFields)));
        fields.put("graph", ComponentVal.none());
        alpha.addQuad(ComponentVal.record(fields));

        final List<ComponentVal> flat = SinkSparqlEngine.INSTANCE.evaluate(alpha,
                "SELECT ?label WHERE { ?s <http://ex/label> ?label }");
        assertThat(flat).hasSize(1);
        final ComponentVal valueTerm = flat.get(0).asRecord().get("value");
        assertThat(valueTerm.asVariant().getCaseName()).isEqualTo("literal");
        final java.util.Map<String, ComponentVal> out =
                valueTerm.asVariant().getPayload().orElseThrow().asRecord();
        assertThat(out.get("value").asString()).isEqualTo("hello");
    }

    @Test
    public void nullSparqlRaisesSyntaxError() {
        final SinkEntry alpha = SinkRegistry.INSTANCE.register("alpha");
        assertThat(catchThrowable(() ->
                SinkSparqlEngine.INSTANCE.evaluate(alpha, null)))
                .isInstanceOf(SinkSparqlEngine.SyntaxError.class);
    }

    @Test
    public void nullSinkEntryRaisesIllegalArgument() {
        assertThat(catchThrowable(() ->
                SinkSparqlEngine.INSTANCE.evaluate(null, "SELECT * WHERE { ?s ?p ?o }")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
