package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.TripleTerm;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Round-trip coverage for {@link SinkValueMarshaller},
 * {@link SinkStatementMarshaller}, and {@link SinkBindingMarshaller}.
 * Uses {@link SimpleValueFactory} directly — no Sail startup — so the
 * suite stays pure-JVM at this layer.
 */
public class TestSinkMarshallers {

    private final ValueFactory vf = SimpleValueFactory.getInstance();

    // ---- named-node round-trip -----------------------------------------

    @Test
    public void namedNodeRoundTrips() {
        final ComponentVal witIn = wnn("http://ex/s");
        final Value v = SinkValueMarshaller.fromWitTerm(witIn, vf);
        assertThat(v).isInstanceOf(IRI.class);
        assertThat(((IRI) v).stringValue()).isEqualTo("http://ex/s");

        final ComponentVal witOut = SinkValueMarshaller.toWitTerm(v);
        assertThat(witOut.asVariant().getCaseName()).isEqualTo("named-node");
        assertThat(witOut.asVariant().getPayload().orElseThrow().asString())
                .isEqualTo("http://ex/s");
    }

    // ---- blank-node round-trip -----------------------------------------

    @Test
    public void blankNodeRoundTripsPreservingId() {
        final ComponentVal witIn = wbn("b1");
        final Value v = SinkValueMarshaller.fromWitTerm(witIn, vf);
        assertThat(v).isInstanceOf(BNode.class);
        assertThat(((BNode) v).getID()).isEqualTo("b1");

        final ComponentVal witOut = SinkValueMarshaller.toWitTerm(v);
        assertThat(witOut.asVariant().getCaseName()).isEqualTo("blank-node");
        assertThat(witOut.asVariant().getPayload().orElseThrow().asString())
                .isEqualTo("b1");
    }

    // ---- literal round-trip: plain, datatyped, lang-tagged --------------

    @Test
    public void plainLiteralRoundTripsAsXsdString() {
        final ComponentVal witIn = wlitPlain("hello");
        final Value v = SinkValueMarshaller.fromWitTerm(witIn, vf);
        assertThat(v).isInstanceOf(Literal.class);
        final Literal lit = (Literal) v;
        assertThat(lit.getLabel()).isEqualTo("hello");
        assertThat(lit.getDatatype().stringValue())
                .isEqualTo(SinkValueMarshaller.XSD_STRING);
        assertThat(lit.getLanguage()).isEmpty();

        final ComponentVal witOut = SinkValueMarshaller.toWitTerm(v);
        final Map<String, ComponentVal> fields =
                witOut.asVariant().getPayload().orElseThrow().asRecord();
        assertThat(fields.get("value").asString()).isEqualTo("hello");
        // datatype absent — xsd:string collapses to none per WIT default.
        assertThat(fields.get("datatype").asSome()).isEmpty();
        assertThat(fields.get("language").asSome()).isEmpty();
    }

    @Test
    public void datatypedLiteralRoundTrips() {
        final String dt = "http://www.w3.org/2001/XMLSchema#integer";
        final ComponentVal witIn = wlitDatatyped("42", dt);
        final Value v = SinkValueMarshaller.fromWitTerm(witIn, vf);
        assertThat(v).isInstanceOf(Literal.class);
        assertThat(((Literal) v).getLabel()).isEqualTo("42");
        assertThat(((Literal) v).getDatatype().stringValue()).isEqualTo(dt);

        final ComponentVal witOut = SinkValueMarshaller.toWitTerm(v);
        final Map<String, ComponentVal> fields =
                witOut.asVariant().getPayload().orElseThrow().asRecord();
        assertThat(fields.get("datatype").asSome().orElseThrow().asString())
                .isEqualTo(dt);
    }

    @Test
    public void langTaggedLiteralRoundTripsWithoutDatatype() {
        final ComponentVal witIn = wlitLang("bonjour", "fr");
        final Value v = SinkValueMarshaller.fromWitTerm(witIn, vf);
        assertThat(v).isInstanceOf(Literal.class);
        assertThat(((Literal) v).getLabel()).isEqualTo("bonjour");
        assertThat(((Literal) v).getLanguage()).contains("fr");

        final ComponentVal witOut = SinkValueMarshaller.toWitTerm(v);
        final Map<String, ComponentVal> fields =
                witOut.asVariant().getPayload().orElseThrow().asRecord();
        assertThat(fields.get("language").asSome().orElseThrow().asString())
                .isEqualTo("fr");
        // datatype absent on lang-tagged literal — mirrors base encoder.
        assertThat(fields.get("datatype").asSome()).isEmpty();
    }

    // ---- RDF-star quoted triple round-trip -----------------------------

    @Test
    public void quotedTripleRoundTrips() {
        final Map<String, ComponentVal> tt = new LinkedHashMap<>();
        tt.put("subject", wnn("http://ex/s"));
        tt.put("predicate", wnn("http://ex/p"));
        tt.put("object", wnn("http://ex/o"));
        final ComponentVal witIn = ComponentVal.variant("triple", ComponentVal.record(tt));

        final Value v = SinkValueMarshaller.fromWitTerm(witIn, vf);
        assertThat(v).isInstanceOf(TripleTerm.class);
        final TripleTerm t = (TripleTerm) v;
        assertThat(t.getSubject().stringValue()).isEqualTo("http://ex/s");
        assertThat(t.getPredicate().stringValue()).isEqualTo("http://ex/p");
        assertThat(t.getObject().stringValue()).isEqualTo("http://ex/o");

        final ComponentVal witOut = SinkValueMarshaller.toWitTerm(v);
        assertThat(witOut.asVariant().getCaseName()).isEqualTo("triple");
        final Map<String, ComponentVal> fields =
                witOut.asVariant().getPayload().orElseThrow().asRecord();
        assertThat(fields.get("subject").asVariant().getCaseName()).isEqualTo("named-node");
    }

