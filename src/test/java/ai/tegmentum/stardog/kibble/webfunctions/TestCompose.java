package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.AbstractStardogTest;
import com.stardog.stark.Literal;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.SelectQueryResult;
import org.assertj.core.api.Assertions;
import org.junit.Test;

import java.util.Optional;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

public class TestCompose extends AbstractStardogTest {

    final String queryHeader = WebFunctionVocabulary.sparqlPrefix("wf", "0.0.0") +
            " prefix f: <file:src/test/rust/target/wasm32-unknown-unknown/release/> ";

    @Test
    public void testCompositeOfFunctionAndPartial() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:call(wf:compose(str(f:echo1x1x1.wasm), wf:partial(str(f:concat.wasm), wf:var, \"dog\")), \"star\") AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isPresent();
            assertThat(aPossibleLiteral.get().label()).isEqualTo("stardog");
        }
    }

    @Test
    public void wrongTypeForFirstArg() {

        final String aQuery = queryHeader +
            " SELECT ?result WHERE { BIND(wf:compose(1, \"stardog\") AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {
            Assertions.assertThat(aResult.hasNext()).isTrue();
            final BindingSet aBindingSet = aResult.next();
            Assertions.assertThat(aBindingSet.size()).isZero();
            Assertions.assertThat(aResult.hasNext()).isFalse();
        }
    }

    @Test
    public void tooFewArgs() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:compose(\"stardog\") AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {
            Assertions.assertThat(aResult.hasNext()).isTrue();
            final BindingSet aBindingSet = aResult.next();
            Assertions.assertThat(aBindingSet.size()).isZero();
            Assertions.assertThat(aResult.hasNext()).isFalse();
        }
    }

    @Test
    public void compositionOfTwoBuiltinFunctions() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:call(wf:compose(\"LCASE\", \"UCASE\"), \"stardog\") AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isPresent();
            assertThat(aPossibleLiteral.get().label()).isEqualTo("stardog");
        }
    }

    @Test
    public void testCompositeOfTwoFunctions() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:call(wf:compose(str(f:echo1x1x1.wasm), str(f:to_upper.wasm)), \"Hello world\") AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isPresent();
            assertThat(aPossibleLiteral.get().label()).isEqualTo("HELLO WORLD");
        }
    }

    @Test
    public void testCompositeOfThreeFunctions() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:call(wf:compose(str(f:echo1x1x1.wasm), str(f:to_upper.wasm)), \"Hello world\") AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            assertThat(aPossibleLiteral).isPresent();
            assertThat(aPossibleLiteral.get().label()).isEqualTo("HELLO WORLD");
        }
    }


}
