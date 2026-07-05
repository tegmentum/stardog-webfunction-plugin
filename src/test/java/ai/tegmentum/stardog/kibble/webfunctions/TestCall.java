package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.AbstractStardogTest;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.SelectQueryResult;
import org.assertj.core.api.AssertionsForClassTypes;
import org.assertj.core.api.AssertionsForInterfaceTypes;
import org.junit.Test;

import java.util.Optional;

import static com.complexible.stardog.plan.filter.functions.AbstractFunction.assertStringLiteral;
import static org.assertj.core.api.Assertions.assertThat;

public class TestCall extends AbstractStardogTest {

    final String queryHeader = WebFunctionVocabulary.sparqlPrefix("wf", "0.0.0") +
            " prefix f: <file:src/test/rust/target/wasm32-unknown-unknown/release/> ";

    @Test
    public void testToUpperConstant() {

        final String aQuery = queryHeader +
            " SELECT ?result WHERE { BIND(wf:call(STR(f:to_upper.wasm), \"stardog\") AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext();
            final Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            final Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            final Literal aLiteral = ((Literal)aValue);
            assertThat(aLiteral.label()).isEqualTo("STARDOG");
            assertThat(aResult).isExhausted();
        }
    }

    @Test
    public void testEmptyResult() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:call(STR(f:empty.wasm)) AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext();
            BindingSet aBindingSet = aResult.next();
            assertThat(aBindingSet).hasSize(0);
            assertThat(aResult).isExhausted();
        }
    }

    @Test
    public void testBuiltIn() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:call(\"PI\") AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext();
            final Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            final Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            final Literal aLiteral = ((Literal)aValue);
            assertThat(aLiteral.label()).isEqualTo("3.141592653589793");
            assertThat(aResult).isExhausted();
        }
    }

    @Test
    public void testToUpperVar() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { VALUEs ?str { \"stardog\" } BIND(wf:call(STR(f:to_upper.wasm), ?str) AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext();
            final Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            final Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            final Literal aLiteral = ((Literal)aValue);
            assertThat(aLiteral.label()).isEqualTo("STARDOG");
            assertThat(aResult).isExhausted();
        }
    }

    @Test
    public void testToUpperEmptyString() {

        final String aQuery = queryHeader +
                " select ?result where { bind(wf:call(str(f:to_upper.wasm), \"\") AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext();
            final Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            final Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            final Literal aLiteral = ((Literal)aValue);
            assertThat(aLiteral.label()).isEqualTo("");
            assertThat(aResult).isExhausted();
        }
    }

    @Test
    public void testEcho1x1x1() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:call(str(f:echo1x1x1.wasm), \"Hello world\" ) AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            AssertionsForInterfaceTypes.assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            AssertionsForClassTypes.assertThat(aPossibleLiteral).isPresent();
            AssertionsForClassTypes.assertThat(aPossibleLiteral.get().label()).isEqualTo("Hello world");
        }
    }

    @Test
    public void testNestedExpression() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { BIND(wf:call(str(f:to_upper.wasm), wf:call(str(f:echo1x1x1.wasm), \"Hello world\" )) AS ?result) }";

        try(final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            AssertionsForInterfaceTypes.assertThat(aResult).hasNext().withFailMessage("Should have a result");
            Optional<Literal> aPossibleLiteral = aResult.next().literal("result");
            AssertionsForClassTypes.assertThat(aPossibleLiteral).isPresent();
            AssertionsForClassTypes.assertThat(aPossibleLiteral.get().label()).isEqualTo("HELLO WORLD");
        }
    }
}