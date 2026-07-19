package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.protocols.http.client.BaseHttpClient;
import ai.tegmentum.stardog.kibble.AbstractStardogTest;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.Test;

import java.util.Optional;

import static com.complexible.stardog.plan.filter.functions.AbstractFunction.assertStringLiteral;
import static org.assertj.core.api.Assertions.assertThat;

public class TestServiceQuery extends AbstractStardogTest {

    final String queryHeader = WebFunctionVocabulary.sparqlPrefix("wf", "latest") +
            " prefix f: <file:src/test/rust/target/wasm32-unknown-unknown/release/> " +
            " prefix wfs: <tag:semantalytics:stardog:webfunction:0.0.0:> ";

    @Test
    public void testServiceQuery() {

        final String aQuery = queryHeader +
                " select ?result where { SERVICE wfs:service {" +
                "  [] wf:call \"file:src/test/rust/target/wasm32-unknown-unknown/release/to_upper.wasm\"; " +
                "     wf:args \"stardog\";" +
                "     wf:result ?result } }";

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
                " select ?result where { SERVICE wfs:service {" +
                "  [] wf:call \"file:src/test/rust/target/wasm32-unknown-unknown/release/empty.wasm\"; " +
                "     wf:result ?result } }";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).isExhausted();
        }
    }

    @Test
    public void testServiceQueryWrapingBNode() {

        final String aQuery = queryHeader +
                " select ?result where { SERVICE wfs:service {" +
                "    [ wf:call \"file:src/test/rust/target/wasm32-unknown-unknown/release/to_upper.wasm\"; " +
                "      wf:args \"stardog\";" +
                "      wf:result ?result " +
                "    ]" +
                "  }" +
                "}";

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
    public void testServiceQueryFunctionNameFromBind() {

        final String aQuery = queryHeader +
                " select ?result where { " +
                " BIND(str(f:to_upper.wasm) as ?func) " +
                " SERVICE wfs:service {" +
                "  [] wf:call ?func; " +
                "     wf:args \"stardog\";" +
                "     wf:result ?result } }";

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

    @Test(expected = BaseHttpClient.HttpClientException.class)
    public void missingCallPredicateShouldFail() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { SERVICE wfs:service {" +
                " [ wf:args ?args; wf:result ?result ] } VALUES ?args {\"stardog\"}}";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext();
            Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            Literal aLiteral = ((Literal)aValue);
            assertThat(aLiteral.label()).isEqualTo("STARDOG");

            assertThat(aResult).isExhausted();
        }
    }

    @Test(expected = BaseHttpClient.HttpClientException.class)
    public void missingResultsPredicateShouldFail() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { SERVICE wfs:service {" +
                "  [ wf:call ?func; wf:args ?args; ] } VALUES ?args {\"stardog\"}}";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {

        }
    }

    @Test(expected = BaseHttpClient.HttpClientException.class)
    public void constantResultsShouldFail() {

        final String aQuery = queryHeader +
                " SELECT ?result WHERE { SERVICE wfs:service {" +
                "  [ wf:call ?func; wf:args ?args; wf:results \"results\" ] } VALUES ?args {\"stardog\"}}";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext();
            Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            Literal aLiteral = ((Literal)aValue);
            assertThat(aLiteral.label()).isEqualTo("STARDOG");

            assertThat(aResult).isExhausted();
        }
    }


    @Test
    public void testServiceTwoArgs() {

        final String aQuery = queryHeader +
                " select ?result where { SERVICE wfs:service {" +
                "  [] wf:call \"file:src/test/rust/target/wasm32-unknown-unknown/release/concat.wasm\"; " +
                "     wf:args (\"star\" \"dog\"); " +
                "     wf:result ?result } }";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext();
            Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            Literal aLiteral = ((Literal)aValue);
            assertThat(aLiteral.label()).isEqualTo("stardog");

            assertThat(aResult).isExhausted();
        }
    }

    @Test
    public void testServiceOneVarInput() {

        final String aQuery = queryHeader +
                " select ?result where { SERVICE wfs:service {" +
                "  [] wf:call \"file:src/test/rust/target/wasm32-unknown-unknown/release/to_upper.wasm\"; " +
                "     wf:args ?args;" +
                "     wf:result ?result } values ?args {\"stardog\"}}";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext();
            Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            Literal aLiteral = ((Literal)aValue);
            assertThat(aLiteral.label()).isEqualTo("STARDOG");

            assertThat(aResult).isExhausted();
        }
    }

    @Test
    public void testServiceMappingDictionarySet() {

        final String aQuery = queryHeader +
                " select ?result where { SERVICE wfs:service {" +
                "  [] wf:call \"file:src/test/rust/target/wasm32-unknown-unknown/release/array_of.wasm\"; " +
                "     wf:args (\"star\" \"dog\");" +
                "     wf:result ?result1 . " +
                "  } " +
                " UNNEST(?result1 as ?result) " +
                " }";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext();
            Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            Literal aLiteral = ((Literal)aValue);
            assertThat(aLiteral.label()).isEqualTo("star");

            assertThat(aResult).hasNext();
            aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            aLiteral = ((Literal)aValue);
            assertThat(aLiteral.label()).isEqualTo("dog");

            assertThat(aResult).isExhausted();
        }
    }

    // Multi-var multi-row SERVICE test retired alongside the
    // stardog-plugin-local multi_var_component crate. The base
    // sparql-extension filter interface returns a single term; the
    // multi-row / multi-var shape belongs to the property-function
    // interface. The migrated example-multi-var-filter (webfunctions)
    // preserves only the 2-arg describe filter surface — the 3-var
    // 2-row shape this test asserted no longer has a replacement
    // fixture. See webfunctions/crates/example-power-set-property for
    // multi-row property-function coverage.
}