    // ---- statement rollup ----------------------------------------------

    @Test
    public void quadRecordRollsUpAsStatement() {
        final ComponentVal quad = TestSinkCallbacks.quad(
                "http://ex/s", "http://ex/p", "http://ex/o");
        final Statement s = SinkStatementMarshaller.fromWitQuad(quad, vf);
        assertThat(s.getSubject().stringValue()).isEqualTo("http://ex/s");
        assertThat(s.getPredicate().stringValue()).isEqualTo("http://ex/p");
        assertThat(s.getObject().stringValue()).isEqualTo("http://ex/o");
        // graph = none in the shared test helper -> default context.
        assertThat(s.getContext()).isNull();
    }

    @Test
    public void quadRecordWithGraphRollsUpWithContext() {
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("subject", wnn("http://ex/s"));
        fields.put("predicate", wnn("http://ex/p"));
        fields.put("object", wnn("http://ex/o"));
        fields.put("graph", ComponentVal.some(ComponentVal.string("http://ex/g")));
        final Statement s = SinkStatementMarshaller.fromWitQuad(
                ComponentVal.record(fields), vf);
        assertThat(s.getContext()).isNotNull();
        assertThat(s.getContext().stringValue()).isEqualTo("http://ex/g");
    }

    @Test
    public void missingSubjectFieldSurfacesAsIllegalArgument() {
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("predicate", wnn("http://ex/p"));
        fields.put("object", wnn("http://ex/o"));
        fields.put("graph", ComponentVal.none());
        assertThat(catchThrowable(() ->
                SinkStatementMarshaller.fromWitQuad(ComponentVal.record(fields), vf)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
    }

    // ---- binding marshalling -------------------------------------------

    @Test
    public void bindingSetProducesOneBindingPerBoundVariable() {
        final MapBindingSet bs = new MapBindingSet();
        bs.addBinding("s", vf.createIRI("http://ex/s"));
        bs.addBinding("p", vf.createIRI("http://ex/p"));
        bs.addBinding("o", vf.createLiteral("42",
                vf.createIRI("http://www.w3.org/2001/XMLSchema#integer")));
        final List<ComponentVal> bindings = SinkBindingMarshaller.toWitBindings(bs);
        assertThat(bindings).hasSize(3);
        // Each row is a record { variable: string, value: term }.
        assertThat(bindings.get(0).asRecord().get("variable").asString()).isEqualTo("s");
        assertThat(bindings.get(0).asRecord().get("value").asVariant().getCaseName())
                .isEqualTo("named-node");
        assertThat(bindings.get(2).asRecord().get("value").asVariant().getCaseName())
                .isEqualTo("literal");
    }

    @Test
    public void flattenSolutionsInterleavesInRowMajorOrder() {
        final MapBindingSet r1 = new MapBindingSet();
        r1.addBinding("x", vf.createIRI("http://ex/a"));
        r1.addBinding("y", vf.createIRI("http://ex/b"));
        final MapBindingSet r2 = new MapBindingSet();
        r2.addBinding("x", vf.createIRI("http://ex/c"));
        r2.addBinding("y", vf.createIRI("http://ex/d"));

        final List<ComponentVal> flat = SinkBindingMarshaller.flattenSolutions(
                List.<BindingSet>of(r1, r2));
        assertThat(flat).hasSize(4);
        // Repeated 'x' variable is the row-boundary marker per WIT contract.
        assertThat(flat.get(0).asRecord().get("variable").asString()).isEqualTo("x");
        assertThat(flat.get(2).asRecord().get("variable").asString()).isEqualTo("x");
    }

    // ---- helpers -------------------------------------------------------

    private static ComponentVal wnn(final String iri) {
        return ComponentVal.variant("named-node", ComponentVal.string(iri));
    }

    private static ComponentVal wbn(final String id) {
        return ComponentVal.variant("blank-node", ComponentVal.string(id));
    }

    private static ComponentVal wlitPlain(final String value) {
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("value", ComponentVal.string(value));
        fields.put("datatype", ComponentVal.none());
        fields.put("language", ComponentVal.none());
        return ComponentVal.variant("literal", ComponentVal.record(fields));
    }

    private static ComponentVal wlitDatatyped(final String value, final String datatype) {
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("value", ComponentVal.string(value));
        fields.put("datatype", ComponentVal.some(ComponentVal.string(datatype)));
        fields.put("language", ComponentVal.none());
        return ComponentVal.variant("literal", ComponentVal.record(fields));
    }

    private static ComponentVal wlitLang(final String value, final String lang) {
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("value", ComponentVal.string(value));
        fields.put("datatype", ComponentVal.none());
        fields.put("language", ComponentVal.some(ComponentVal.string(lang)));
        return ComponentVal.variant("literal", ComponentVal.record(fields));
    }
}
