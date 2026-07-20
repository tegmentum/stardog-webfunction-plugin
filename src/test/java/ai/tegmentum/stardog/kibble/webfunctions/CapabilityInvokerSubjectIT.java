package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.stardog.kibble.StardogContainer;
import ai.tegmentum.stardog.kibble.StardogUserProvisioner;

import com.complexible.stardog.api.Connection;
import com.complexible.stardog.api.ConnectionConfiguration;
import com.complexible.stardog.api.admin.AdminConnection;
import com.complexible.stardog.api.admin.AdminConnectionConfiguration;
import com.complexible.stardog.security.ActionType;
import com.complexible.stardog.security.SecurityOptions;
import com.stardog.stark.Value;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.Container;

import java.io.File;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * I4 — Phase 4 invoker-subject execution end-to-end. Two authenticated
 * Stardog users (Alice + Bob) invoke the same web-function extension
 * whose runtime dispatch reaches {@code graph-callbacks/execute-query}
 * against the same database, but only Alice holds a named-graph read
 * permission on the graph the extension queries. The assertion is that
 * Stardog's own {@link SecurityOptions#NAMED_GRAPH_SECURITY_ENABLED}
 * ACL check fires under the invoker's subject — Alice sees the fixture
 * triples, Bob sees nothing — and both invocations land audit rows
 * carrying the correct Shiro principal.
 *
 * <p>Exercises {@link HostCallbacks#executeAsInvoker}'s
 * {@code ShiroUtils.executeAs(invokerSubject, body)} wrap: without it,
 * the inner {@code executeSelect} would run under the plugin's ambient
 * credential (admin) and Bob would inherit admin's cross-graph read,
 * masking the ACL enforcement the capability wave promises. Unit-level
 * coverage lives in {@link TestHostCallbacksInvokerSubject}
 * (Shiro-proxy subject) and {@link TestCapabilityEnforcer}
 * (grant → dispatch); this IT is the container-level proof that the
 * wire holds all the way through a real HTTP-authenticated wf:call.
 *
 * <p>Container config:
 * <ul>
 *   <li>{@code webfunctions.capability.enabled=true} — master gate on so
 *       {@link CapabilityEnforcer#activePolicy} returns present and the
 *       invoker-subject wrap actually fires.</li>
 *   <li>{@code webfunctions.capability.unknown-extension-policy=deny} —
 *       forces the deny branch on a mis-provisioning bug rather than
 *       silently permitting.</li>
 *   <li>{@code webfunctions.capability.anonymous-policy=permit} — Alice
 *       and Bob are authenticated by basic auth on every SPARQL
 *       connection, so anonymous handling only affects a misconfiguration
 *       path; permit is the least-surprising default.</li>
 *   <li>Per-row-fsync disk audit at {@link #AUDIT_DIR} so
 *       {@link #readAuditLog} can read committed rows synchronously
 *       after the invocation returns.</li>
 * </ul>
 *
 * <p>Skipped unless Docker + STARDOG_LICENSE_PATH + shaded plugin JAR +
 * graph-callback fixture wasm are all present.
 */
public class CapabilityInvokerSubjectIT {

    private static final String DB = "test_cap_invoker";
    private static final String LICENSE_PATH = System.getenv("STARDOG_LICENSE_PATH");
    private static final String PLUGIN_JAR = System.getProperty("wf.plugin.jar",
            "target/tegmentum-stardog-webfunction-1.0.3.jar");
    // Fixture without an embedded ask so the audit log carries only the
    // GRANTED rows the test asserts on — no incidental
    // GRANTED_UNDECLARED noise from an understated ask (that's the
    // subject of CapabilityAskIT's I7).
    private static final String FIXTURE_WASM =
            "src/test/resources/integration/example_graph_callback_extension.wasm";
    private static final String C_GRAPH_CB =
            "/opt/wasm/example_graph_callback_extension.wasm";

    // Users under test. Passwords are the same as usernames — trivial
    // for the container-local ITs; never used outside the ephemeral
    // testcontainer.
    private static final String USER_ALICE = "alice";
    private static final String USER_BOB   = "bob";

    // Named graph that carries the fixture data. Alice has cap read,
    // Bob does not — the whole point of the test.
    private static final String GRAPH_G1  = "http://example.org/g1";
    // Root subject the guest's SPARQL query pivots on
    // (`SELECT ?neighbor WHERE { <root> ?p ?neighbor }`).
    private static final String ROOT_IRI  = "http://example.org/root";
    private static final String N1_IRI    = "http://example.org/n1";
    private static final String P_IRI     = "http://example.org/p";

    // Distinct audit dir per class so bleed-through between IT classes
    // (which run sequentially under failsafe but share the same host
    // volume namespace for `/var/opt/stardog`) doesn't confuse
    // `readAuditLog`.
    static final String AUDIT_DIR = "/var/opt/stardog/wf-audit-invoker";
    static final String CAP_AUDIT_LOG = AUDIT_DIR + "/audit-capability.log";

    static StardogContainer CONTAINER;
    static String WASM_URL;
    static String SERVER_URL;

    @BeforeClass
    public static void bootContainer() throws Exception {
        assumeTrue("Docker not available", isDockerAvailable());
        assumeTrue("STARDOG_LICENSE_PATH not set",
                LICENSE_PATH != null && !LICENSE_PATH.isEmpty());
        assumeTrue("plugin JAR not built: " + PLUGIN_JAR + " (run `mvn package`)",
                new File(PLUGIN_JAR).exists());
        assumeTrue("graph-callback fixture missing: " + FIXTURE_WASM,
                new File(FIXTURE_WASM).exists());

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
        WASM_URL = CONTAINER.withWasm(FIXTURE_WASM, C_GRAPH_CB);
        CONTAINER.start();
        SERVER_URL = CONTAINER.getServerUrl();

        // chmod the audit dir so the NDJSON sink can create files
        // regardless of whether the Stardog process runs as root
        // (mirrors the pattern from CapabilityAskIT).
        CONTAINER.execInContainer("mkdir", "-p", AUDIT_DIR);
        CONTAINER.execInContainer("chmod", "777", AUDIT_DIR);

        // Create the test DB with named-graph security ON — the
        // whole invoker-subject enforcement premise depends on
        // Stardog's ACL machinery consulting per-graph permissions,
        // which only happens under this flag. Also enable it as a
        // db-level property (not just server-wide) so we don't need
        // to reconfigure the JVM args in the container.
        try (AdminConnection admin = AdminConnectionConfiguration.toServer(SERVER_URL)
                .credentials("admin", "admin").connect()) {
            if (!admin.list().contains(DB)) {
                admin.newDatabase(DB)
                        .set(SecurityOptions.NAMED_GRAPH_SECURITY_ENABLED, true)
                        .create();
            }
        }
        CapabilityPolicyDbHelpers.ensurePolicyDb(SERVER_URL);

        // Provision the multi-user shape.
        final StardogUserProvisioner prov = new StardogUserProvisioner(SERVER_URL);
        prov.addUser(USER_ALICE, USER_ALICE);
        prov.addUser(USER_BOB,   USER_BOB);
        prov.grantDatabaseRead(USER_ALICE, DB);
        prov.grantDatabaseRead(USER_BOB,   DB);
        // Only Alice can read graph G1. Bob has DB read (so his
        // wf:call opens the connection) but NO graph read, so the
        // inner execute-query returns nothing to him under the
        // invoker-subject wrap.
        prov.grantNamedGraphRead(USER_ALICE, DB, GRAPH_G1);
        // Both users need the Shiro-level web-function-callback
        // permission so the CapabilityEnforcer's Shiro check
        // (perCallback step 3) clears. The verb aligns with
        // ActionType.EXECUTE so the grant goes through the Java
        // PermissionManager rather than any HTTP admin fallback —
        // WebFunctionCallbackResourceType is a plugin-registered
        // SecurityResourceType, and Permission's constructor accepts
        // that interface directly.
        prov.grantUserPermission(USER_ALICE, ActionType.EXECUTE,
                WebFunctionCallbackResourceType.INSTANCE,
                "graph-callbacks/execute-query");
        prov.grantUserPermission(USER_BOB, ActionType.EXECUTE,
                WebFunctionCallbackResourceType.INSTANCE,
                "graph-callbacks/execute-query");
        // Alice and Bob also need EXECUTE on the wasm URL itself —
        // Stardog checks WebFunctionResourceType at load time.
        prov.grantUserPermission(USER_ALICE, ActionType.EXECUTE,
                WebFunctionResourceType.INSTANCE, WASM_URL);
        prov.grantUserPermission(USER_BOB, ActionType.EXECUTE,
                WebFunctionResourceType.INSTANCE, WASM_URL);

        // Seed the fixture triple in G1. Uses the admin credential so
        // the seed does not depend on the users-under-test's own
        // write access — the whole point of the test is asymmetric
        // read.
        prov.insertIntoNamedGraph(DB, GRAPH_G1,
                "<" + ROOT_IRI + "> <" + P_IRI + "> <" + N1_IRI + "> .");

        // Grant the extension the graph-callbacks + execute-query
        // capability in the plugin's policy DB — otherwise
        // CapabilityEnforcer.perCallback rejects the dispatch on the
        // method-policy axis before the Shiro check even runs.
        CapabilityPolicyDbHelpers.grantInterfaces(SERVER_URL, WASM_URL,
                java.util.List.of(CapabilityVocabulary.IFACE_GRAPH_CALLBACKS),
                java.util.List.of("graph-callbacks/execute-query"));
    }

    @AfterClass
    public static void tearDown() {
        if (CONTAINER != null) CONTAINER.stop();
    }

    // Vocabulary URI inlined here for the same shading-avoidance reason
    // documented in CapabilityDisabledIT — the shade plugin strips
    // semver4j from the dependency-reduced-pom, so referencing
    // WebFunctionVocabulary from the failsafe classpath NoClassDefFound-s.
    private static final String WF_NAMESPACE =
            "http://semantalytics.com/2021/03/ns/stardog/webfunction/latest/";

    @Test
    public void aliceReadsG1BobDoesNotUnderInvokerSubject() throws Exception {
        // Anchor the audit-log grep so we only inspect rows this
        // test wrote — the on-disk NDJSON accumulates across the
        // whole class run.
        final long preSize = auditLogSize();

        // ---- Alice — should see the neighbour in G1. ---------------
        final String aliceResult = invokeExpandNeighborhood(USER_ALICE);
        assertThat(aliceResult)
                .as("Alice has cap read on G1 → guest SELECT should see the fixture neighbour")
                .contains(N1_IRI);

        // ---- Bob — should NOT see the neighbour in G1. -------------
        // Two acceptable shapes:
        //   1. Guest returns empty string / no binding — Stardog
        //      filtered G1 out for Bob's subject.
        //   2. wf:call throws — inner execute-query hit an
        //      authorization exception the guest surfaced as an
        //      error result which the outer BIND unwound as a
        //      StardogException.
        // The invariant is that Bob's response MUST NOT contain
        // N1_IRI — that would prove the invoker-subject wrap was
        // bypassed and Bob inherited the plugin's ambient credential.
        String bobResult;
        try {
            bobResult = invokeExpandNeighborhood(USER_BOB);
        } catch (RuntimeException expected) {
            // Some Stardog + wasm error surfaces materialise as a
            // client-side exception with the error string in the
            // message. Compact it into a stable string for the
            // no-leak assertion.
            bobResult = expected.getMessage() == null ? "" : expected.getMessage();
        }
        assertThat(bobResult)
                .as("Bob has no cap read on G1 → guest SELECT must not surface the G1 triple")
                .doesNotContain(N1_IRI);

        // ---- Audit — both invocations landed rows with the correct
        // Shiro principal per row. Read the tail of the NDJSON log
        // written since preSize and grep for {"userId":"alice"} +
        // {"userId":"bob"}. The plugin writes at least two
        // capability rows per invocation:
        //   • one GRANTED for the instantiation-time preInvocation
        //     (interfaceName="capability", method="instantiation")
        //   • one GRANTED (or GRANTED_UNDECLARED) for
        //     graph-callbacks/execute-query on the callback dispatch
        // Grepping the principal is enough evidence that the audit
        // ring attributed the invocation correctly. Row-count
        // assertions would over-constrain the test to a specific
        // dispatch-count contract downstream refactors might change.
        final String tail = readAuditLogTail(preSize);
        assertThat(tail)
                .as("audit log should carry a row attributed to Alice's principal")
                .contains("\"userId\":\"" + USER_ALICE + "\"");
        assertThat(tail)
                .as("audit log should carry a row attributed to Bob's principal")
                .contains("\"userId\":\"" + USER_BOB + "\"");
        // Extension URL + interface + method should appear on the
        // per-callback GRANTED row for at least one of the invocations —
        // proves the plugin reached the callback dispatch site, not
        // just the outer instantiation row.
        assertThat(tail)
                .as("audit log should record the extension URL on the per-callback row")
                .contains(WASM_URL);
        assertThat(tail)
                .as("audit log should record the graph-callbacks interface")
                .contains("\"interfaceName\":\"graph-callbacks\"");
        assertThat(tail)
                .as("audit log should record the execute-query method")
                .contains("\"method\":\"execute-query\"");
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * Drive the graph_callback extension's {@code expand-neighborhood}
     * function once as the given user via basic auth. Returns the
     * guest's response as a plain string (empty when the extension
     * produced no neighbours), extracted from the {@code ?result}
     * binding.
     *
     * <p>Runtime shape: extension invokes
     * {@code tegmentum:webfunction/graph-callbacks@0.1.0#execute-query}
     * internally with SPARQL {@code "SELECT ?neighbor WHERE { <ROOT> ?p ?neighbor }"};
     * the guest concatenates the results into a comma-separated string.
     */
    private static String invokeExpandNeighborhood(final String user) {
        final String query =
                "PREFIX wf: <" + WF_NAMESPACE + ">\n"
              + "PREFIX ex: <http://example.org/>\n"
              + "SELECT ?result WHERE {\n"
              + "  BIND(wf:call(str(<" + WASM_URL + ">), \"expand-neighborhood\","
              + " <" + ROOT_IRI + ">) AS ?result)\n"
              + "}";
        try (Connection conn = ConnectionConfiguration.to(DB)
                .server(SERVER_URL)
                .credentials(user, user)
                .connect();
             SelectQueryResult r = conn.select(query).execute()) {
            if (!r.hasNext()) return "";
            final Optional<Value> v = r.next().value("result");
            return v.map(Value::toString).orElse("");
        }
    }

    /**
     * Size in bytes of the active audit-capability log. Returns 0 when
     * the file does not yet exist — first row appended creates it.
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
     * end-of-file. Empty string when the file does not exist or has not
     * grown since {@code offset}. Same pattern CapabilityAskIT.I7 uses.
     */
    static String readAuditLogTail(final long offset) throws Exception {
        final Container.ExecResult res = CONTAINER.execInContainer(
                "sh", "-c",
                "if [ -f " + CAP_AUDIT_LOG + " ]; then "
                        + "tail -c +" + (offset + 1) + " " + CAP_AUDIT_LOG
                        + "; fi");
        return res.getStdout() == null ? "" : res.getStdout();
    }

    /**
     * Debug helper — full audit log dump. Not used in the assertion
     * path; keep for triage under {@code mvn -X failsafe:integration-test}
     * when the test regresses under a Stardog upgrade or a rework of
     * the audit-row shape.
     */
    @SuppressWarnings("unused")
    static String readAuditLog() throws Exception {
        return readAuditLogTail(0L);
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
