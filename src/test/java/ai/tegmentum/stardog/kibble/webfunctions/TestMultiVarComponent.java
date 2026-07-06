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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * Direct-instantiation test that the WIT {@code binding-sets} shape
 * (list of vars + list of rows, each row a list of bindings) round-trips
 * correctly for a multi-variable, multi-row component. The Stardog
 * {@link TestServiceQuery} tests only cover single-var single-row output;
 * this test exercises the shape actually declared by the WIT world.
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
    public void evaluateReturnsMultiVarMultiRowBindingSets() throws Exception {
        final File wasm = new File(COMPONENT_PATH);
        assumeTrue(
                "multi_var_component.wasm not built: " + wasm.getAbsolutePath()
                        + " — run `cargo component build --release --package multi_var_component` "
                        + "in src/test/rust",
                wasm.exists());

        final URL url = wasm.toURI().toURL();
        try (StardogWasmInstance instance = new StardogWasmInstance(url)) {
            try (SelectQueryResult result = instance.evaluate()) {
                final List<String> vars = result.variables();
                assertThat(vars).containsExactly("label", "upper", "length");

                assertThat(result.hasNext()).isTrue();
                assertRow(result.next(), "stardog", "STARDOG", "7",
                        "http://www.w3.org/2001/XMLSchema#integer");

                assertThat(result.hasNext()).isTrue();
                assertRow(result.next(), "jena", "JENA", "4",
                        "http://www.w3.org/2001/XMLSchema#integer");

                assertThat(result.hasNext()).isFalse();
            }
        }
    }

    private static void assertRow(final BindingSet row,
                                  final String expectedLabel,
                                  final String expectedUpper,
                                  final String expectedLengthLexical,
                                  final String expectedLengthDatatype) {
        final Optional<Value> label = row.value("label");
        assertThat(label).isPresent();
        assertThat(label.get()).isInstanceOf(Literal.class);
        assertThat(((Literal) label.get()).label()).isEqualTo(expectedLabel);

        final Optional<Value> upper = row.value("upper");
        assertThat(upper).isPresent();
        assertThat(upper.get()).isInstanceOf(Literal.class);
        assertThat(((Literal) upper.get()).label()).isEqualTo(expectedUpper);

        final Optional<Value> length = row.value("length");
        assertThat(length).isPresent();
        assertThat(length.get()).isInstanceOf(Literal.class);
        final Literal lengthLit = (Literal) length.get();
        assertThat(lengthLit.label()).isEqualTo(expectedLengthLexical);
        assertThat(lengthLit.datatypeIRI().toString()).isEqualTo(expectedLengthDatatype);
    }
}
