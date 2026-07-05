package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.AbstractStardogTest;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.Test;

import java.util.Optional;

import static com.complexible.stardog.plan.filter.functions.AbstractFunction.assertStringLiteral;
import static org.assertj.core.api.Assertions.assertThat;

public class TestMappingDictionaryAdd extends AbstractStardogTest {

    @Test
    public void mappingDictionaryAdd() {
        final String cacheClearQuery = WebFunctionVocabulary.sparqlPrefix("wf", "0.0.0") +
                " select ?result where { unnest(wf:cacheClear() AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(cacheClearQuery).execute()) {
            aResult.stream().count();
        }

        final String aQuery = WebFunctionVocabulary.sparqlPrefix("wf", "0.0.0") +
                "prefix f: <file:src/test/rust/target/wasm32-unknown-unknown/release/> " +
                " select ?result where { unnest(wf:call(str(f:mapping_dictionary_add.wasm), \"stardog\") AS ?result) }";

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
