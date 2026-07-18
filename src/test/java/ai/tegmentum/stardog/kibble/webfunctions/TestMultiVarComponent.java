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
 * interface (not covered here). {@code multi_var_component} was
 * simplified to a two-arg {@code describe(label, upper)} filter that
 * concatenates its arguments — enough to exercise the multi-argument
 * call path end-to-end.
 */
public class TestMultiVarComponent {

    private static final String COMPONENT_PATH =
            "src/test/rust/target/wasm32-wasip1/release/multi_var_component.wasm";

    @Before
    public void enableComponentMode() {
        System.setProperty(WebFunctionConfig.PROP_ENGINE_MODE, "component");
    }

    @After
    public void resetComponentMode() {
        System.clearProperty(WebFunctionConfig.PROP_ENGINE_MODE);
    }

    @Test
    public void describeConcatenatesTwoLiteralArgs() throws Exception {
        final File wasm = new File(COMPONENT_PATH);
        assumeTrue(
                "multi_var_component.wasm not built: " + wasm.getAbsolutePath()
                        + " — run `cargo component build --release --package multi_var_component` "
                        + "in src/test/rust",
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
