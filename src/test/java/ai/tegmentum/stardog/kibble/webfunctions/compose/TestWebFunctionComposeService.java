package ai.tegmentum.stardog.kibble.webfunctions.compose;

import java.util.List;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Turtle serialization helpers on
 * {@link WebFunctionComposeService} — full SPARQL SERVICE end-to-end
 * dispatch lands in {@code ComposeIntegrationIT} where the Stardog
 * server is real.
 *
 * <p>Deliberately narrow scope: the compile-time surface (extracting
 * plan triples from ScanNodes, wiring the operator's evaluate call)
 * requires an ExecutionContext + PlanNode + Kernel triple that's cheap
 * to hand-mock but adds a lot of surface. The Turtle serializer is
 * the only piece with an interesting standalone contract — round-trip
 * parity with {@code compose-rdf}'s writer half.
 */
public class TestWebFunctionComposeService {

    @Test
    public void turtleSerializerEmitsPrefixHeaderAndComponentShortForm() {
        final com.stardog.stark.IRI plan = com.stardog.stark.Values.iri("urn:composition:plan");
        final com.stardog.stark.IRI planClass =
                com.stardog.stark.Values.iri("http://tegmentum.ai/ns/composition/CompositionPlan");
        final com.stardog.stark.IRI rdfType =
                com.stardog.stark.Values.iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");

        final List<WebFunctionComposeService.TripleRow> triples = List.of(
                new WebFunctionComposeService.TripleRow(plan, rdfType, planClass));
        final String ttl = WebFunctionComposeService.TurtleSerializer.render(triples);

        assertThat(ttl).contains("@prefix comp: <http://tegmentum.ai/ns/composition/>");
        // rdf:type short form (`a`) + comp: prefix compression on the class IRI.
        assertThat(ttl).contains(" a comp:CompositionPlan .");
    }

    @Test
    public void turtleSerializerHandlesLiteralWithDatatype() {
        final com.stardog.stark.IRI subject = com.stardog.stark.Values.iri("urn:s");
        final com.stardog.stark.IRI predicate = com.stardog.stark.Values.iri(
                "http://tegmentum.ai/ns/composition/memoryBytes");
        final com.stardog.stark.Literal lit =
                com.stardog.stark.Values.literal(1024L);

        final List<WebFunctionComposeService.TripleRow> triples = List.of(
                new WebFunctionComposeService.TripleRow(subject, predicate, lit));
        final String ttl = WebFunctionComposeService.TurtleSerializer.render(triples);

        assertThat(ttl).contains("comp:memoryBytes");
        assertThat(ttl).contains("\"1024\"");
        // long → xsd:long is a non-default datatype and must be emitted.
        assertThat(ttl).contains("^^<http://www.w3.org/2001/XMLSchema#long>");
    }

    @Test
    public void turtleSerializerOmitsXsdStringForPlainLiterals() {
        final com.stardog.stark.IRI subject = com.stardog.stark.Values.iri("urn:s");
        final com.stardog.stark.IRI predicate = com.stardog.stark.Values.iri(
                "http://tegmentum.ai/ns/composition/root");
        final com.stardog.stark.Literal lit = com.stardog.stark.Values.literal("hello");

        final List<WebFunctionComposeService.TripleRow> triples = List.of(
                new WebFunctionComposeService.TripleRow(subject, predicate, lit));
        final String ttl = WebFunctionComposeService.TurtleSerializer.render(triples);

        assertThat(ttl).contains("comp:root \"hello\" .");
        assertThat(ttl).doesNotContain("xsd:string");
        assertThat(ttl).doesNotContain("XMLSchema#string");
    }

    @Test
    public void turtleSerializerEscapesLiteralQuotes() {
        final com.stardog.stark.IRI subject = com.stardog.stark.Values.iri("urn:s");
        final com.stardog.stark.IRI predicate =
                com.stardog.stark.Values.iri("http://tegmentum.ai/ns/composition/version");
        final com.stardog.stark.Literal lit = com.stardog.stark.Values.literal("has \"quotes\"");

        final List<WebFunctionComposeService.TripleRow> triples = List.of(
                new WebFunctionComposeService.TripleRow(subject, predicate, lit));
        final String ttl = WebFunctionComposeService.TurtleSerializer.render(triples);

        assertThat(ttl).contains("\\\"quotes\\\"");
    }

    @Test
    public void turtleSerializerHandlesBlankNodeSubject() {
        final com.stardog.stark.BNode bn = com.stardog.stark.Values.bnode("c1");
        final com.stardog.stark.IRI predicate = com.stardog.stark.Values.iri(
                "http://tegmentum.ai/ns/composition/id");
        final com.stardog.stark.Literal lit = com.stardog.stark.Values.literal("root");

        final List<WebFunctionComposeService.TripleRow> triples = List.of(
                new WebFunctionComposeService.TripleRow(bn, predicate, lit));
        final String ttl = WebFunctionComposeService.TurtleSerializer.render(triples);

        assertThat(ttl).contains("_:c1 comp:id \"root\" .");
    }

    @Test
    public void turtleSerializerFallsBackToAngleBracketedIriOutsideCompNs() {
        final com.stardog.stark.IRI subject = com.stardog.stark.Values.iri("urn:s");
        final com.stardog.stark.IRI predicate = com.stardog.stark.Values.iri("urn:foo:bar");
        final com.stardog.stark.IRI object = com.stardog.stark.Values.iri("urn:baz");

        final List<WebFunctionComposeService.TripleRow> triples = List.of(
                new WebFunctionComposeService.TripleRow(subject, predicate, object));
        final String ttl = WebFunctionComposeService.TurtleSerializer.render(triples);

        assertThat(ttl).contains("<urn:s>");
        assertThat(ttl).contains("<urn:foo:bar>");
        assertThat(ttl).contains("<urn:baz>");
    }
}
