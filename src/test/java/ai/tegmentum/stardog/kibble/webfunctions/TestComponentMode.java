package ai.tegmentum.stardog.kibble.webfunctions;

import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.Values;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * Direct-instantiation smoke test for the component-model call path.
 * Bypasses Stardog boot to focus on the WIT ABI round-trip.
 *
 * <p>Loads {@code example_uppercase_extension.wasm} from the shared
 * webfunctions target — retired the stardog-plugin-local
 * {@code to_upper_component} crate so all four engine bindings load one wasm.
 *
 * <p>Locator: {@code EXAMPLE_UPPERCASE_WASM} env override, else fall back
 * to {@code $HOME/git/webfunctions/target/wasm32-wasip2/release/example_uppercase_extension.wasm}.
 * Skips cleanly via {@code assumeTrue} if the wasm has not been built.
 */
public class TestComponentMode {

    private static final String COMPONENT_PATH = resolveWasmPath();

    private static String resolveWasmPath() {
        final String env = System.getenv("EXAMPLE_UPPERCASE_WASM");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return System.getProperty("user.home")
                + "/git/webfunctions/target/wasm32-wasip2/release/example_uppercase_extension.wasm";
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
        assumeTrue(
                "example-uppercase-extension wasm not built: " + wasm.getAbsolutePath()
                        + " — run `cargo component build --release -p example-uppercase-extension "
                        + "--target wasm32-wasip2` in ~/git/webfunctions, or set "
                        + "EXAMPLE_UPPERCASE_WASM to the built component path",
                wasm.exists());
    }
}
