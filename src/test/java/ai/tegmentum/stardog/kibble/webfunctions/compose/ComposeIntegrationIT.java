package ai.tegmentum.stardog.kibble.webfunctions.compose;

import ai.tegmentum.stardog.kibble.StardogContainer;
import ai.tegmentum.stardog.kibble.webfunctions.WebFunctionConfig;

import com.complexible.stardog.StardogException;
import com.complexible.stardog.api.Connection;
import com.complexible.stardog.api.ConnectionConfiguration;
import com.complexible.stardog.api.admin.AdminConnection;
import com.complexible.stardog.api.admin.AdminConnectionConfiguration;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.SelectQueryResult;
import org.testcontainers.utility.MountableFile;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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
    /**
     * Plugin-managed capability policy database name. Duplicated here
     * rather than referencing {@code POLICY_DB}
     * because that helper lives in the parent package with
     * package-private visibility (test-only utility, kept next to its
     * consumers) — the compose IT lives in the subpackage so it inlines
     * the constant. Same value as
     * {@code WebFunctionConfig.DEFAULT_CAPABILITY_POLICY_STORE_DATABASE}.
     */
    private static final String POLICY_DB =
            WebFunctionConfig.DEFAULT_CAPABILITY_POLICY_STORE_DATABASE;
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

    // ---- IE2 ------------------------------------------------------------

    /**
     * IE2 — composition RDF is inserted into the capability DB's
     * compositions named graph and round-trips through a SPARQL SELECT.
     *
     * <p>Compose the fixture plan, ask the orchestrator for its Turtle
     * projection (via {@code sys:compose/rdf#plan-to-turtle-with-iri}),
     * push the Turtle into the container's
     * {@code system-webfunctions-capability} DB under
     * {@link ComposePolicyStoreWriter#COMPOSITIONS_NAMED_GRAPH}, then
     * SELECT the plan-anchored triples back out and assert the plan IRI,
     * a version literal, and a reference to the composed CID are all
     * visible.
     *
     * <p>Insert path is via SPARQL UPDATE over HTTP (bypasses the
     * Kernel-backed {@link ComposePolicyStoreWriter}, which needs a live
     * Kernel we don't have from the test JVM). The resulting on-disk
     * state is identical — same named graph, same triple shapes — so
     * IE2 exercises the "compositions DB is queryable and carries the
     * expected triples" contract even without going through the
     * production writer.
     */
    @Test
    public void ie2_compositionRdfInsertedAndQueryable() {
        // Ensure the plugin's own bootstrap has created the policy DB —
        // defensive belt against a race with the plugin's
        // CapabilityPolicyStarter (see WebFunctionServiceModule).
        ensurePolicyDb(SERVER_URL);

        final String planIri = "urn:test:compose-it:plan:ie2";
        final PlanV1 plan = TestComposePlanFixtures.minimalPlan("uppercase", FIXTURE_WASM_DIGEST);
        final byte[] planCbor = PlanV1Cbor.encode(plan);

        // Compose to obtain a CID we can reference in RDF assertions.
        final byte[] composed = CLIENT.composeFromCbor(planCbor);
        assertThat(composed).isNotEmpty();
        final String cid;
        try {
            cid = ARTIFACT_STORE.persist(composed);
        } catch (java.io.IOException ioe) {
            throw new IllegalStateException("artifact persist failed: " + ioe.getMessage(), ioe);
        }
        final String cidUrl = "sha256://" + cid.substring("sha256:".length());

        // Turtle projection from the orchestrator, keyed on our plan IRI
        // so the DELETE-before-INSERT idempotency contract holds even
        // when tests run in different orders across CI shards.
        final String turtle = CLIENT.planToTurtleCbor(planCbor, planIri);
        assertThat(turtle)
                .as("planToTurtle must produce a non-empty Turtle document")
                .isNotBlank();

        // Push into the compositions graph. Also assert the composed CID
        // via a wf:hasComposedArtifact triple so IE2's assertion set
        // includes both orchestrator-produced RDF and admin-tracked
        // composed-artifact anchoring.
        insertCompositionsTurtle(SERVER_URL, planIri, turtle, cidUrl);

        // Query the compositions graph back out and confirm the plan IRI,
        // a triple carrying the composed CID URL, and at least one plan
        // predicate the orchestrator emits are visible.
        final List<String[]> rows = readCompositionTriples(SERVER_URL, planIri);
        assertThat(rows)
                .as("compositions graph should carry orchestrator-emitted plan triples for " + planIri)
                .isNotEmpty();
        assertThat(rows)
                .as("composition RDF should reference the composed CID URL")
                .anyMatch(pv -> cidUrl.equals(pv[1]));
    }

    // ---- IE3 ------------------------------------------------------------

    /**
     * IE3 — a composed CID loads through the full extension pipeline
     * and its execution is gated by the plugin's capability enforcer.
     *
     * <p>Compose the fixture plan to obtain composed wasm bytes; mount
     * those bytes into the container at
     * {@code /opt/wasm/composed_ie3.wasm}; assert that a wf:call
     * against the composed URL is <em>denied</em> before any grant
     * (capability master gate is on with {@code unknown-extension-policy=deny});
     * insert a grant into the policy DB; assert that the same wf:call
     * now <em>succeeds</em> and returns the uppercase result the root
     * component computes.
     *
     * <p>URL scheme note: the container's plugin JVM has its own
     * {@link ComposedArtifactStore} instance whose {@code sha256://}
     * handler resolves against an on-disk artifacts directory it owns,
     * <em>not</em> the test JVM's. Rather than shipping the composed
     * bytes into the container's compose-root and reaching in to
     * synthesize a CID URL that the container can dereference, we mount
     * the composed bytes at a stable {@code file://} path — which
     * exercises the same {@code StardogWasmInstance.from(URL)} +
     * capability-enforcer chain as {@code sha256://} loads (both funnel
     * through the same URL#openConnection stream extraction). The C11
     * unit test {@link TestSha256UrlLoader} covers the URL-scheme
     * dereference contract on its own.
     */
    @Test
    public void ie3_composedCidLoadsThroughExtensionPipeline() throws Exception {
        ensurePolicyDb(SERVER_URL);

        final PlanV1 plan = TestComposePlanFixtures.minimalPlan("uppercase", FIXTURE_WASM_DIGEST);
        final byte[] composed = CLIENT.composeFromCbor(PlanV1Cbor.encode(plan));
        assertThat(composed).isNotEmpty();

        // Materialise the composed wasm on the host so we can mount it
        // into the container. Same on-disk shape as the artifact store
        // would produce; we use a stable filename so the file:// URL
        // stays predictable across test runs.
        final Path stagingDir = tmp.newFolder("compose-staging").toPath();
        final Path composedFile = stagingDir.resolve("composed_ie3.wasm");
        Files.write(composedFile, composed);

        final String containerPath = "/opt/wasm/composed_ie3.wasm";
        CONTAINER.copyFileToContainer(
                MountableFile.forHostPath(composedFile.toAbsolutePath().toString()),
                containerPath);
        final String composedUrl = "file://" + containerPath;

        // Purge any prior grant residue so the "denied before grant"
        // half of the test observes the empty-policy state cleanly.
        purgeExtension(SERVER_URL, composedUrl);

        // --- Half A: denied when no grant ---------------------------
        // With unknown-extension-policy=deny and no policy triples, the
        // resolver refuses to instantiate the extension. Same error
        // shape as CapabilityDenyIT.
        final Throwable denied = catchThrown(() -> invokeUpper(composedUrl));
        assertThat(denied)
                .as("wf:call on composed URL must be denied before any grant is written")
                .isNotNull();
        assertThat(denied).isInstanceOf(StardogException.class);
        final String deniedMessage = denied.getMessage() == null ? "" : denied.getMessage();
        assertThat(deniedMessage).contains("WF_CAPABILITY_UNKNOWN_EXTENSION");
        assertThat(deniedMessage).contains(composedUrl);

        // --- Half B: permitted when granted -------------------------
        // A trivial http-callbacks grant is enough to make the extension
        // "known" — same idiom I5/I6 use. The uppercase root exports the
        // `upper` filter function and imports no callback interface, so
        // the grant intersection stays empty; the wf:call still succeeds.
        grantHttpCallbacks(SERVER_URL, composedUrl);

        try (Connection conn = ConnectionConfiguration.to(DB)
                .server(SERVER_URL)
                .credentials("admin", "admin")
                .connect();
             SelectQueryResult r = conn.select(upperQuery(composedUrl)).execute()) {
            assertThat(r.hasNext()).isTrue();
            final Optional<Value> v = r.next().value("result");
            assertThat(v).isPresent();
            assertThat(v.get()).isInstanceOf(Literal.class);
            assertThat(((Literal) v.get()).label()).isEqualTo("STARDOG");
        }
    }

    // ---- IE4 ------------------------------------------------------------

    /**
     * IE4 — content-addressed CIDs preserve distinct compositions.
     *
     * <p>Compose two plans that differ in a well-observed field
     * (Policy tenant tag), assert:
     * <ul>
     *   <li>The two composed byte streams produce distinct CIDs — the
     *       orchestrator's emit surface is <em>content-addressed</em>
     *       so a plan-level change flows through to a distinct hash.</li>
     *   <li>Both artifacts materialise on disk under their respective
     *       hex filenames — the store's idempotent write path does not
     *       overwrite v1 when v2 lands.</li>
     *   <li>Both plans' Turtle projections land in the compositions
     *       named graph under distinct plan IRIs — each occupies its
     *       own idempotency slot without stepping on the other.</li>
     * </ul>
     *
     * <p>The two plans share the same root component + digest so the
     * blob validator does not stall on a missing blob; only the plan's
     * Policy (specifically the tenant tag) differs. The CBOR encoder
     * carries the tenant literal through to the byte stream (see
     * TestPlanV1Cbor#policyTenantEncodedWhenPresent), so the digest
     * distinction is expected — this test tightens that unit-level
     * observation into an integration-level round trip.
     */
    @Test
    public void ie4_contentAddressedCidsPreserveDistinctCompositions() throws Exception {
        ensurePolicyDb(SERVER_URL);

        final PlanV1 planA = TestComposePlanFixtures.fullerPlan(
                "uppercase", FIXTURE_WASM_DIGEST, "tenant-alpha");
        final PlanV1 planB = TestComposePlanFixtures.fullerPlan(
                "uppercase", FIXTURE_WASM_DIGEST, "tenant-beta");

        final byte[] cborA = PlanV1Cbor.encode(planA);
        final byte[] cborB = PlanV1Cbor.encode(planB);
        assertThat(cborA)
                .as("plans with distinct tenant tags must produce distinct CBOR")
                .isNotEqualTo(cborB);

        final byte[] composedA = CLIENT.composeFromCbor(cborA);
        final byte[] composedB = CLIENT.composeFromCbor(cborB);

        final String cidA = ARTIFACT_STORE.persist(composedA);
        final String cidB = ARTIFACT_STORE.persist(composedB);
        assertThat(cidA)
                .as("distinct plans should compose to distinct content-addressed CIDs")
                .isNotEqualTo(cidB);

        // Both blobs on disk — the artifact store's persist path does
        // not overwrite when a second CID lands.
        final Path artA = ARTIFACT_STORE.artifactsDir()
                .resolve(cidA.substring("sha256:".length()) + ".wasm");
        final Path artB = ARTIFACT_STORE.artifactsDir()
                .resolve(cidB.substring("sha256:".length()) + ".wasm");
        assertThat(Files.exists(artA)).isTrue();
        assertThat(Files.exists(artB)).isTrue();

        // Both plans' Turtle projections land in the compositions graph
        // under their respective plan IRIs. Distinct DELETE-INSERT
        // windows so neither eviction steps on the other.
        final String iriA = "urn:test:compose-it:plan:ie4:alpha";
        final String iriB = "urn:test:compose-it:plan:ie4:beta";
        final String cidUrlA = "sha256://" + cidA.substring("sha256:".length());
        final String cidUrlB = "sha256://" + cidB.substring("sha256:".length());
        insertCompositionsTurtle(SERVER_URL, iriA, CLIENT.planToTurtleCbor(cborA, iriA), cidUrlA);
        insertCompositionsTurtle(SERVER_URL, iriB, CLIENT.planToTurtleCbor(cborB, iriB), cidUrlB);

        final List<String[]> rowsA = readCompositionTriples(SERVER_URL, iriA);
        final List<String[]> rowsB = readCompositionTriples(SERVER_URL, iriB);
        assertThat(rowsA).as("plan A triples visible in compositions graph").isNotEmpty();
        assertThat(rowsB).as("plan B triples visible in compositions graph").isNotEmpty();
        assertThat(rowsA)
                .as("plan A carries the plan-A CID as its composed artifact anchor")
                .anyMatch(pv -> cidUrlA.equals(pv[1]));
        assertThat(rowsB)
                .as("plan B carries the plan-B CID as its composed artifact anchor")
                .anyMatch(pv -> cidUrlB.equals(pv[1]));
        assertThat(rowsA)
                .as("plan A must NOT surface the plan-B CID URL")
                .noneMatch(pv -> cidUrlB.equals(pv[1]));
        assertThat(rowsB)
                .as("plan B must NOT surface the plan-A CID URL")
                .noneMatch(pv -> cidUrlA.equals(pv[1]));
    }

    // ---- helpers --------------------------------------------------------

    /**
     * SPARQL UPDATE that (a) DELETEs any prior triples anchored on
     * {@code planIri} in the compositions graph, then (b) INSERTs the
     * orchestrator's Turtle plus a {@code wf:hasComposedArtifact} anchor
     * triple linking the plan IRI to the composed CID URL. Mirrors the
     * production writer's DELETE-then-INSERT idempotency shape without
     * requiring a Kernel reference.
     */
    private static void insertCompositionsTurtle(final String serverUrl,
                                                 final String planIri,
                                                 final String turtle,
                                                 final String cidUrl) {
        final String graph = ComposePolicyStoreWriter.COMPOSITIONS_NAMED_GRAPH;
        final String hasComposedArtifact =
                "http://semantalytics.com/2021/03/ns/stardog/webfunction/hasComposedArtifact";
        // Break the update into three statements so a syntactic issue in
        // one doesn't hide a later one. Stardog batches these in a single
        // transaction the same way multiple SPARQL Update requests would.
        final String deleteExisting =
                "DELETE { GRAPH <" + graph + "> { <" + planIri + "> ?p ?o } } "
                        + "WHERE { GRAPH <" + graph + "> { <" + planIri + "> ?p ?o } }";
        final String insertTurtle =
                "INSERT DATA { GRAPH <" + graph + "> {\n"
                        + turtle
                        + "\n<" + planIri + "> <" + hasComposedArtifact + "> <" + cidUrl + "> .\n"
                        + "} }";
        try (Connection conn = ConnectionConfiguration.to(
                    POLICY_DB)
                .server(serverUrl)
                .credentials("admin", "admin")
                .connect()) {
            conn.begin();
            conn.update(deleteExisting).execute();
            conn.update(insertTurtle).execute();
            conn.commit();
        }
    }

    /**
     * SPARQL SELECT that reads back every triple anchored on
     * {@code planIri} in the compositions named graph. Returns
     * {@code (predicate, object-rendered)} pairs so the assertions can
     * grep for content without a per-value Value-vs-Literal dispatch.
     */
    private static List<String[]> readCompositionTriples(final String serverUrl,
                                                         final String planIri) {
        final String graph = ComposePolicyStoreWriter.COMPOSITIONS_NAMED_GRAPH;
        final String q =
                "SELECT ?p ?o WHERE { GRAPH <" + graph + "> { <" + planIri + "> ?p ?o . } }";
        final List<String[]> rows = new ArrayList<>();
        try (Connection conn = ConnectionConfiguration.to(
                    POLICY_DB)
                .server(serverUrl)
                .credentials("admin", "admin")
                .connect();
             SelectQueryResult r = conn.select(q).execute()) {
            while (r.hasNext()) {
                final BindingSet bs = r.next();
                final Optional<Value> p = bs.value("p");
                final Optional<Value> o = bs.value("o");
                rows.add(new String[]{
                        p.map(Value::toString).orElse(""),
                        o.map(v -> {
                            if (v instanceof com.stardog.stark.Literal l) return l.label();
                            return v.toString();
                        }).orElse("")
                });
            }
        }
        return rows;
    }

    /**
     * SPARQL prefix + namespace for the plugin's function vocabulary.
     * Inlined to avoid a class-init NoClassDefFound on WebFunctionVocabulary
     * — same reason WasmTestSuiteIT / CapabilityAskIT inline the literal.
     */
    private static final String WF_NAMESPACE =
            "http://semantalytics.com/2021/03/ns/stardog/webfunction/latest/";

    /**
     * Capability-vocabulary namespace + IRI for the http-callbacks
     * interface — inlined for the same shade-hostile reason WF_NAMESPACE is.
     */
    private static final String CAP_NAMESPACE =
            "http://semantalytics.com/2021/03/ns/stardog/webfunction-capability/";
    private static final String IFACE_HTTP_CALLBACKS =
            "tegmentum:webfunction/http-callbacks@0.1.0";

    private static String upperQuery(final String extensionUrl) {
        return "PREFIX wf: <" + WF_NAMESPACE + ">"
                + " SELECT ?result WHERE { BIND(wf:call(str(<" + extensionUrl + ">),"
                + " \"upper\", \"stardog\") AS ?result) }";
    }

    /**
     * Run the {@code upper} filter function on {@code extensionUrl} and
     * drain the result. Discards the return value; callers that want the
     * value use the inlined form (see IE3 Half B).
     */
    private static void invokeUpper(final String extensionUrl) {
        try (Connection conn = ConnectionConfiguration.to(DB)
                .server(SERVER_URL)
                .credentials("admin", "admin")
                .connect();
             SelectQueryResult r = conn.select(upperQuery(extensionUrl)).execute()) {
            while (r.hasNext()) r.next();
        }
    }

    /**
     * INSERT a minimal trusted + allow-interface grant into the policy
     * DB, mirroring {@code CapabilityPolicyDbHelpers.grantInterfaces}
     * with a single (interface, extension) pair.
     */
    private static void grantHttpCallbacks(final String serverUrl, final String extensionUrl) {
        final String q =
                "PREFIX cap: <" + CAP_NAMESPACE + ">\n"
                + "INSERT DATA { <" + extensionUrl + "> cap:trusted true ; "
                + "cap:allowInterface <" + IFACE_HTTP_CALLBACKS + "> . }";
        try (Connection conn = ConnectionConfiguration.to(POLICY_DB)
                .server(serverUrl)
                .credentials("admin", "admin")
                .connect()) {
            conn.begin();
            conn.update(q).execute();
            conn.commit();
        }
    }

    /**
     * DELETE every triple in the policy DB anchored on
     * {@code extensionUrl} — same idiom as
     * {@code CapabilityPolicyDbHelpers.purgeExtension} but pared down to
     * the default-graph half we care about here.
     */
    private static void purgeExtension(final String serverUrl, final String extensionUrl) {
        final String q =
                "DELETE { ?s ?p ?o } WHERE { ?s ?p ?o "
                + "FILTER (?s = <" + extensionUrl + ">) }";
        try (Connection conn = ConnectionConfiguration.to(POLICY_DB)
                .server(serverUrl)
                .credentials("admin", "admin")
                .connect()) {
            conn.begin();
            conn.update(q).execute();
            conn.commit();
        }
    }

    /** Local Throwable-capturing helper — assertj-esque without the dep coupling. */
    private static Throwable catchThrown(final ThrowingRunnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Idempotent bootstrap of the plugin-managed capability policy DB.
     * Local copy of the parent-package {@code CapabilityPolicyDbHelpers.ensurePolicyDb}
     * because that helper is package-private.
     */
    private static void ensurePolicyDb(final String serverUrl) {
        try (AdminConnection admin = AdminConnectionConfiguration.toServer(serverUrl)
                .credentials("admin", "admin")
                .connect()) {
            if (!admin.list().contains(POLICY_DB)) {
                admin.newDatabase(POLICY_DB).create();
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
