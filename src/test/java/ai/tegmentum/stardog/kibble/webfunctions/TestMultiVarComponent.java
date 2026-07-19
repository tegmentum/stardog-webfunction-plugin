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
 * Direct-instantiation test that a multi-argument filter component
 * round-trips through the sparql-extension world's
 * {@code extension.call(name, args)} dispatch.
 *
 * <p>Pre-migration this test drove the flat {@code evaluate}
 * export that returned multi-row multi-var binding-sets. The base
 * {@code sparql-extension} filter interface returns a single {@code
 * term}; the multi-row test surface belongs to the property-function
 * interface (not covered here). The reference filter was simplified
 * to a two-arg {@code describe(label, upper)} filter that concatenates
 * its arguments — enough to exercise the multi-argument call path
 * end-to-end.
 *
 * <p>Loads {@code example_multi_var_filter.wasm} from the shared
 * webfunctions target — retired the stardog-plugin-local
 * {@code multi_var_component} crate so all four engine bindings load
 * one wasm.
 *
 * <p>Locator: {@code EXAMPLE_MULTI_VAR_FILTER_WASM} env override, else
 * fall back to
 * {@code $HOME/git/webfunctions/target/wasm32-wasip2/release/example_multi_var_filter.wasm}.
 * Skips cleanly via {@code assumeTrue} if the wasm has not been built.
 */
public class TestMultiVarComponent {

    private static final String COMPONENT_PATH = resolveWasmPath();

    private static String resolveWasmPath() {
        final String env = System.getenv("EXAMPLE_MULTI_VAR_FILTER_WASM");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return System.getProperty("user.home")
                + "/git/webfunctions/target/wasm32-wasip2/release/example_multi_var_filter.wasm";
    }

    @Test
    public void describeConcatenatesTwoLiteralArgs() throws Exception {
        final File wasm = new File(COMPONENT_PATH);
        assumeTrue(
                "example-multi-var-filter wasm not built: " + wasm.getAbsolutePath()
                        + " — run `cargo component build --release -p example-multi-var-filter "
                        + "--target wasm32-wasip2` in ~/git/webfunctions, or set "
                        + "EXAMPLE_MULTI_VAR_FILTER_WASM to the built component path",
                wasm.exists());

        final URL url = wasm.toURI().toURL();
        try (StardogWasmInstance instance = new StardogWasmInstance(url)) {
            try (SelectQueryResult result =
                     instance.evaluate(Values.literal("stardog"), Values.literal("STARDOG"))) {
                assertThat(result.hasNext()).isTrue();
                final BindingSet row = result.next();
                final Optional<Value> resultValue = row.value("value_0");
                assertThat(resultValue).isPresent();
                assertThat(resultValue.get()).isInstanceOf(Literal.class);
                assertThat(((Literal) resultValue.get()).label()).isEqualTo("stardog=STARDOG");
                assertThat(result.hasNext()).isFalse();
            }
        }
    }
}
