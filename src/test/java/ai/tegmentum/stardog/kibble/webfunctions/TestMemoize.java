package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.AbstractStardogTest;
import com.stardog.stark.Literal;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.Test;

import java.util.Optional;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

/**
 * SPARQL-surface tests for wf:memoize. Ports the pre-migration
 * TestMemoize (which loaded the retired MODULE to_upper.wasm) onto
 * the checked-in example-uppercase-extension component at
 * src/test/resources/integration/.
 *
 * <p>The wf:compose subtest was dropped alongside TestCompose in the
 * flat-ABI deletion batch — wf:memoize over a composed function still
 * dispatches the same way, but composing two wasm components (or a
 * wasm + built-in) is exercised by the compose orchestrator's own
 * tests (TestWebFunctionComposeService) rather than repeated here.
 */
public class TestMemoize extends AbstractStardogTest {

    private static final String WASM =
            "file:src/test/resources/integration/example_uppercase_extension.wasm";

    final String queryHeader = WebFunctionVocabulary.sparqlPrefix("wf", "0.0.0");

    @Test
    public void testIriFunctionNoArgs() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:memoize(10, \"PI\") AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isPresent();
            assertThat(aPossibleLiteral.get().label()).isEqualTo("3.141592653589793");
        }
    }

    @Test
    public void testIriFunction() {

        final String aQuery = queryHeader +
            " SELECT ?result WHERE { BIND(wf:memoize(10, \"" + WASM + "\", \"Hello world\" ) AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isPresent();
            assertThat(aPossibleLiteral.get().label()).isEqualTo("HELLO WORLD");
        }
    }

    @Test
    public void testStringFunction() {

        final String aQuery = queryHeader +
                 " SELECT ?result WHERE { BIND(wf:memoize(10, \"UCASE\", \"Hello world\") AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isPresent();
            assertThat(aPossibleLiteral.get().label()).isEqualTo("HELLO WORLD");
        }
    }
}
