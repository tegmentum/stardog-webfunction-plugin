package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.StardogContainer;

import com.complexible.stardog.StardogException;
import com.complexible.stardog.api.Connection;
import com.complexible.stardog.api.ConnectionConfiguration;
import com.complexible.stardog.api.admin.AdminConnection;
import com.complexible.stardog.api.admin.AdminConnectionConfiguration;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.Assume.assumeTrue;

/**
 * I3 — load-time denial when the master gate is on but the policy
 * store has no triples for the extension URL. With
 * {@code webfunctions.capability.enabled=true} and
 * {@code webfunctions.capability.unknown-extension-policy=deny}, the
 * plugin's resolver ({@link CapabilityPolicyResolver#fetchPolicyTriples})
 * throws {@link WfCapabilityError.UnknownExtension} at load time and
 * the SPARQL invocation surfaces the typed error with the extension
 * URL in the message.
 *
 * <p>Fixture: {@code example_uppercase_extension.wasm} (no callbacks,
 * no ask) — the fewest moving parts that still exercise the deny
 * branch. Every capability sub-decision downstream of the empty-
 * policy check is bypassed, so this test isolates the deny signal.
 *
 * <p>Skipped unless Docker + STARDOG_LICENSE_PATH + shaded plugin
 * JAR + fixture wasm are all present.
 */
public class CapabilityDenyIT {

    private static final String DB = "test_cap_deny";
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

        CONTAINER = new StardogContainer()
                .withLicense(LICENSE_PATH)
                .withPluginJar(PLUGIN_JAR)
                // Flip the capability master gate + set anonymous-policy
                // to PERMIT so the deny path we're exercising is the
                // unknown-extension-policy branch, not the anonymous
                // fallback (the admin user we authenticate as is a
                // valid Shiro subject; we set anon=permit for
                // defensiveness only).
                .withSystemProperty(WebFunctionConfig.PROP_CAPABILITY_ENABLED, "true")
                .withSystemProperty(WebFunctionConfig.PROP_CAPABILITY_UNKNOWN_EXTENSION_POLICY, "deny")
                .withSystemProperty(WebFunctionConfig.PROP_CAPABILITY_ANONYMOUS_POLICY, "permit");
        WASM_URL = CONTAINER.withWasm(FIXTURE_WASM,
                "/opt/wasm/example_uppercase_extension.wasm");
        CONTAINER.start();

        try (AdminConnection admin = AdminConnectionConfiguration.toServer(CONTAINER.getServerUrl())
                .credentials("admin", "admin").connect()) {
            if (!admin.list().contains(DB)) {
                admin.newDatabase(DB).create();
            }
        }
        // No INSERT into system-webfunctions-capability — the plugin's
        // CapabilityPolicyStarter auto-created the DB at kernel-install
        // time, and leaving it empty is precisely the state that
        // triggers the deny branch.
    }

    @AfterClass
    public static void tearDown() {
        if (CONTAINER != null) CONTAINER.stop();
    }

    private static final String WF_NAMESPACE =
            "http://semantalytics.com/2021/03/ns/stardog/webfunction/latest/";

    @Test
    public void loadTimeDenialSurfacesTypedError() {
        final String query = "PREFIX wf: <" + WF_NAMESPACE + ">"
                + " SELECT ?result WHERE { BIND(wf:call(str(<" + WASM_URL + ">), \"upper\", \"stardog\") AS ?result) }";

        final Throwable thrown = catchThrowable(() -> {
            try (Connection conn = ConnectionConfiguration.to(DB)
                    .server(CONTAINER.getServerUrl())
                    .credentials("admin", "admin")
                    .connect();
                 SelectQueryResult r = conn.select(query).execute()) {
                // Drain the result — Stardog's HTTP client materialises
                // errors on iteration, not on execute() alone.
                while (r.hasNext()) r.next();
            }
        });

        assertThat(thrown)
                .as("expected wf:call to fail because the extension has no policy triples")
                .isNotNull();
        // The typed error rides through Stardog's string-only error
        // surface — the human message + the extension URL both land
        // in the client-visible exception message. Assert on both.
        final String message = thrown.getMessage() == null ? "" : thrown.getMessage();
        // WfCapabilityError extends StardogException; over HTTP the
        // client sees a StardogException (or a subtype) with the
        // server-side message payload. Assert on the stable pieces:
        // the WF_CAPABILITY_UNKNOWN_EXTENSION error_code and the
        // extension URL that failed to resolve.
        assertThat(thrown).isInstanceOf(StardogException.class);
        assertThat(message)
                .as("error message should mention WF_CAPABILITY_UNKNOWN_EXTENSION")
                .contains("WF_CAPABILITY_UNKNOWN_EXTENSION");
        assertThat(message)
                .as("error message should mention the failing extension URL")
                .contains(WASM_URL);
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
