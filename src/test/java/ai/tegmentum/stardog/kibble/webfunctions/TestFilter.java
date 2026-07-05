package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.AbstractStardogTest;
import com.stardog.stark.Literal;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.Test;

import java.util.Optional;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

public class TestFilter extends AbstractStardogTest {

    final String queryHeader = WebFunctionVocabulary.sparqlPrefix("wf", "0.0.0") +
            " prefix f: <file:src/test/rust/target/wasm32-unknown-unknown/release/> ";

    @Test
    public void testIsNumericTrue() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:call(str(f:is_numeric.wasm), 1) AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isPresent();
            assertThat(aPossibleLiteral.get().label()).isEqualTo("true");
        }
    }

    @Test
    public void testIsNumericFalse() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:call(str(f:is_numeric.wasm), \"stardog\") AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isPresent();
            assertThat(aPossibleLiteral.get().label()).isEqualTo("false");
        }
    }

    @Test
    public void testIriFunctionNoArgs() {

        final String aQuery = queryHeader +
            " SELECT ?result WHERE { UNNEST(wf:filter(str(f:is_numeric.wasm), wf:call(str(f:array_of.wasm), \"star\", \"dog\", \"1\", \"2\")) AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isPresent();
            assertThat(aPossibleLiteral.get().label()).isEqualTo("1");

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isPresent();
            assertThat(aPossibleLiteral.get().label()).isEqualTo("2");

            assertThat(aResult).isExhausted();
        }
    }
}
