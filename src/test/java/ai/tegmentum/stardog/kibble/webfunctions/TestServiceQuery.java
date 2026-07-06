package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.protocols.http.client.BaseHttpClient;
import ai.tegmentum.stardog.kibble.AbstractStardogTest;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Optional;

import static com.complexible.stardog.plan.filter.functions.AbstractFunction.assertStringLiteral;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

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

    /**
     * Multi-var multi-row: exercises the WIT {@code binding-sets} shape end-to-end
     * via SERVICE. The {@code multi_var_component.wasm} component emits vars
     * {@code (label, upper, length)} and two rows; the SERVICE operator must map
     * output positions to the requested {@code wf:result} vars using the
     * component-declared var names, not the module-mode {@code value_%d} pattern.
     */
    @Test
    public void testServiceMultiVarMultiRowComponent() {
        final File wasm = new File("src/test/rust/target/wasm32-wasip1/release/multi_var_component.wasm");
        assumeTrue("multi_var_component.wasm not built at " + wasm.getAbsolutePath(), wasm.exists());

        final String prevMode = System.getProperty(WebFunctionConfig.PROP_ENGINE_MODE);
        System.setProperty(WebFunctionConfig.PROP_ENGINE_MODE, "component");
        try {
            final String aQuery = queryHeader +
                    " select ?label ?upper ?length where { SERVICE wfs:service {" +
                    "  [] wf:call \"" + wasm.toURI() + "\"; " +
                    "     wf:result (?label ?upper ?length) } }";

            try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {
                assertThat(aResult).hasNext();
                final BindingSet row1 = aResult.next();
                assertThat(((Literal) row1.get("label")).label()).isEqualTo("stardog");
                assertThat(((Literal) row1.get("upper")).label()).isEqualTo("STARDOG");
                assertThat(((Literal) row1.get("length")).label()).isEqualTo("7");
                assertThat(((Literal) row1.get("length")).datatypeIRI().toString())
                        .isEqualTo("http://www.w3.org/2001/XMLSchema#integer");

                assertThat(aResult).hasNext();
                final BindingSet row2 = aResult.next();
                assertThat(((Literal) row2.get("label")).label()).isEqualTo("jena");
                assertThat(((Literal) row2.get("upper")).label()).isEqualTo("JENA");
                assertThat(((Literal) row2.get("length")).label()).isEqualTo("4");

                assertThat(aResult).isExhausted();
            }
        } finally {
            if (prevMode == null) {
                System.clearProperty(WebFunctionConfig.PROP_ENGINE_MODE);
            } else {
                System.setProperty(WebFunctionConfig.PROP_ENGINE_MODE, prevMode);
            }
        }
    }
}
