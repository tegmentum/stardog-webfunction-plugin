package ai.tegmentum.stardog.kibble.webfunctions.compose;

import com.stardog.stark.Statement;
import com.stardog.stark.Values;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ComposePolicyStoreWriter}'s SPARQL-building
 * helpers. The full write() path is exercised in the integration
 * suite where a Kernel is available.
 */
public class TestComposePolicyStoreWriter {

    @Test
    public void appendStatementFormatsIriTriple() {
        final Statement stmt = Values.statement(
                Values.iri("urn:composition:plan"),
                Values.iri("http://tegmentum.ai/ns/composition/version"),
                Values.literal("v1"));
        final StringBuilder out = new StringBuilder();
        ComposePolicyStoreWriter.appendStatement(out, stmt);
        assertThat(out.toString())
                .isEqualTo("<urn:composition:plan> "
                        + "<http://tegmentum.ai/ns/composition/version> "
                        + "\"v1\" . ");
    }

    @Test
    public void appendStatementFormatsTypedLiteralWithDatatype() {
        final Statement stmt = Values.statement(
                Values.iri("urn:composition:plan"),
                Values.iri("http://tegmentum.ai/ns/composition/size"),
                Values.literal(1024L));
        final StringBuilder out = new StringBuilder();
        ComposePolicyStoreWriter.appendStatement(out, stmt);
        assertThat(out.toString())
                .contains("<http://www.w3.org/2001/XMLSchema#long>")
                .contains("\"1024\"");
    }

    @Test
    public void appendStatementFormatsIriObject() {
        final Statement stmt = Values.statement(
                Values.iri("urn:composition:plan"),
                Values.iri("http://tegmentum.ai/ns/composition/root"),
                Values.iri("urn:comp:root"));
        final StringBuilder out = new StringBuilder();
        ComposePolicyStoreWriter.appendStatement(out, stmt);
        assertThat(out.toString())
                .isEqualTo("<urn:composition:plan> "
                        + "<http://tegmentum.ai/ns/composition/root> "
                        + "<urn:comp:root> . ");
    }

    @Test
    public void escapeLiteralHandlesQuotesAndBackslash() {
        final String escaped = ComposePolicyStoreWriter.escapeLiteral(
                "with \"quotes\" and \\ backslash and\nnewline");
        assertThat(escaped).isEqualTo(
                "with \\\"quotes\\\" and \\\\ backslash and\\nnewline");
    }

    @Test
    public void escapeIriStripsProblematicCharacters() {
        assertThat(ComposePolicyStoreWriter.escapeIri("urn:test:ok")).isEqualTo("urn:test:ok");
        assertThat(ComposePolicyStoreWriter.escapeIri("<injected>")).isEqualTo("_injected_");
        assertThat(ComposePolicyStoreWriter.escapeIri("space here")).isEqualTo("space_here");
    }
}
