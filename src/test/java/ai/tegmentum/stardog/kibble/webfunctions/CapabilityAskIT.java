package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.StardogContainer;

import com.complexible.stardog.api.Connection;
import com.complexible.stardog.api.ConnectionConfiguration;
import com.complexible.stardog.api.admin.AdminConnection;
import com.complexible.stardog.api.admin.AdminConnectionConfiguration;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.query.SelectQueryResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.testcontainers.containers.Container;
import org.testcontainers.utility.MountableFile;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * Compound integration test for the capability-ask wave —
 * sub-phases I4, I5, I6, I7, I8 grouped together because they share
 * the same container config (capability master gate on, disk-backed
 * audit ring, both fixture wasms mounted). Each test purges its
 * extension's policy-DB state in {@code @Before} so cases don't
 * observe each other's residue.
 *
 * <p>Method order is pinned via {@link FixMethodOrder} for
 * deterministic behaviour of the audit-log slicing in I7 — the
 * on-disk NDJSON log accumulates rows across the whole class run
 * and I7's grep window is anchored at the row count before the
 * test's invocation.
 *
 * <p>Container config summary (mirrors WebFunctionConfig keys):
 * <ul>
 *   <li>{@code webfunctions.capability.enabled=true}</li>
 *   <li>{@code webfunctions.capability.unknown-extension-policy=deny}
 *       — every test grants first before invoking, so an unrelated
 *       flow through this branch is caught rather than silently
 *       passed.</li>
 *   <li>{@code webfunctions.capability.anonymous-policy=permit}
 *       — HTTP admin auth produces a bound subject, but the
 *       explicit permit rules out any silent reroute.</li>
 *   <li>Disk-backed audit enabled at {@code /var/opt/stardog/wf-audit/}
 *       with {@code per-row} fsync so I7 can read GRANTED_UNDECLARED
 *       rows immediately after the invocation returns.</li>
 * </ul>
 *
 * <p>Skipped unless Docker + STARDOG_LICENSE_PATH + shaded plugin
 * JAR + both fixture wasms are all present.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class CapabilityAskIT {

    private static final String DB = "test_cap_ask";
    private static final String LICENSE_PATH = System.getenv("STARDOG_LICENSE_PATH");
    private static final String PLUGIN_JAR = System.getProperty("wf.plugin.jar",
            "target/tegmentum-stardog-webfunction-1.0.3.jar");

    // Fixture wasms (paths relative to the module root — the failsafe
    // plugin runs from there).
    private static final String UPPERCASE_WITH_ASK =
            "src/test/resources/integration/example_uppercase_extension_with_ask.wasm";
    private static final String UPPERCASE_WITH_ASK_V2 =
            "src/test/resources/integration/example_uppercase_extension_with_ask_v2.wasm";
    private static final String GRAPH_CB_WITH_ASK =
            "src/test/resources/integration/example_graph_callback_extension_with_ask.wasm";

    // In-container paths for the same fixtures.
    private static final String C_UPPERCASE =
            "/opt/wasm/example_uppercase_extension_with_ask.wasm";
    private static final String C_GRAPH_CB =
            "/opt/wasm/example_graph_callback_extension_with_ask.wasm";

    // Disk-backed audit sink directory — chmod-777'd at container
    // start so the plugin's writer thread can create the log files
    // regardless of the Stardog process user.
    static final String AUDIT_DIR = "/var/opt/stardog/wf-audit";
    static final String CAP_AUDIT_LOG = AUDIT_DIR + "/audit-capability.log";

    static StardogContainer CONTAINER;
    static String UPPERCASE_URL;
    static String GRAPH_CB_URL;
    static String SERVER_URL;

    @BeforeClass
    public static void bootContainer() throws Exception {
        assumeTrue("Docker not available", isDockerAvailable());
        assumeTrue("STARDOG_LICENSE_PATH not set",
                LICENSE_PATH != null && !LICENSE_PATH.isEmpty());
        assumeTrue("plugin JAR not built: " + PLUGIN_JAR + " (run `mvn package`)",
                new File(PLUGIN_JAR).exists());
        assumeTrue("uppercase-with-ask fixture missing: " + UPPERCASE_WITH_ASK,
                new File(UPPERCASE_WITH_ASK).exists());
        assumeTrue("uppercase-with-ask-v2 fixture missing: " + UPPERCASE_WITH_ASK_V2,
                new File(UPPERCASE_WITH_ASK_V2).exists());
        assumeTrue("graph-callback-with-ask fixture missing: " + GRAPH_CB_WITH_ASK,
                new File(GRAPH_CB_WITH_ASK).exists());

        CONTAINER = new StardogContainer()
                .withLicense(LICENSE_PATH)
                .withPluginJar(PLUGIN_JAR)
                .withSystemProperty(WebFunctionConfig.PROP_CAPABILITY_ENABLED, "true")
                .withSystemProperty(WebFunctionConfig.PROP_CAPABILITY_UNKNOWN_EXTENSION_POLICY, "deny")
                .withSystemProperty(WebFunctionConfig.PROP_CAPABILITY_ANONYMOUS_POLICY, "permit")
                .withSystemProperty(WebFunctionConfig.PROP_CAPABILITY_AUDIT_ENABLED, "true")
                .withSystemProperty(WebFunctionConfig.PROP_AUDIT_DISK_ENABLED, "true")
                .withSystemProperty(WebFunctionConfig.PROP_AUDIT_DISK_DIRECTORY, AUDIT_DIR)
                .withSystemProperty(WebFunctionConfig.PROP_AUDIT_DISK_FSYNC_POLICY, "per-row");
        UPPERCASE_URL = CONTAINER.withWasm(UPPERCASE_WITH_ASK, C_UPPERCASE);
        GRAPH_CB_URL = CONTAINER.withWasm(GRAPH_CB_WITH_ASK, C_GRAPH_CB);
        CONTAINER.start();
        SERVER_URL = CONTAINER.getServerUrl();

        // chmod the audit dir so the NDJSON sink can create files.
        // The container's Stardog process may not run as root; a
        // pre-created dir avoids a permission-denied at first append.
        CONTAINER.execInContainer("mkdir", "-p", AUDIT_DIR);
        CONTAINER.execInContainer("chmod", "777", AUDIT_DIR);

        try (AdminConnection admin = AdminConnectionConfiguration.toServer(SERVER_URL)
                .credentials("admin", "admin").connect()) {
            if (!admin.list().contains(DB)) {
                admin.newDatabase(DB).create();
            }
        }
        // The plugin's CapabilityPolicyStarter auto-creates
        // system-webfunctions-capability at kernel-install time;
        // ensurePolicyDb is defensive against ordering surprises.
        CapabilityPolicyDbHelpers.ensurePolicyDb(SERVER_URL);
    }

    @AfterClass
    public static void tearDown() {
        if (CONTAINER != null) CONTAINER.stop();
    }

    @Before
    public void purgePolicyState() {
        // Purge both extensions' policy + ask triples so tests don't
        // observe each other's residue when they run in sequence.
        CapabilityPolicyDbHelpers.purgeExtension(SERVER_URL, UPPERCASE_URL);
        CapabilityPolicyDbHelpers.purgeExtension(SERVER_URL, GRAPH_CB_URL);
    }

    static final String WF_NAMESPACE =
            "http://semantalytics.com/2021/03/ns/stardog/webfunction/latest/";

    // ---- I4 --------------------------------------------------------------

    /**
     * I4 — grant + invoker-subject GRAPH ACL enforcement.
     *
     * <p>The scenario needs two Stardog users (Alice with read on
     * graph G1, Bob without), each with the Shiro
     * {@code web-function-callback:invoke:graph-callbacks/*}
     * permission but not overlapping GRAPH ACLs, then a query
     * invoked as each user asserting that the extension's
     * execute-query dispatch reads with the invoker's ACLs.
     *
     * <p>Disabled for this landing: the multi-user + role-permission
     * + GRAPH ACL setup wants a full admin-API integration harness
     * that this integration suite doesn't yet carry, and the
     * invoker-subject wrap plumbing itself is already covered at
     * the unit level by {@code TestHostCallbacksInvokerSubject}
     * (host-side ShiroUtils.executeAs) plus
     * {@code TestCapabilityEnforcer} (grant→dispatch happy path).
     *
     * <p>TODO(post-MVP): land a multi-user helper on top of
     * {@code CapabilityPolicyDbHelpers} that provisions users,
     * roles, and per-graph read permissions, then wire this test.
     */
    @Test
    @Ignore("I4 — multi-user + role-permission + GRAPH ACL setup pending; "
            + "unit tests TestHostCallbacksInvokerSubject + TestCapabilityEnforcer "
            + "cover the invoker-subject dispatch plumbing.")
    public void i4_grantExecutesUnderInvokerSubject() {
        // Skeleton preserved so a follow-up patch has a concrete
        // wire-up target — grant graph-callbacks + execute-query to
        // both users; run wf:call as Alice against a graph she can
        // read; run same wf:call as Bob against the same graph;
        // assert Alice sees data, Bob sees empty/denied; assert
        // the capability attribution ring (via /audit-capability.log)
        // carries rows for both invocations with the correct
        // Shiro principal.
    }

    // ---- I5 --------------------------------------------------------------

    @Test
    public void i5_askAutoInsertedIntoPolicyDbOnLoad() {
        // Any non-empty policy triple keeps the resolver from routing
        // through unknown-extension-policy=deny. Uppercase imports no
        // callback interface so the grant intersection is empty; the
        // trivial http-callbacks grant is enough to make the extension
        // "known" without meaningfully affecting dispatch.
        CapabilityPolicyDbHelpers.grantInterfaces(SERVER_URL, UPPERCASE_URL,
                List.of(CapabilityVocabulary.IFACE_HTTP_CALLBACKS),
                List.of());

        invokeUpper();

        final List<String[]> rows = CapabilityPolicyDbHelpers.readAskTriples(
                SERVER_URL, UPPERCASE_URL);
        assertThat(rows).as("ask triples should have been auto-inserted").isNotEmpty();

        // Assert every predicate the source Turtle declared shows up
        // in the round-tripped set. The ask body:
        //   cap:asksInterface cap:HttpCallbacks
        //   cap:asksHost      "api.example.com"
        //   cap:asksRationale "..."
        assertThat(rows).anyMatch(pv ->
                CapabilityVocabulary.CAP_ASKS_INTERFACE.equals(pv[0])
                && CapabilityVocabulary.IFACE_HTTP_CALLBACKS.equals(pv[1]));
        assertThat(rows).anyMatch(pv ->
                CapabilityVocabulary.CAP_ASKS_HOST.equals(pv[0])
                && "api.example.com".equals(pv[1]));
        assertThat(rows).anyMatch(pv ->
                CapabilityVocabulary.CAP_ASKS_RATIONALE.equals(pv[0])
                && pv[1].contains("Integration-test ask for uppercase"));
    }

    // ---- I6 --------------------------------------------------------------

    @Test
    public void i6_diffQueryReturnsPendingReview() {
        // Grant only cap:allowInterface cap:HttpCallbacks — no
        // cap:allowHost triples. The uppercase ask carries
        // cap:asksHost "api.example.com", so an admin diff query
        // filtering "asked but not granted" on the host axis will
        // surface that literal as pending review.
        CapabilityPolicyDbHelpers.grantInterfaces(SERVER_URL, UPPERCASE_URL,
                List.of(CapabilityVocabulary.IFACE_HTTP_CALLBACKS),
                List.of());

        invokeUpper();

        // Admin diff query — "asked but not allowed" on the host axis.
        // The ask carries cap:asksHost "api.example.com"; the grant
        // carries no cap:allowHost — so the diff returns the ask'd host.
        final String diff =
                CapabilityPolicyDbHelpers.CAP_PREFIX
                + "SELECT ?host WHERE {\n"
                + "  GRAPH <" + CapabilityVocabulary.CAP_ASKS_NAMED_GRAPH + "> {\n"
                + "    ?askDoc cap:asksHost ?host .\n"
                + "    <" + UPPERCASE_URL + "> cap:hasAsk ?askDoc .\n"
                + "  }\n"
                + "  FILTER NOT EXISTS {\n"
                + "    <" + UPPERCASE_URL + "> cap:allowHost ?host .\n"
                + "  }\n"
                + "}";
        final List<String> pending = new ArrayList<>();
        try (Connection conn = ConnectionConfiguration.to(
                    CapabilityPolicyDbHelpers.POLICY_DB)
                .server(SERVER_URL)
                .credentials("admin", "admin")
                .connect();
             SelectQueryResult r = conn.select(diff).execute()) {
            while (r.hasNext()) {
                final Optional<Value> v = r.next().value("host");
                v.ifPresent(val -> {
                    if (val instanceof Literal l) pending.add(l.label());
                    else pending.add(val.toString());
                });
            }
        }
        assertThat(pending)
                .as("ask host should surface as pending review — asked but not granted")
                .contains("api.example.com");
    }

    // ---- I7 --------------------------------------------------------------

    /**
     * I7 — warn-on-undeclared audit row when the extension invokes a
     * host callback whose (interface, method) is NOT declared in its
     * embedded ask.
     *
     * <p>Fixture wiring:
     * <ul>
     *   <li>{@code example_graph_callback_extension_with_ask.wasm}
     *       imports graph-callbacks and invokes execute-query at
     *       runtime.</li>
     *   <li>Its embedded ask declares only http-callbacks — a
     *       deliberately understated ask so the runtime dispatch
     *       falls outside the ask's declared surface.</li>
     * </ul>
     *
     * <p>We grant graph-callbacks + graph-callbacks/execute-query in
     * the policy DB so the dispatch actually proceeds; the
     * warn-on-undeclared path fires <em>after</em> the grant check
     * clears. We then read the on-disk audit-capability.log
     * (per-row fsync guarantees the row is on disk before the
     * assertion) and grep for a GRANTED_UNDECLARED row for the
     * dispatched (interface, method).
     */
    @Test
    public void i7_warnOnUndeclaredWritesAuditRow() throws Exception {
        // Grant graph-callbacks + execute-query so the dispatch proceeds.
        CapabilityPolicyDbHelpers.grantInterfaces(SERVER_URL, GRAPH_CB_URL,
                List.of(CapabilityVocabulary.IFACE_GRAPH_CALLBACKS),
                List.of("graph-callbacks/execute-query"));

        // Note the current byte offset of the audit log so we grep
        // only rows written by THIS test. tail-c anchoring at the
        // pre-invocation size avoids seeing rows other sub-phases
        // wrote (I5, I6 both emit GRANTED rows on their invokes).
        final long preSize = auditLogSize();

        // Invoke expand-neighborhood on an IRI. The extension will
        // call graph-callbacks/execute-query internally; the guest
        // return value is a comma-separated string of neighbours
        // (empty when there are none). We only care that the
        // dispatch actually reached execute-query — the empty
        // response is enough evidence the host callback fired.
        try (Connection conn = ConnectionConfiguration.to(DB)
                .server(SERVER_URL)
                .credentials("admin", "admin")
                .connect()) {
            final String query =
                    "PREFIX wf: <" + WF_NAMESPACE + ">\n"
                  + "PREFIX ex: <http://example.org/>\n"
                  + "SELECT ?result WHERE {\n"
                  + "  BIND(wf:call(str(<" + GRAPH_CB_URL + ">),"
                  + " \"expand-neighborhood\", ex:root) AS ?result)\n"
                  + "}";
            try (SelectQueryResult r = conn.select(query).execute()) {
                while (r.hasNext()) r.next();
            }
        }

        // Read the tail of the audit log written since preSize.
        final String tail = readAuditLogTail(preSize);
        assertThat(tail)
                .as("audit-capability.log should carry a GRANTED_UNDECLARED row "
                        + "for graph-callbacks/execute-query on " + GRAPH_CB_URL)
                .contains("GRANTED_UNDECLARED")
                .contains(GRAPH_CB_URL)
                .contains("graph-callbacks")
                .contains("execute-query");
    }

    // ---- I8 --------------------------------------------------------------

    @Test
    public void i8_askOverwriteOnReload() throws Exception {
        // Grant one interface so the extension is "known" per the
        // resolver — same trick I5 uses.
        CapabilityPolicyDbHelpers.grantInterfaces(SERVER_URL, UPPERCASE_URL,
                List.of(CapabilityVocabulary.IFACE_HTTP_CALLBACKS),
                List.of());

        // Round 1 — extension load records the v1 ask.
        invokeUpper();
        final List<String[]> v1Rows = CapabilityPolicyDbHelpers.readAskTriples(
                SERVER_URL, UPPERCASE_URL);
        assertThat(v1Rows).anyMatch(pv ->
                CapabilityVocabulary.CAP_ASKS_HOST.equals(pv[0])
                && "api.example.com".equals(pv[1]));
        // A v2-only predicate the v1 round should NOT contain — the
        // round-2 assertion relies on it appearing only after the swap.
        assertThat(v1Rows).noneMatch(pv ->
                CapabilityVocabulary.CAP_ASKS_INTERFACE.equals(pv[0])
                && CapabilityVocabulary.IFACE_GRAPH_CALLBACKS.equals(pv[1]));

        // Swap the wasm bytes on the container's filesystem — the
        // plugin's byte cache (`loadingCache`) still holds v1 bytes,
        // so we also invalidate via wf:cacheClear. (The COMPONENT_CACHE
        // still holds the v1 compiled component — that's OK: the ask
        // extraction reads bytes from the freshly-invalidated
        // loadingCache, not from the cached component, so the ask
        // pipeline sees v2 even though the executable component is
        // v1's compiled form. Both fixtures share the same guest
        // code — only the custom section differs.)
        CONTAINER.copyFileToContainer(
                MountableFile.forHostPath(new File(UPPERCASE_WITH_ASK_V2).getAbsolutePath()),
                C_UPPERCASE);
        clearWasmCache(UPPERCASE_URL);

        // Round 2 — extension load re-reads bytes; extractAndRecordAsk
        // parses v2's ask and the store's DELETE-then-INSERT overwrites
        // v1's triples.
        invokeUpper();

        final List<String[]> v2Rows = CapabilityPolicyDbHelpers.readAskTriples(
                SERVER_URL, UPPERCASE_URL);
        // v2 predicates SHOULD be present.
        assertThat(v2Rows).anyMatch(pv ->
                CapabilityVocabulary.CAP_ASKS_HOST.equals(pv[0])
                && "api.v2.example.com".equals(pv[1]));
        assertThat(v2Rows).anyMatch(pv ->
                CapabilityVocabulary.CAP_ASKS_INTERFACE.equals(pv[0])
                && CapabilityVocabulary.IFACE_GRAPH_CALLBACKS.equals(pv[1]));
        // v1 predicates SHOULD NOT be present anymore — the DELETE
        // half of the overwrite fired.
        assertThat(v2Rows).noneMatch(pv ->
                CapabilityVocabulary.CAP_ASKS_HOST.equals(pv[0])
                && "api.example.com".equals(pv[1]));
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * Drive the uppercase extension's {@code upper} filter function
     * once. Return value is discarded — the tests read the plugin's
     * side effect (ask triples + audit rows) rather than the guest
     * return.
     */
    static void invokeUpper() {
        final String query = "PREFIX wf: <" + WF_NAMESPACE + ">"
                + " SELECT ?result WHERE { BIND(wf:call(str(<" + UPPERCASE_URL + ">), \"upper\", \"stardog\") AS ?result) }";
        try (Connection conn = ConnectionConfiguration.to(DB)
                .server(SERVER_URL)
                .credentials("admin", "admin")
                .connect();
             SelectQueryResult r = conn.select(query).execute()) {
            while (r.hasNext()) r.next();
        }
    }

    /**
     * Invoke {@code wf:cacheClear(<url>)} — the SPARQL surface for
     * invalidating the plugin's byte cache. Used by I8 so the next
     * wf:call re-reads bytes from disk after a file swap.
     */
    static void clearWasmCache(final String url) {
        final String q = "PREFIX wf: <" + WF_NAMESPACE + ">"
                + " SELECT ?r WHERE { BIND(wf:cacheClear(str(<" + url + ">)) AS ?r) }";
        try (Connection conn = ConnectionConfiguration.to(DB)
                .server(SERVER_URL)
                .credentials("admin", "admin")
                .connect();
             SelectQueryResult r = conn.select(q).execute()) {
            while (r.hasNext()) r.next();
        }
    }

    /**
     * Size in bytes of the active audit-capability log. Returns 0 when
     * the file does not yet exist — the first row appended by the
     * NDJSON sink creates it.
     */
    static long auditLogSize() throws Exception {
        final Container.ExecResult res = CONTAINER.execInContainer(
                "sh", "-c",
                "if [ -f " + CAP_AUDIT_LOG + " ]; then stat -c %s " + CAP_AUDIT_LOG
                        + "; else echo 0; fi");
        final String stdout = res.getStdout() == null ? "0" : res.getStdout().trim();
        try {
            return Long.parseLong(stdout);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Read the audit-capability log from the given byte offset to
     * end-of-file. Returns the tail as a single string so tests can
     * grep for tokens. Empty string when the file does not exist or
     * has not grown.
     */
    static String readAuditLogTail(final long offset) throws Exception {
        final Container.ExecResult res = CONTAINER.execInContainer(
                "sh", "-c",
                "if [ -f " + CAP_AUDIT_LOG + " ]; then "
                        + "tail -c +" + (offset + 1) + " " + CAP_AUDIT_LOG
                        + "; fi");
        return res.getStdout() == null ? "" : res.getStdout();
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
