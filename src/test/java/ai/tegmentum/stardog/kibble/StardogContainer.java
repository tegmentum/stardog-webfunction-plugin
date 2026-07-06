package ai.tegmentum.stardog.kibble;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.time.Duration;

/**
 * Testcontainers wrapper for Stardog. Boots {@code stardog/stardog} at the
 * requested version, mounts the license file from {@code $STARDOG_LICENSE_PATH},
 * and installs the shaded plugin JAR from {@code target/} into Stardog's
 * extension directory so {@code wf:call} and friends are registered inside the
 * running server.
 *
 * <p>Meant for {@code *IT.java} tests run under maven-failsafe-plugin in the
 * {@code integration-test} phase, so the shaded JAR exists at test time.
 */
public final class StardogContainer extends GenericContainer<StardogContainer> {

    public static final String DEFAULT_IMAGE = "stardog/stardog:12.1.1";
    public static final int STARDOG_PORT = 5820;

    private static final String CONTAINER_LICENSE_PATH = "/var/opt/stardog/stardog-license-key.bin";
    private static final String CONTAINER_EXT_DIR      = "/var/opt/stardog/.ext";

    public StardogContainer() {
        this(DEFAULT_IMAGE);
    }

    public StardogContainer(final String image) {
        super(image);
        addExposedPort(STARDOG_PORT);
        withEnv("STARDOG_SERVER_JAVA_ARGS", "-Xmx1g");
        // Ready when the admin HTTP endpoint returns 200.
        waitingFor(Wait.forHttp("/admin/alive")
                .forPort(STARDOG_PORT)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(3)));
        // Stardog only publishes linux/amd64 images. Apple-silicon hosts need
        // DOCKER_DEFAULT_PLATFORM=linux/amd64 in the surrounding shell, or
        // Docker Desktop's Rosetta translation enabled.
    }

    /**
     * Mount the license file into the container. Reads path from the given
     * host location; defaults to {@code $STARDOG_LICENSE_PATH}.
     */
    public StardogContainer withLicense(final String hostPath) {
        if (hostPath == null || hostPath.isEmpty()) {
            throw new IllegalArgumentException("license path is required");
        }
        final File license = new File(hostPath);
        if (!license.exists()) {
            throw new IllegalStateException("license file not found: " + hostPath);
        }
        withCopyFileToContainer(MountableFile.forHostPath(license.getAbsolutePath()),
                CONTAINER_LICENSE_PATH);
        return this;
    }

    public StardogContainer withLicenseFromEnv() {
        return withLicense(System.getenv("STARDOG_LICENSE_PATH"));
    }

    /**
     * Mount a plugin JAR into Stardog's extension directory. Pass the path to
     * the shaded plugin JAR built by {@code mvn package}.
     */
    public StardogContainer withPluginJar(final String hostJarPath) {
        final File jar = new File(hostJarPath);
        if (!jar.exists()) {
            throw new IllegalStateException(
                    "plugin JAR not found (run `mvn package` first): " + hostJarPath);
        }
        withCopyFileToContainer(MountableFile.forHostPath(jar.getAbsolutePath()),
                CONTAINER_EXT_DIR + "/" + jar.getName());
        return this;
    }

    /**
     * Mount a wasm file into the container so {@code wf:call(<file://…>, …)}
     * inside SPARQL can resolve it. Returns the URL string usable inside SPARQL
     * (a {@code file://} URL pointing at the in-container location).
     */
    public String withWasm(final String hostPath, final String containerPath) {
        final File wasm = new File(hostPath);
        if (!wasm.exists()) {
            throw new IllegalStateException("wasm not found: " + hostPath);
        }
        withCopyFileToContainer(MountableFile.forHostPath(wasm.getAbsolutePath()), containerPath);
        return "file://" + containerPath;
    }

    /**
     * Public HTTP endpoint of the running server (host + mapped port).
     */
    public String getServerUrl() {
        return "http://" + getHost() + ":" + getMappedPort(STARDOG_PORT);
    }
}
