package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.AbstractStardogTest;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.Test;

import java.util.Optional;

import static com.complexible.stardog.plan.filter.functions.AbstractFunction.assertStringLiteral;
import static org.assertj.core.api.Assertions.assertThat;

public class TestCache extends AbstractStardogTest {

    final String queryHeader = WebFunctionVocabulary.sparqlPrefix("wf", "0.0.0") +
            " prefix f: <file:src/test/rust/target/wasm32-unknown-unknown/release/> ";

    @Test
    public void testCacheList() {
        final String cacheClearQuery = queryHeader +
                " select ?result where { unnest(wf:cacheClear() AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(cacheClearQuery).execute()) {
            aResult.stream().count();
        }

        final String aQuery = queryHeader +
                " select ?result where { bind(wf:call(str(f:to_upper.wasm), \"stardog\") AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {
            aResult.stream().count();
        }

        final String listCacheQuery = queryHeader +
                " select ?result where { unnest(wf:cacheList() AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(listCacheQuery).execute()) {

            assertThat(aResult).hasNext();
            final Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            final Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            final Literal aLiteral = ((Literal)aValue);
            assertThat(aLiteral.label()).isEqualTo("file:src/test/rust/target/wasm32-unknown-unknown/release/to_upper.wasm");
            assertThat(aResult).isExhausted();
        }
    }

    @Test
    public void testCacheClear() {

        final String aQuery = queryHeader +
                " select ?result where { bind(wf:call(f:toUpper, \"stardog\") AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {
            aResult.stream().count();
        }

        final String listCacheQuery = queryHeader +
                " select ?result where { unnest(wf:cacheClear() AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(listCacheQuery).execute()) {

            assertThat(aResult).hasNext();
            final Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isEmpty();
        }
    }

    @Test
    public void testCacheLoadFromUrl() {
        final String cacheClearQuery = queryHeader +
                " select ?result where { unnest(wf:cacheClear() AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(cacheClearQuery).execute()) {
            aResult.stream().count();
        }

        final String aQuery = queryHeader +
                " select ?result where { bind(wf:cacheLoad(str(f:to_upper.wasm)) AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {
            aResult.stream().count();
        }

        final String listCacheQuery = WebFunctionVocabulary.sparqlPrefix("wf", "0.0.0") +
                " select ?result where { unnest(wf:cacheList() AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(listCacheQuery).execute()) {

            assertThat(aResult).hasNext();
            final Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            final Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            final Literal aLiteral = ((Literal)aValue);
            assertThat(aLiteral.label()).isEqualTo("file:src/test/rust/target/wasm32-unknown-unknown/release/to_upper.wasm");
            assertThat(aResult).isExhausted();
        }
    }

    @Test
    public void testCacheLoadFromLiteral() {
        final String cacheClearQuery = queryHeader +
                " select ?result where { unnest(wf:cacheClear() AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(cacheClearQuery).execute()) {
            aResult.stream().count();
        }

        final String aQuery = queryHeader +
                " select ?result where { bind(wf:cacheLoad(str(f:to_upper.wasm)) AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {
            aResult.stream().count();
        }

        final String listCacheQuery = queryHeader +
                " select ?result where { unnest(wf:cacheList() AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(listCacheQuery).execute()) {

            assertThat(aResult).hasNext();
            final Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            final Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            final Literal aLiteral = ((Literal)aValue);
            assertThat(aLiteral.label()).isEqualTo("file:src/test/rust/target/wasm32-unknown-unknown/release/to_upper.wasm");
            assertThat(aResult).isExhausted();
        }
    }

}
