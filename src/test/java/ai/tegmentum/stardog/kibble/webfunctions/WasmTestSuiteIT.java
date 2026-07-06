package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.StardogContainer;

import com.complexible.stardog.api.Connection;
import com.complexible.stardog.api.ConnectionConfiguration;
import com.complexible.stardog.api.admin.AdminConnection;
import com.complexible.stardog.api.admin.AdminConnectionConfiguration;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * Integration test that boots a Stardog container via Testcontainers, mounts
 * the shaded plugin JAR (built by {@code mvn package} via the failsafe binding),
 * and drives a {@code wf:call} SPARQL query against the running server.
 *
 * <p>Skipped when neither Docker nor a Stardog license is available.
 */
public class WasmTestSuiteIT {

    private static final String DB = "test";
    private static final String LICENSE_PATH = System.getenv("STARDOG_LICENSE_PATH");
    private static final String PLUGIN_JAR = System.getProperty("wf.plugin.jar",
            "target/tegmentum-stardog-webfunction-1.0.3.jar");
    private static final String WASM_PATH = System.getProperty("wf.toUpper.wasm",
            "src/test/rust/target/wasm32-unknown-unknown/release/to_upper.wasm");

    private static StardogContainer CONTAINER;
    private static String WASM_URL;

    @BeforeClass
    public static void bootContainer() {
        assumeTrue("Docker not available", isDockerAvailable());
        assumeTrue("STARDOG_LICENSE_PATH not set", LICENSE_PATH != null && !LICENSE_PATH.isEmpty());
        assumeTrue("plugin JAR not built: " + PLUGIN_JAR + " (run `mvn package`)",
                new File(PLUGIN_JAR).exists());
        assumeTrue("wasm not built: " + WASM_PATH,
                new File(WASM_PATH).exists());

        CONTAINER = new StardogContainer()
                .withLicense(LICENSE_PATH)
                .withPluginJar(PLUGIN_JAR);
        WASM_URL = CONTAINER.withWasm(WASM_PATH, "/opt/wasm/to_upper.wasm");
        CONTAINER.start();

        try (AdminConnection admin = AdminConnectionConfiguration.toServer(CONTAINER.getServerUrl())
                .credentials("admin", "admin").connect()) {
            if (!admin.list().contains(DB)) {
                admin.newDatabase(DB).create();
            }
        }
    }

    @AfterClass
    public static void tearDown() {
        if (CONTAINER != null) CONTAINER.stop();
    }

    // Inline the vocabulary URI rather than depending on WebFunctionVocabulary
    // — the shade plugin's dependency-reduced-pom strips semver4j (needed by
    // that class's static init), so referencing it from the failsafe classpath
    // NoClassDefFound-s during mvn verify. See the WebFunctionVocabulary source
    // for the canonical namespace template.
    private static final String WF_NAMESPACE =
            "http://semantalytics.com/2021/03/ns/stardog/webfunction/latest/";

    @Test
    public void wfCallInsideContainerUppercases() {
        final String query = "PREFIX wf: <" + WF_NAMESPACE + ">"
                + " SELECT ?result WHERE { BIND(wf:call(str(<" + WASM_URL + ">), \"stardog\") AS ?result) }";
        try (Connection conn = ConnectionConfiguration.to(DB)
                .server(CONTAINER.getServerUrl())
                .credentials("admin", "admin")
                .connect()) {
            try (SelectQueryResult r = conn.select(query).execute()) {
                assertThat(r.hasNext()).isTrue();
                final Optional<Value> v = r.next().value("result");
                assertThat(v).isPresent();
                assertThat(v.get()).isInstanceOf(Literal.class);
                assertThat(((Literal) v.get()).label()).isEqualTo("STARDOG");
            }
        }
    }

    private static boolean isDockerAvailable() {
        try {
            org.testcontainers.DockerClientFactory.instance().client();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
