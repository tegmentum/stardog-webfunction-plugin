package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.AbstractStardogTest;
import com.stardog.stark.Literal;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.Test;

import java.util.Optional;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

public class TestMap extends AbstractStardogTest {

    final String queryHeader = WebFunctionVocabulary.sparqlPrefix("wf", "0.0.0") +
            " prefix f: <file:src/test/rust/target/wasm32-unknown-unknown/release/> ";

    @Test
    public void testIriFunctionNoArgs() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { UNNEST(wf:map(STR(f:to_upper.wasm), wf:call(STR(f:array_of.wasm), \"star\", \"dog\")) AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isPresent();
            assertThat(aPossibleLiteral.get().label()).isEqualTo("STAR");

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isPresent();
            assertThat(aPossibleLiteral.get().label()).isEqualTo("DOG");

            assertThat(aResult).isExhausted();
        }
    }
}
