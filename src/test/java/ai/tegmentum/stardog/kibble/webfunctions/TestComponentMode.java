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
 * Direct-instantiation smoke test for the component-model call path.
 * Bypasses Stardog boot to focus on the WIT ABI round-trip.
 */
public class TestComponentMode {

    private static final String COMPONENT_PATH =
            "src/test/rust/target/wasm32-wasip1/release/to_upper_component.wasm";

    @Before
    public void enableComponentMode() {
        System.setProperty(WebFunctionConfig.PROP_ENGINE_MODE, "component");
    }

    @After
    public void resetComponentMode() {
        System.clearProperty(WebFunctionConfig.PROP_ENGINE_MODE);
    }

    @Test
    public void evaluateRoundTripsThroughWit() throws Exception {
        final File wasm = new File(COMPONENT_PATH);
        assumeBuilt(wasm);

        final URL url = wasm.toURI().toURL();
        try (StardogWasmInstance instance = new StardogWasmInstance(url)) {
            final Value input = Values.literal("stardog");
            try (SelectQueryResult result = instance.evaluate(input)) {
                assertThat(result.hasNext()).isTrue();
                final BindingSet row = result.next();
                final Optional<Value> resultValue = row.value("value_0");
                assertThat(resultValue).isPresent();
                final Value value = resultValue.get();
                assertThat(value).isInstanceOf(Literal.class);
                assertThat(((Literal) value).label()).isEqualTo("STARDOG");
                assertThat(result.hasNext()).isFalse();
            }
        }
    }

    private static void assumeBuilt(final File wasm) {
        if (!wasm.exists()) {
            throw new org.junit.AssumptionViolatedException(
                    "component wasm not built: " + wasm.getAbsolutePath()
                            + " — run `cargo component build --release` in src/test/rust/function/to_upper_component");
        }
    }
}
