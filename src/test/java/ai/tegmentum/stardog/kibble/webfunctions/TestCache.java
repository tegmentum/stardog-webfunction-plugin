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
 * SPARQL-surface tests for wf:cacheClear / wf:cacheLoad / wf:cacheList.
 * Ports the pre-migration TestCache (which loaded the retired MODULE
 * to_upper.wasm) onto the checked-in example-uppercase-extension
 * component at src/test/resources/integration/.
 *
 * <p>The cache lifecycle is not sensitive to the wasm ABI — the cache
 * is keyed by URL and holds compiled instances — so the assertions
 * still just check that the URL round-trips through cacheList / that
 * cacheClear empties the cache.
 */
public class TestCache extends AbstractStardogTest {

    private static final String WASM =
            "file:src/test/resources/integration/example_uppercase_extension.wasm";

    final String queryHeader = WebFunctionVocabulary.sparqlPrefix("wf", "0.0.0");

    @Test
    public void testCacheList() {
        final String cacheClearQuery = queryHeader +
                " select ?result where { unnest(wf:cacheClear() AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(cacheClearQuery).execute()) {
            aResult.stream().count();
        }

        final String aQuery = queryHeader +
                " select ?result where { bind(wf:call(\"" + WASM + "\", \"stardog\") AS ?result) }";

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
            assertThat(aLiteral.label()).isEqualTo(WASM);
            assertThat(aResult).isExhausted();
        }
    }

    @Test
    public void testCacheClear() {

        final String aQuery = queryHeader +
                " select ?result where { bind(wf:call(\"" + WASM + "\", \"stardog\") AS ?result) }";

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
                " select ?result where { bind(wf:cacheLoad(\"" + WASM + "\") AS ?result) }";

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
            assertThat(aLiteral.label()).isEqualTo(WASM);
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
                " select ?result where { bind(wf:cacheLoad(\"" + WASM + "\") AS ?result) }";

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
            assertThat(aLiteral.label()).isEqualTo(WASM);
            assertThat(aResult).isExhausted();
        }
    }

}
