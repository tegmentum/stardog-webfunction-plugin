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
 * Exercises wf:call over an http:// URL to prove the plugin's URL fetch
 * is not silently file:// only. Serves the checked-in
 * example-uppercase-extension.wasm from an in-process JDK HTTP server on
 * a random loopback port. Ports the pre-migration TestToUpperHttp
 * (which served the retired MODULE to_upper.wasm from src/test/rust/
 * target/...) onto the checked-in component fixture.
 */
public class TestToUpperHttp extends AbstractStardogTest {

    private static final String WASM_PATH =
            "src/test/resources/integration/example_uppercase_extension.wasm";

    private static HttpServer SERVER;
    private static String BASE_URL;

    @BeforeClass
    public static void startHttpServer() throws IOException {
        final File wasm = new File(WASM_PATH);
        assumeTrue("example_uppercase_extension.wasm not present at "
                        + wasm.getAbsolutePath(),
                wasm.exists());
        final byte[] wasmBytes = Files.readAllBytes(wasm.toPath());

        SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        SERVER.createContext("/example.wasm", exchange -> {
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
        final String url = BASE_URL + "/example.wasm";
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
