package ai.tegmentum.stardog.kibble.webfunctions;

import com.stardog.stark.Values;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.net.URL;

import static org.junit.Assume.assumeTrue;

/**
 * Micro-benchmark of component-mode {@code evaluate} throughput and
 * cold-instantiation cost using the {@code example-uppercase-extension}
 * component.
 *
 * Not part of the standard test cycle. Runs only when {@code -Dbench=1} is set:
 * {@code mvn test -Dtest=TestComponentBench -Dbench=1}. Numbers are hand-rolled
 * System.nanoTime measurements after a fixed warm-up — first-order signal, not
 * a JMH-quality run.
 */
public class TestComponentBench {

    // Component wasm sourced from the shared webfunctions target. Locator:
    // EXAMPLE_UPPERCASE_WASM env override, else fall back to the well-known
    // path under ~/git/webfunctions.
    private static final String COMPONENT_WASM = resolveComponentWasm();

    private static String resolveComponentWasm() {
        final String env = System.getenv("EXAMPLE_UPPERCASE_WASM");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return System.getProperty("user.home")
                + "/git/webfunctions/target/wasm32-wasip2/release/example_uppercase_extension.wasm";
    }

    private static final int WARMUP = 500;
    private static final int MEASURED = 5_000;
    private static final int INSTANTIATIONS = 50;

    @Before
    public void gate() {
        assumeTrue("bench is off; enable with -Dbench=1",
                "1".equals(System.getProperty("bench")));
        assumeTrue("example-uppercase-extension wasm not built (cd ~/git/webfunctions "
                        + "&& cargo component build --release -p example-uppercase-extension "
                        + "--target wasm32-wasip2), or set EXAMPLE_UPPERCASE_WASM to the built path",
                new File(COMPONENT_WASM).exists());
    }

    @Test
    public void benchEvaluate() throws Exception {
        final long componentNs = timeEvaluate(COMPONENT_WASM);
        report("evaluate", "component", componentNs);
    }

    @Test
    public void benchInstantiation() throws Exception {
        final long componentNs = timeInstantiation(COMPONENT_WASM);
        report("instantiate", "component", componentNs);
    }

    private static long timeEvaluate(final String wasmPath) throws Exception {
        final URL url = new File(wasmPath).toURI().toURL();

        try (StardogWasmInstance instance = new StardogWasmInstance(url)) {
            // Warm-up.
            for (int i = 0; i < WARMUP; i++) {
                try (SelectQueryResult r = instance.evaluate(Values.literal("stardog"))) {
                    r.hasNext();
                }
            }

            final long start = System.nanoTime();
            for (int i = 0; i < MEASURED; i++) {
                try (SelectQueryResult r = instance.evaluate(Values.literal("stardog"))) {
                    r.hasNext();
                }
            }
            return (System.nanoTime() - start) / MEASURED;
        }
    }

    private static long timeInstantiation(final String wasmPath) throws Exception {
        final URL url = new File(wasmPath).toURI().toURL();

        // Warm the component byte cache and JIT.
        for (int i = 0; i < 3; i++) {
            new StardogWasmInstance(url).close();
        }

        final long start = System.nanoTime();
        for (int i = 0; i < INSTANTIATIONS; i++) {
            new StardogWasmInstance(url).close();
        }
        return (System.nanoTime() - start) / INSTANTIATIONS;
    }

    private static void report(final String op, final String mode, final long nsPerCall) {
        System.out.printf("%s %s: %,10d ns/op (%,10.0f ops/s)%n",
                op, mode, nsPerCall, 1e9 / nsPerCall);
    }
}
