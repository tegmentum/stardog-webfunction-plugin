package ai.tegmentum.stardog.kibble.webfunctions;

import com.stardog.stark.Literal;
import com.stardog.stark.Value;
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
 * Stardog counterpart of the RDF4J / Jena {@code TestCallbackDepth}. Loads
 * the shared {@code debug_callback_depth.wasm} — a component whose
 * {@code evaluate} export just returns the value of the
 * {@code host::callback-depth()} import — and asserts it comes back as 0
 * when invoked at the top level.
 *
 * <p>Bypasses Stardog boot in favour of direct {@link StardogWasmInstance}
 * construction: the intent is to prove the linker binding is engine-agnostic,
 * which lives at exactly this level of the stack.
 */
public class TestCallbackDepth {

    private static final String WASM = System.getProperty("wf.debug.callback.depth.wasm",
            System.getProperty("user.home")
                    + "/git/tegmentum-webfunctions/target/wasm32-wasip1/release/debug_callback_depth.wasm");

    @Before
    public void enableComponentMode() {
        System.setProperty(WebFunctionConfig.PROP_ENGINE_MODE, "component");
    }

    @After
    public void resetComponentMode() {
        System.clearProperty(WebFunctionConfig.PROP_ENGINE_MODE);
    }

    @Test
    public void depthIsZeroAtTopLevel() throws Exception {
        final File wasm = new File(WASM);
        if (!wasm.exists()) {
            throw new org.junit.AssumptionViolatedException(
                    "debug_callback_depth.wasm not built at " + wasm.getAbsolutePath()
                            + " — build via `cargo component build --release` in "
                            + "tegmentum-webfunctions/crates/debug_callback_depth");
        }

        // The context binds ordinarily in Call.evaluate; for this direct
        // instance-level test we bind manually so the import returns a
        // realistic value.
        final CallbackContext ctx = CallbackContext.bind();
        try {
            final URL url = wasm.toURI().toURL();
            try (StardogWasmInstance instance = new StardogWasmInstance(url)) {
                try (SelectQueryResult result = instance.evaluate()) {
                    assertThat(result.hasNext()).isTrue();
                    final BindingSet row = result.next();
                    final Optional<Value> depth = row.value("depth");
                    assertThat(depth).isPresent();
                    assertThat(depth.get()).isInstanceOf(Literal.class);
                    assertThat(((Literal) depth.get()).label()).isEqualTo("0");
                }
            }
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }
}
