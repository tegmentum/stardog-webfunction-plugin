package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.AbstractStardogTest;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.query.SelectQueryResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Optional;

import static com.complexible.stardog.plan.filter.functions.AbstractFunction.assertStringLiteral;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * Exercises wf:call over an http:// URL to prove the plugin's URL fetch is
 * not silently file:// only. Serves the {@code to_upper.wasm} module from an
 * in-process JDK HTTP server on a random loopback port.
 */
public class TestToUpperHttp extends AbstractStardogTest {

    private static final String TO_UPPER_WASM =
            "src/test/rust/target/wasm32-unknown-unknown/release/to_upper.wasm";

    private static HttpServer SERVER;
    private static String BASE_URL;

    @BeforeClass
    public static void startHttpServer() throws IOException {
        final File wasm = new File(TO_UPPER_WASM);
        assumeTrue("to_upper.wasm not built at " + wasm.getAbsolutePath()
                        + " -- run `cargo make build` in src/test/rust",
                wasm.exists());
        final byte[] wasmBytes = Files.readAllBytes(wasm.toPath());

        SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        SERVER.createContext("/to_upper.wasm", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/wasm");
            exchange.sendResponseHeaders(200, wasmBytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(wasmBytes);
            }
        });
        SERVER.start();
        BASE_URL = "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    @AfterClass
    public static void stopHttpServer() {
        if (SERVER != null) {
            SERVER.stop(0);
            SERVER = null;
        }
    }

    @Test
    public void testToUpperOverHttp() {
        final String url = BASE_URL + "/to_upper.wasm";
        final String aQuery = WebFunctionVocabulary.sparqlPrefix("wf", "0.0.0") +
                " select ?result where { bind(wf:call(\"" + url + "\", \"stardog\") AS ?result) }";

        try (final SelectQueryResult aResult = connection.select(aQuery).execute()) {
            assertThat(aResult).hasNext();
            final Optional<Value> aPossibleValue = aResult.next().value("result");
            assertThat(aPossibleValue).isPresent();
            final Value aValue = aPossibleValue.get();
            assertThat(assertStringLiteral(aValue));
            final Literal aLiteral = ((Literal) aValue);
            assertThat(aLiteral.label()).isEqualTo("STARDOG");
            assertThat(aResult).isExhausted();
        }
    }
}
