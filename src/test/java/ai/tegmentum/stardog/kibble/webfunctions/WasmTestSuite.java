package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.common.protocols.server.Server;
import com.complexible.common.protocols.server.ServerException;
import com.complexible.stardog.Stardog;
import com.complexible.stardog.StardogConfiguration;
import com.complexible.stardog.StardogException;
import com.complexible.stardog.api.Connection;
import com.complexible.stardog.api.admin.AdminConnection;
import com.complexible.stardog.api.admin.AdminConnectionConfiguration;
import com.google.common.io.Files;
import ai.tegmentum.stardog.kibble.webfunctions.*;
import ai.tegmentum.stardog.kibble.webfunctions.compose.TestComposedArtifactStore;
import ai.tegmentum.stardog.kibble.webfunctions.compose.TestComposeOrchestratorClient;
import ai.tegmentum.stardog.kibble.webfunctions.compose.TestComposePolicyStoreWriter;
import ai.tegmentum.stardog.kibble.webfunctions.compose.TestPlanV1Cbor;
import ai.tegmentum.stardog.kibble.webfunctions.compose.TestSha256UrlLoader;
import ai.tegmentum.stardog.kibble.webfunctions.compose.TestWebFunctionComposeService;
import junit.framework.TestCase;
import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;

@RunWith(Suite.class)
@SuiteClasses({
    TestAggregate.class,
    TestAuditRowSerialization.class,
    TestAuditSinkRingIntegration.class,
    TestCache.class,
    TestCall.class,
    TestCallbackContext.class,
    TestCallbackContextAsk.class,
    TestCapabilityAsk.class,
    TestCapabilityAskStore.class,
    TestCapabilityEnforcer.class,
    TestCapabilityGrant.class,
    TestCapabilityPolicyResolver.class,
    TestCapabilityPolicyStore.class,
    TestCapabilityVocabulary.class,
    // Compose Wave B unit tests — pure JVM, no Stardog needed but
    // discovered through the suite so `mvn test` runs them alongside
    // the rest of the plugin's tests.
    TestComposedArtifactStore.class,
    TestComposeOrchestratorClient.class,
    TestComposePolicyStoreWriter.class,
    TestPlanV1Cbor.class,
    TestSha256UrlLoader.class,
    TestWebFunctionComposeService.class,
    TestHostAllowlist.class,
    TestHostCallbacksInvokerSubject.class,
    TestHostCallbacksPhase5.class,
    // TestHostCallbacksSinkStubs removed in Wave B — the last stubbed
    // family (tracker-sink-callbacks) is now real; see
    // TestTrackerSinkCallbacks for round-trip coverage.
    TestHostCallbacksWarnOnUndeclared.class,
    TestHostCallbacksWasmDispatch.class,
    TestHttpPathAllowlist.class,
    TestMemoize.class,
    TestMethodPolicy.class,
    TestNdjsonRotatingFileAuditSink.class,
    TestPluginVersion.class,
    TestPolicyTriples.class,
    // Wave A - sink registry + real impls for sink-callbacks /
    // sink-query-callbacks / document-sink-callbacks.
    // Wave D upgraded execute-sink-select from a targeted backend-
    // error stub to a real per-invocation MemoryStore + SPARQL
    // evaluator; TestSinkMarshallers covers the WIT<->rdf4j value /
    // quad / binding conversion round-trips, TestSinkSparqlEngine
    // covers the engine's lifecycle + error taxonomy.
    TestSinkRegistry.class,
    TestSinkCallbacks.class,
    TestSinkMarshallers.class,
    TestSinkSparqlEngine.class,
    TestSinkQueryCallbacks.class,
    TestDocumentSinkCallbacks.class,
    // Wave C - fulltext registry + real impls for
    // fulltext-callbacks (insert-documents / delete-documents /
    // search-index) over the in-memory InMemoryFulltextRegistry.
    TestInMemoryFulltextRegistry.class,
    TestFulltextCallbacks.class,
    // Wave B - SQLite JDBC-backed tracker-sink registry + real impls
    // for tracker-sink-callbacks (register-tracker-tables,
    // tracker-insert / upsert / select / delete / count). Pure JVM,
    // no wasm engine or Stardog required — SqliteTrackerBackend uses
    // a per-test SQLite file (TemporaryFolder) for isolation.
    TestTrackerSchema.class,
    TestTrackerValueMarshaller.class,
    TestTrackerWhere.class,
    TestSqliteTrackerBackend.class,
    TestTrackerSinkCallbacks.class,
    TestToUpper.class,
    TestWasmCalleeAllowlist.class,
    TestWasmCustomSectionReader.class,
    TestWebFunctionCapability.class,
    TestWebFunctionConfig.class,
    TestWfCapabilityError.class,
})

public class WasmTestSuite extends TestCase {

	private static Stardog STARDOG;
	private static Server SERVER;
	public static final String DB = "test";
	public static final int TEST_PORT = 5888;
	protected Connection connection;
    private static final String STARDOG_LICENSE_PATH = System.getenv("STARDOG_LICENSE_PATH");

    @BeforeClass
    public static void beforeClass() throws IOException, ServerException {

        Process process = new ProcessBuilder("cargo", "make", "build").directory(new File("./src/test/rust")).start();
        System.out.println(IOUtils.toString(process.getErrorStream(), Charset.defaultCharset()));
        System.out.println(IOUtils.toString(process.getErrorStream(), Charset.defaultCharset()));

        final String serverUrl = "http://localhost:" + TEST_PORT;

        try{
            AdminConnectionConfiguration.toServer(serverUrl)
                    .credentials("admin", "admin")
                    .connect();
        } catch(StardogException e) {


            final File TEST_HOME;

            TEST_HOME = Files.createTempDir();
            TEST_HOME.deleteOnExit();

            try {
                STARDOG = Stardog.builder()
                        .set(StardogConfiguration.LICENSE_LOCATION, STARDOG_LICENSE_PATH)
                        .home(TEST_HOME).create();
            } catch (IllegalStateException nativeMismatch) {
                // Skip when the installed Stardog native library doesn't match
                // this build's Java deps — use the Testcontainers-based
                // WasmTestSuiteIT (mvn verify) when Docker is available.
                org.junit.Assume.assumeNoException(
                        "Skipping embedded Stardog suite: " + nativeMismatch.getMessage()
                                + " — run `mvn verify` for the Testcontainers IT instead.",
                        nativeMismatch);
                return;
            }

            SERVER = STARDOG.newServer()
                    .bind(new InetSocketAddress("localhost", TEST_PORT))
                    .start();

            final AdminConnection adminConnection = AdminConnectionConfiguration.toServer(serverUrl)
                    .credentials("admin", "admin")
                    .connect();

            if (adminConnection.list().contains(DB)) {
                adminConnection.drop(DB);
            }

            adminConnection.newDatabase(DB).create();
        }
    }

    @AfterClass
    public static void afterClass() {
        if (SERVER != null) {
            SERVER.stop();
        }
        if (STARDOG != null) {
            STARDOG.shutdown();
        }
    }
}
