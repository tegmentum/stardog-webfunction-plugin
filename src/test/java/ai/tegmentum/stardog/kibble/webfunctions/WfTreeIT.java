package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.StardogContainer;

import com.complexible.stardog.api.Connection;
import com.complexible.stardog.api.ConnectionConfiguration;
import com.complexible.stardog.api.admin.AdminConnection;
import com.complexible.stardog.api.admin.AdminConnectionConfiguration;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.Values;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * The wf_tree portability claim on Stardog. Loads the same
 * {@code wf_tree.wasm} binary the RDF4J and Jena plugins use into a
 * Testcontainers-hosted Stardog and drives a recursive-CONSTRUCT tree walk
 * over a small graph via {@code SERVICE wfs:service}. If the resulting JSON
 * mentions every node in the graph, the v0.3.0 host-callback ABI is
 * engine-agnostic across all three targets.
 *
 * <p>Uses SERVICE (not BIND) because that path binds the outer query's
 * {@link com.complexible.stardog.plan.eval.ExecutionContext} into
 * {@link CallbackContext}, so the wasm-side {@code execute-query} import
 * has a live connection to route sub-queries against.
 *
 * <p>Skipped when Docker, the Stardog license, the shaded plugin JAR, or
 * the wasm binary is missing.
 */
public class WfTreeIT {

    private static final String DB = "test_wftree";
    private static final String LICENSE_PATH = System.getenv("STARDOG_LICENSE_PATH");
    private static final String PLUGIN_JAR = System.getProperty("wf.plugin.jar",
            "target/tegmentum-stardog-webfunction-1.0.3.jar");
    private static final String WASM_HOST_PATH = System.getProperty("wf.tree.wasm",
            System.getProperty("user.home")
                    + "/git/webfunctions/target/wasm32-wasip1/release/wf_tree.wasm");

    private static final String HAS_CHILD = "http://example.org/hasChild";
    private static final String A = "http://example.org/A";
    private static final String B = "http://example.org/B";
    private static final String C = "http://example.org/C";
    private static final String D = "http://example.org/D";
    private static final String E = "http://example.org/E";
    private static final String[] URIS = { A, B, C, D, E };

    private static StardogContainer CONTAINER;
    private static String WASM_URL;

    @BeforeClass
    public static void bootContainer() {
        assumeTrue("Docker not available", isDockerAvailable());
        assumeTrue("STARDOG_LICENSE_PATH not set",
                LICENSE_PATH != null && !LICENSE_PATH.isEmpty());
        assumeTrue("plugin JAR not built: " + PLUGIN_JAR + " (run `mvn package`)",
                new File(PLUGIN_JAR).exists());
        assumeTrue("wf_tree.wasm not built at " + WASM_HOST_PATH,
                new File(WASM_HOST_PATH).exists());

        CONTAINER = new StardogContainer()
                .withLicense(LICENSE_PATH)
                .withPluginJar(PLUGIN_JAR);
        WASM_URL = CONTAINER.withWasm(WASM_HOST_PATH, "/opt/wasm/wf_tree.wasm");
        CONTAINER.start();

        try (AdminConnection admin = AdminConnectionConfiguration.toServer(CONTAINER.getServerUrl())
                .credentials("admin", "admin").connect()) {
            if (admin.list().contains(DB)) admin.drop(DB);
            admin.newDatabase(DB).create();
        }

        // Load the 5-node graph.
        try (Connection conn = ConnectionConfiguration.to(DB)
                .server(CONTAINER.getServerUrl())
                .credentials("admin", "admin")
                .connect()) {
            conn.begin();
            conn.add().statement(Values.iri(A), Values.iri(HAS_CHILD), Values.iri(B));
            conn.add().statement(Values.iri(A), Values.iri(HAS_CHILD), Values.iri(C));
            conn.add().statement(Values.iri(B), Values.iri(HAS_CHILD), Values.iri(D));
            conn.add().statement(Values.iri(C), Values.iri(HAS_CHILD), Values.iri(E));
            conn.commit();
        }

        // Component mode must be on for the v0.3.0 WIT world.
        System.setProperty(WebFunctionConfig.PROP_ENGINE_MODE, "component");
    }

    @AfterClass
    public static void tearDown() {
        System.clearProperty(WebFunctionConfig.PROP_ENGINE_MODE);
        if (CONTAINER != null) CONTAINER.stop();
    }

    // Same vocabulary URIs the other Service tests use.
    private static final String WF_NS =
            "http://semantalytics.com/2021/03/ns/stardog/webfunction/latest/";
    private static final String WFS_NS =
            "tag:semantalytics:stardog:webfunction:0.0.0:";

    @Test
    public void treeWalkedRecursivelyOverStardog() {
        // SERVICE wfs:service {} form so WebFunctionServiceOperator runs the
        // wasm invocation and binds ExecutionContext into CallbackContext.
        // Inside the wasm, execute-query then re-enters Stardog to fetch
        // each node's children.
        final String query =
            "PREFIX wf: <" + WF_NS + ">\n" +
            "PREFIX wfs: <" + WFS_NS + ">\n" +
            "PREFIX ex: <http://example.org/>\n" +
            "SELECT ?tree WHERE {\n" +
            "  SERVICE wfs:service {\n" +
            "    [] wf:call \"" + WASM_URL + "\";\n" +
            "       wf:args (ex:A \"SELECT ?child WHERE { ?this <" + HAS_CHILD
                + "> ?child }\");\n" +
            "       wf:result ?tree\n" +
            "  }\n" +
            "}";

        try (Connection conn = ConnectionConfiguration.to(DB)
                .server(CONTAINER.getServerUrl())
                .credentials("admin", "admin")
                .connect()) {
            try (SelectQueryResult r = conn.select(query).execute()) {
                assertThat(r.hasNext()).isTrue();
                final Optional<Value> v = r.next().value("tree");
                assertThat(v).isPresent();
                assertThat(v.get()).isInstanceOf(Literal.class);
                final String tree = ((Literal) v.get()).label();
                for (String uri : URIS) {
                    assertThat(tree)
                        .as("wf_tree on Stardog should mention " + uri)
                        .contains("\"uri\":\"" + uri + "\"");
                }
                assertThat(tree).contains("\"children\":");
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
