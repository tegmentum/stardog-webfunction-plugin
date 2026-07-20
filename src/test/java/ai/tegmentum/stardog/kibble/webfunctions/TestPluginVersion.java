package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.AbstractStardogTest;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.Test;

import java.util.Optional;

import static com.complexible.stardog.plan.filter.functions.AbstractFunction.assertStringLiteral;
import static org.assertj.core.api.Assertions.assertThat;

public class TestPluginVersion extends AbstractStardogTest {

    // wf:pluginVersion() takes no arguments and does not resolve a wasm URL,
    // so no fixture prefix is needed. Kept the SPARQL prefix declaration
    // for wf: only.
    final String queryHeader = WebFunctionVocabulary.sparqlPrefix("wf", "0.0.0");

    @Test
    public void testPluginVersion() {

        final String aQuery = queryHeader +
                " select ?result where { bind(wf:pluginVersion() AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext();
            final Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            final Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            final Literal aLiteral = ((Literal) aValue);
            assertThat((aLiteral.label())).isEqualTo("0.0.0");
            assertThat(aResult).isExhausted();
        }
    }

    @Test
    public void testVersionedName() {

        final String aQuery = queryHeader +
                " select ?result where { bind(wf:pluginVersion() AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext();
            final Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            final Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            final Literal aLiteral = ((Literal) aValue);
            assertThat((aLiteral.label())).isEqualTo("0.0.0");
            assertThat(aResult).isExhausted();
        }
    }
}