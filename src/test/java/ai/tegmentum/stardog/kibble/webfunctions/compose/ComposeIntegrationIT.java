package ai.tegmentum.stardog.kibble.webfunctions.compose;

import ai.tegmentum.stardog.kibble.StardogContainer;
import ai.tegmentum.stardog.kibble.webfunctions.WebFunctionConfig;

import com.complexible.stardog.api.admin.AdminConnection;
import com.complexible.stardog.api.admin.AdminConnectionConfiguration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end integration tests for the compose orchestrator wave.
 *
 * <p>Each sub-phase drives a real {@link ComposeOrchestratorClient} —
 * built against the classpath-embedded orchestrator wasm at
 * {@code src/main/resources/webfunctions/compose_orchestrator.wasm} —
 * from the test JVM and (for the RDF / extension-load sub-phases)
 * cross-checks state against a running Stardog container the failsafe
 * plugin brings up alongside every other {@code *IT} class.
 *
 * <p>Why compose runs in the test JVM rather than inside the container:
 * the plugin's {@link ComposeAdmin} exposes no HTTP surface — the C10
 * admin entry point is Java-callable only (the SPARQL SERVICE trigger
 * throws with an actionable message; see
 * {@link WebFunctionComposeService}). Driving compose over a Stardog
 * connection would require the SERVICE parser Task 278 hasn't landed
 * yet. Running the orchestrator wasm in-process on the test JVM is
 * consistent with {@code TestComponentMode}'s direct-instantiation shape
 * and lets the ITs exercise the whole {@code plan#deserialize →
 * emit#compose → get-artifact} chain against the real bundled wasm.
 *
 * <p>Container role: the container is the RDF landing surface for
 * {@code sys:compose/rdf#plan-to-turtle} output (IE2) and the extension
 * host for the composed CID (IE3, IE4). Compose bytes flow test-JVM →
 * container via {@code CONTAINER.copyFileToContainer}.
 *
 * <p>Skipped unless Docker + STARDOG_LICENSE_PATH + the shaded plugin
 * JAR + the fixture input wasm are all present. The bundled orchestrator
 * wasm is a plugin resource; a checkout-fresh source tree carries it in
 * {@code src/main/resources/webfunctions/compose_orchestrator.wasm} —
 * see {@code chore(compose): bundle compose orchestrator wasm as plugin
 * resource} in the C-wave commit trail.
 */
public class ComposeIntegrationIT {

    private static final String DB = "test_compose_it";
    private static final String LICENSE_PATH = System.getenv("STARDOG_LICENSE_PATH");
    private static final String PLUGIN_JAR = System.getProperty("wf.plugin.jar",
            "target/tegmentum-stardog-webfunction-1.0.3.jar");

    /**
     * Fixture input wasm — a real component that gets staged into the
     * orchestrator's blob store as the plan's root component. Reused
     * across sub-phases; each test computes its digest fresh so a
     * fixture swap doesn't require a helper update.
     */
    private static final String FIXTURE_WASM =
            "src/test/resources/integration/example_uppercase_extension.wasm";

    /** Classpath resource carrying the compose orchestrator component. */
    private static final String ORCHESTRATOR_RESOURCE =
            ComposeOrchestratorLoader.CLASSPATH_RESOURCE;

    static StardogContainer CONTAINER;
    static String SERVER_URL;

    static byte[] FIXTURE_WASM_BYTES;
    static byte[] FIXTURE_WASM_DIGEST;

    /**
     * Per-test-class TemporaryFolder wrapping the compose orchestrator's
     * on-disk state (extracted orchestrator wasm + preopen directories +
     * artifact store). {@link Rule} isn't allowed on static fields, so
     * we manage it in {@link #bootContainer()} / {@link #tearDown()}.
     */
    static Path composeRoot;

    static ComposeOrchestratorLoader LOADER;
    static ComposeOrchestratorInstance ORCHESTRATOR;
    static ComposeOrchestratorClient CLIENT;
    static ComposedArtifactStore ARTIFACT_STORE;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @BeforeClass
    public static void bootContainer() throws Exception {
        assumeTrue("Docker not available", isDockerAvailable());
        assumeTrue("STARDOG_LICENSE_PATH not set",
                LICENSE_PATH != null && !LICENSE_PATH.isEmpty());
        assumeTrue("plugin JAR not built: " + PLUGIN_JAR + " (run `mvn package`)",
                new File(PLUGIN_JAR).exists());
        assumeTrue("fixture wasm missing: " + FIXTURE_WASM,
                new File(FIXTURE_WASM).exists());
        assumeTrue("orchestrator wasm resource missing from classpath: " + ORCHESTRATOR_RESOURCE,
                ComposeIntegrationIT.class.getClassLoader().getResource(ORCHESTRATOR_RESOURCE) != null);

        // Boot the container early so the failsafe run's slowest step
        // (Stardog cold start) happens once for the whole class.
        CONTAINER = new StardogContainer()
                .withLicense(LICENSE_PATH)
                .withPluginJar(PLUGIN_JAR)
                // Capability master gate on — IE3 wants to exercise the
                // grant → resolver → enforcer → dispatch chain against
                // the composed CID's mounted URL. IE1/IE2/IE5 don't need
                // it but the gate being on is harmless for them.
                .withSystemProperty(WebFunctionConfig.PROP_CAPABILITY_ENABLED, "true")
                .withSystemProperty(WebFunctionConfig.PROP_CAPABILITY_UNKNOWN_EXTENSION_POLICY, "deny")
                .withSystemProperty(WebFunctionConfig.PROP_CAPABILITY_ANONYMOUS_POLICY, "permit");
        CONTAINER.start();
        SERVER_URL = CONTAINER.getServerUrl();

        try (AdminConnection admin = AdminConnectionConfiguration.toServer(SERVER_URL)
                .credentials("admin", "admin").connect()) {
            if (!admin.list().contains(DB)) {
                admin.newDatabase(DB).create();
            }
        }

        // Compose stack in the test JVM. The temp root doubles as
        // stardog.home for the loader — no interference with the running
        // container's own compose root inside the container.
        composeRoot = Files.createTempDirectory("wf-compose-it-");
        LOADER = new ComposeOrchestratorLoader(composeRoot);
        LOADER.ensureExtracted();
        ORCHESTRATOR = new ComposeOrchestratorInstance(LOADER);
        CLIENT = new ComposeOrchestratorClient(ORCHESTRATOR);
        ARTIFACT_STORE = ComposedArtifactStore.forLoader(LOADER);
        // Wire the sha256:// URL handler globally so tests can round-trip
        // composed CIDs through URL.openConnection() if they want to. The
        // container's plugin JVM has its own store; the test JVM's store
        // resolves through URL only inside this JVM.
        Sha256ArtifactUrlHandler.setStore(ARTIFACT_STORE);
        Sha256ArtifactUrlHandler.install();

        FIXTURE_WASM_BYTES = Files.readAllBytes(Paths.get(FIXTURE_WASM));
        FIXTURE_WASM_DIGEST =
                TestComposePlanFixtures.stageBlob(LOADER.blobsDir(), FIXTURE_WASM_BYTES);
    }

    @AfterClass
    public static void tearDown() {
        if (ORCHESTRATOR != null) {
            try { ORCHESTRATOR.close(); } catch (RuntimeException ignore) {}
        }
        if (CONTAINER != null) CONTAINER.stop();
        if (composeRoot != null) {
            try { deleteRecursively(composeRoot); } catch (RuntimeException ignore) {}
        }
    }

    private static void deleteRecursively(final Path root) {
        try {
            if (!Files.exists(root)) return;
            Files.walk(root)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignore) {} });
        } catch (java.io.IOException ignore) {}
    }

    // ---- IE1 ------------------------------------------------------------

    /**
     * IE1 — minimal plan compose flow end-to-end.
     *
     * <p>Encode a single-component plan whose digest points at the
     * pre-staged uppercase fixture blob, drive it through the real
     * orchestrator's {@code plan#deserialize} → {@code emit#compose} →
     * {@code get-artifact} chain, persist the composed bytes into the
     * test-JVM artifact store, and assert the composed wasm materialised
     * on disk under its content-addressed filename.
     */
    @Test
    public void ie1_minimalPlanComposesToArtifact() throws Exception {
        final PlanV1 plan = TestComposePlanFixtures.minimalPlan("uppercase", FIXTURE_WASM_DIGEST);
        final byte[] planCbor = PlanV1Cbor.encode(plan);

        final byte[] composed = CLIENT.composeFromCbor(planCbor);
        assertThat(composed).as("composed wasm bytes").isNotEmpty();

        final String cid = ARTIFACT_STORE.persist(composed);
        assertThat(cid).startsWith("sha256:");

        // Assert the on-disk artifact matches the CID's hex portion.
        final String hex = cid.substring("sha256:".length());
        final Path artifact = ARTIFACT_STORE.artifactsDir().resolve(hex + ".wasm");
        assertThat(Files.exists(artifact))
                .as("composed artifact must materialize under content-addressed filename")
                .isTrue();
        assertThat(Files.size(artifact))
                .as("composed artifact size on disk matches bytes returned by orchestrator")
                .isEqualTo(composed.length);

        // Round-trip through the store's load() surface — same lookup
        // the sha256:// URL handler funnels URL#openConnection through.
        final Optional<byte[]> readBack = ARTIFACT_STORE.load(cid);
        assertThat(readBack).isPresent();
        assertThat(readBack.get()).isEqualTo(composed);
    }

    // ---- helpers --------------------------------------------------------

    private static boolean isDockerAvailable() {
        try {
            org.testcontainers.DockerClientFactory.instance().client();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
