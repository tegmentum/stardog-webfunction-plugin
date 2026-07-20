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
 * SPARQL-surface smoke test for wf:call against a component-mode wasm.
 * Ports the pre-migration TestToUpper (which loaded the retired MODULE
 * to_upper.wasm) onto the checked-in example-uppercase-extension
 * component at src/test/resources/integration/.
 *
 * <p>StardogWasmInstance.componentExtensionCall auto-discovers the
 * function name from register() and dispatches through
 * extension.call — so the SPARQL surface {@code wf:call(str(url), arg)}
 * still just says "run the extension at this URL against these args".
 * The two testCall* variants that exercised wf:compose over to_upper
 * were dropped alongside TestCompose (retired flat-ABI fixtures);
 * this test now covers only the direct wf:call surface.
 */
public class TestToUpper extends AbstractStardogTest {

    private static final String WASM =
            "file:src/test/resources/integration/example_uppercase_extension.wasm";

    final String queryHeader = WebFunctionVocabulary.sparqlPrefix("wf", "0.0.0");

    @Test
    public void testToUpper() {

        final String aQuery = queryHeader +
                " select ?result where { bind(wf:call(\"" + WASM + "\", \"stardog\") AS ?result) }";

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
    public void testLiteralUrl() {

        final String aQuery = queryHeader +
                " select ?result where { bind(wf:call(\"" + WASM + "\", \"stardog\") AS ?result) }";

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
}
