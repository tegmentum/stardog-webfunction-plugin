package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.AbstractStardogTest;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.Test;

import java.util.Optional;

import static com.complexible.stardog.plan.filter.functions.AbstractFunction.assertStringLiteral;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPARQL-surface tests for wf:call — covers both the wasm-URL dispatch
 * (against the checked-in example-uppercase-extension component) and
 * the built-in-name dispatch (PI). Ports the pre-migration TestCall
 * with the flat-ABI subtests (empty.wasm, echo1x1x1.wasm) dropped;
 * see the module-cleanup batch for context.
 */
public class TestCall extends AbstractStardogTest {

    private static final String WASM =
            "file:src/test/resources/integration/example_uppercase_extension.wasm";

    final String queryHeader = WebFunctionVocabulary.sparqlPrefix("wf", "0.0.0");

    @Test
    public void testToUpperConstant() {

        final String aQuery = queryHeader +
            " SELECT ?result WHERE { BIND(wf:call(\"" + WASM + "\", \"stardog\") AS ?result) }";

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
                " SELECT ?result WHERE { VALUEs ?str { \"stardog\" } BIND(wf:call(\"" + WASM + "\", ?str) AS ?result) }";

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
                " select ?result where { bind(wf:call(\"" + WASM + "\", \"\") AS ?result) }";

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
}
