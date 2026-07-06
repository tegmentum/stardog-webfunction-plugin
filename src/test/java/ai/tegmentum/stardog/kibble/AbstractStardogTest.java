package ai.tegmentum.stardog.kibble;

import com.complexible.common.protocols.server.Server;
import com.complexible.common.protocols.server.ServerException;
import com.complexible.stardog.Stardog;
import com.complexible.stardog.StardogConfiguration;
import com.complexible.stardog.StardogException;
import com.complexible.stardog.StardogLicense;
import com.complexible.stardog.api.Connection;
import com.complexible.stardog.api.ConnectionConfiguration;
import com.complexible.stardog.api.admin.AdminConnection;
import com.complexible.stardog.api.admin.AdminConnectionConfiguration;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import io.undertow.server.handlers.cache.CachedHttpRequest;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.Charset;


public abstract class AbstractStardogTest {

    private static Stardog STARDOG;
    private static Server SERVER;
    private static final String DB = "test";
    private static int TEST_PORT = 5888;
    private static final String STARDOG_LICENSE_PATH = System.getenv("STARDOG_LICENSE_PATH");
    protected Connection connection;

    @BeforeClass
    public static void beforeClass() throws IOException, ServerException {

       final String serverUrl = "http://localhost:" + TEST_PORT;
       AdminConnection adminConnection;
       try{
           adminConnection = AdminConnectionConfiguration.toServer(serverUrl)
                .credentials("admin", "admin")
                .connect();
       } catch(StardogException e) {

           Process process = new ProcessBuilder("cargo", "make", "build").directory(new File("./src/test/rust")).start();
           System.out.println(IOUtils.toString(process.getErrorStream(), Charset.defaultCharset()));
           System.out.println(IOUtils.toString(process.getErrorStream(), Charset.defaultCharset()));

           final File TEST_HOME = Files.createTempDir();
        TEST_HOME.deleteOnExit();

        try {
            Files.copy(new File(Resources.getResource("log4j2-test.xml").toURI()), new File(TEST_HOME, "log4j2.xml"));
        } catch(URISyntaxException e1) {

        }

        try {
            STARDOG = Stardog.builder()
                             .set(StardogConfiguration.LICENSE_LOCATION, STARDOG_LICENSE_PATH)
                             .home(TEST_HOME).create();
        } catch (IllegalStateException nativeMismatch) {
            // Common failure on machines whose installed Stardog native library
            // (e.g. libStarrocks-*.dylib in $STARDOG/lib) doesn't match the
            // Stardog Java jars this build depends on. Skip so we don't fail
            // on environment drift — use the WasmTestSuiteIT Testcontainers
            // path if a Docker daemon is available instead.
            org.junit.Assume.assumeNoException(
                    "Skipping embedded Stardog tests: " + nativeMismatch.getMessage()
                            + " — run `mvn verify` for the Testcontainers-based IT instead.",
                    nativeMismatch);
        }

        SERVER = STARDOG.newServer()
                .bind(new InetSocketAddress("localhost", TEST_PORT)).start();

        adminConnection = AdminConnectionConfiguration.toServer(serverUrl)
                .credentials("admin", "admin")
                .connect();

        if (adminConnection.list().contains(DB)) {
            adminConnection.drop(DB);
        } else {
            adminConnection.newDatabase(DB).create();
        }
       }
    }

    @AfterClass
    public static void afterClass() {
        if (SERVER != null) {
            SERVER.stop();
            STARDOG.shutdown();
        }
    }

    @Before
    public void setUp() {
        connection = ConnectionConfiguration.to(DB)
                .server("http://localhost:" + TEST_PORT)
                .credentials("admin", "admin")
                .connect();
    }

    @After
    public void tearDown() {
        connection.close();
    }
}
