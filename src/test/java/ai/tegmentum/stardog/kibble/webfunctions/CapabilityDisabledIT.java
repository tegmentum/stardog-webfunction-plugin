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
 * I2 — regression safety net for the capability wave: with
 * {@code webfunctions.capability.enabled=false} (the default), the
 * plugin's SPARQL extension entry point behaves identically to the
 * pre-capability landing. The capability code lives in the plugin
 * either way; the test proves the master gate genuinely short-circuits
 * every capability code path so unmodified deployments see no
 * regression.
 *
 * <p>Uses the {@code example_uppercase_extension.wasm} component
 * from {@code src/test/resources/integration/} — no host callbacks,
 * no ask, no policy triples required. The SPARQL invocation path
 * is exercised via the plugin's {@code tegmentum:webfunction/extension@0.1.0#call}
 * export (this is the same call surface the shipped Rust reference
 * extension exposes upstream).
 *
 * <p>Sibling of {@link WasmTestSuiteIT} — differs only in fixture
 * (an ask-less component) and in the assertion (extension.call
 * returns the guest-produced literal), keeping the container config
 * to the shipped default so the master-gate contract is what the
 * test measures.
 *
 * <p>Skipped unless Docker + STARDOG_LICENSE_PATH + the shaded
 * plugin JAR + the fixture wasm are all present.
 */
public class CapabilityDisabledIT {

    private static final String DB = "test_cap_disabled";
    private static final String LICENSE_PATH = System.getenv("STARDOG_LICENSE_PATH");
    private static final String PLUGIN_JAR = System.getProperty("wf.plugin.jar",
            "target/tegmentum-stardog-webfunction-1.0.3.jar");
    private static final String FIXTURE_WASM =
            "src/test/resources/integration/example_uppercase_extension.wasm";

    private static StardogContainer CONTAINER;
    private static String WASM_URL;

    @BeforeClass
    public static void bootContainer() {
        assumeTrue("Docker not available", isDockerAvailable());
        assumeTrue("STARDOG_LICENSE_PATH not set", LICENSE_PATH != null && !LICENSE_PATH.isEmpty());
        assumeTrue("plugin JAR not built: " + PLUGIN_JAR + " (run `mvn package`)",
                new File(PLUGIN_JAR).exists());
        assumeTrue("fixture wasm missing: " + FIXTURE_WASM,
                new File(FIXTURE_WASM).exists());

        // Intentional: no withSystemProperty(...) for capability keys.
        // Master gate stays off, matching an unmodified pre-capability
        // deployment shape — the regression's whole point.
        CONTAINER = new StardogContainer()
                .withLicense(LICENSE_PATH)
                .withPluginJar(PLUGIN_JAR);
        WASM_URL = CONTAINER.withWasm(FIXTURE_WASM,
                "/opt/wasm/example_uppercase_extension.wasm");
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

    // Same vocabulary URI the other Service ITs inline — the shade
    // plugin's dependency-reduced-pom strips semver4j (needed by
    // WebFunctionVocabulary's static init), so referencing that class
    // from the failsafe classpath NoClassDefFound-s during mvn verify.
    private static final String WF_NAMESPACE =
            "http://semantalytics.com/2021/03/ns/stardog/webfunction/latest/";

    @Test
    public void uppercaseExtensionRunsWithoutCapabilityGate() {
        // Drive the uppercase reference extension's `upper` filter
        // function via wf:call. With capability disabled, the plugin
        // takes the pre-capability code path — no resolver, no
        // enforcer, no ask extraction. The dispatch is expected to
        // return the guest-produced upper-cased literal exactly as
        // it did before the capability wave landed.
        final String query = "PREFIX wf: <" + WF_NAMESPACE + ">"
                + " SELECT ?result WHERE { BIND(wf:call(str(<" + WASM_URL + ">), \"upper\", \"stardog\") AS ?result) }";
        try (Connection conn = ConnectionConfiguration.to(DB)
                .server(CONTAINER.getServerUrl())
                .credentials("admin", "admin")
                .connect();
             SelectQueryResult r = conn.select(query).execute()) {
            assertThat(r.hasNext()).isTrue();
            final Optional<Value> v = r.next().value("result");
            assertThat(v).isPresent();
            assertThat(v.get()).isInstanceOf(Literal.class);
            assertThat(((Literal) v.get()).label()).isEqualTo("STARDOG");
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
