package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.AbstractStardogTest;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.Test;

import java.util.Optional;

import static com.complexible.stardog.plan.filter.functions.AbstractFunction.assertStringLiteral;
import static org.assertj.core.api.Assertions.assertThat;

public class TestAggregate extends AbstractStardogTest {

    final String queryHeader = WebFunctionVocabulary.sparqlPrefix("wf", "0.0.0") +
            " prefix f: <file:src/test/rust/target/wasm32-unknown-unknown/release/> ";

    @Test
    public void testSum() {

        final String aQuery = queryHeader +
        " select (wf:agg(str(f:sum.wasm), ?a) AS ?result)  WHERE { VALUES ?a { 1 2 3 1}} ";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext();
            final Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            final Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            final Literal aLiteral = ((Literal)aValue);
            assertThat(Literal.longValue(aLiteral)).isEqualTo(7);
            assertThat(aResult).isExhausted();
        }
    }

    @Test
    public void testMultiplicity() {

        final String aQuery = queryHeader +
                " select (wf:agg(str(f:sum.wasm), ?a) AS ?result)  WHERE { VALUES ?a { 1 1 1 1 2 3 4 } } ";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {

            assertThat(aResult).hasNext();
            final Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            final Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            final Literal aLiteral = ((Literal)aValue);
            assertThat(Literal.longValue(aLiteral)).isEqualTo(13);
            assertThat(aResult).isExhausted();
        }
    }

    @Test
    public void testSumWithGroupBy() {

        final String aQuery = queryHeader +
                " select ?group (wf:agg(str(f:sum.wasm), ?value) AS ?result)  WHERE { VALUES (?group ?value) { (1 2) (1 3) (2 4) (2 5) }} GROUP BY ?group ORDER BY ?group";

        try (final SelectQueryResult selectQueryResult = connection.select(aQuery).execute()) {

            assertThat(selectQueryResult).hasNext();
            BindingSet aResult = selectQueryResult.next();
            Value aValue = aResult.get("result");
            Value groupValue = aResult.get("group");
            assertThat(Literal.longValue((Literal)aValue)).isEqualTo(5);
            assertThat(Literal.longValue((Literal) groupValue)).isEqualTo(1);

            assertThat(selectQueryResult.hasNext()).isTrue();
            aResult = selectQueryResult.next();

            aValue = aResult.get("result");
            groupValue = aResult.get("group");
            assertThat(Literal.longValue((Literal)aValue)).isEqualTo(9);
            assertThat(Literal.longValue((Literal)groupValue)).isEqualTo(2);

            assertThat(selectQueryResult).isExhausted();
        }
    }
}
