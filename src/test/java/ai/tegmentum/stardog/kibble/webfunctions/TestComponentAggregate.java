package ai.tegmentum.stardog.kibble.webfunctions;

import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.Values;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke test for the component-mode aggregate lifecycle
 * (aggregate-step … × N then aggregate-finish). Uses the {@code sum} component.
 */
public class TestComponentAggregate {

    private static final String COMPONENT_PATH =
            "src/test/rust/target/wasm32-wasip1/release/sum_component.wasm";

    @Before
    public void enableComponentMode() {
        System.setProperty(WebFunctionConfig.PROP_ENGINE_MODE, "component");
    }

    @After
    public void resetComponentMode() {
        System.clearProperty(WebFunctionConfig.PROP_ENGINE_MODE);
    }

    @Test
    public void aggregateStepThenFinishReturnsSum() throws Exception {
        final File wasm = new File(COMPONENT_PATH);
        if (!wasm.exists()) {
            throw new org.junit.AssumptionViolatedException(
                    "sum component wasm not built: " + wasm.getAbsolutePath()
                            + " — run `cargo component build --release` in "
                            + "src/test/rust/aggregate/sum_component");
        }

        final URL url = wasm.toURI().toURL();
        try (StardogWasmInstance instance = new StardogWasmInstance(url)) {
            // Feed rows: 10*1 + 20*2 + 3*5 = 10 + 40 + 15 = 65.
            instance.compute(new Value[]{Values.literal("10")}, 1L).close();
            instance.compute(new Value[]{Values.literal("20")}, 2L).close();
            instance.compute(new Value[]{Values.literal("3")}, 5L).close();

            try (SelectQueryResult result = instance.aggregateGetValue()) {
                assertThat(result.hasNext()).isTrue();
                final BindingSet row = result.next();
                final Optional<Value> sumValue = row.value("value_0");
                assertThat(sumValue).isPresent();
                final Value value = sumValue.get();
                assertThat(value).isInstanceOf(Literal.class);
                assertThat(((Literal) value).label()).isEqualTo("65");
                assertThat(result.hasNext()).isFalse();
            }
        }
    }
}
