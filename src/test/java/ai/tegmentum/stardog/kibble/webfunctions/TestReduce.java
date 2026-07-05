package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.AbstractStardogTest;
import com.stardog.stark.Literal;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.Test;

import java.util.Optional;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

public class TestReduce extends AbstractStardogTest {

    final String queryHeader = WebFunctionVocabulary.sparqlPrefix("wf", "0.0.0") +
            " prefix f: <file:src/test/rust/target/wasm32-unknown-unknown/release/> ";

    //TODO should take either array or list of args if first arg isn't array

    @Test
    public void sumOverThreeInts() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:reduce(\"http://www.w3.org/2005/xpath-functions#numeric-add\", wf:call(str(f:array_of.wasm), 2, 2, 2)) AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isPresent();
            assertThat(Literal.intValue(aPossibleLiteral.get())).isEqualTo(6);
        }
    }

    @Test
    public void sumOverTwoInts() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:reduce(\"http://www.w3.org/2005/xpath-functions#numeric-add\", wf:call(str(f:array_of.wasm), 2, 2)) AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isPresent();
            assertThat(Literal.intValue(aPossibleLiteral.get())).isEqualTo(4);
        }
    }

    @Test
    public void tooFewArrayLiterals() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:reduce(\"http://www.w3.org/2005/xpath-functions#numeric-add\", call(str(f:array_of.wasm), 2)) AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isNotPresent();
            assertThat(aResult).isExhausted();
        }
    }

    @Test
    public void wrongTypeFirstArg() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:reduce(1, wf:call(str(f:array_of.wasm), 2, 2)) AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isNotPresent();
            assertThat(aResult).isExhausted();
        }
    }

    @Test
    public void wrongTypeSecondArg() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:reduce(\"http://www.w3.org/2005/xpath-functions#numeric-add\", 2) AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isNotPresent();
            assertThat(aResult).isExhausted();
        }
    }
}
