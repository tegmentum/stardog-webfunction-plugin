package ai.tegmentum.stardog.kibble.webfunctions.compose;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Bootstrap loader for the compose orchestrator wasm.
 *
 * <p>Extracts {@code webfunctions/compose_orchestrator.wasm} from the
 * plugin classpath into {@code ${stardog.home}/webfunctions-compose/} on
 * first use and provisions the three preopen directories the composed
 * orchestrator's WASI Preview 2 imports require ({@code /blobs},
 * {@code /emit-cache}, {@code /trust}).
 *
 * <p>Uses the same {@code stardog.home} idiom as
 * {@link ai.tegmentum.stardog.kibble.webfunctions.WebFunctionConfig#auditDiskDirectory()}:
 * falls back to {@code ${java.io.tmpdir}/webfunctions-compose} when
 * {@code stardog.home} is unset (unit tests, embedded direct-instantiation).
 *
 * <p>Idempotent: repeat {@link #ensureExtracted()} calls short-circuit
 * once the orchestrator wasm and preopen directories are on disk. Callers
 * that want to re-extract (e.g. plugin upgrade in place) delete the
 * extracted wasm first and call {@link #ensureExtracted()} again.
 */
public class ComposeOrchestratorLoader {

    /** Relative path of the bundled orchestrator wasm inside the plugin jar. */
    public static final String CLASSPATH_RESOURCE =
            "webfunctions/compose_orchestrator.wasm";

    /** Sub-directory under {@code stardog.home} that owns the compose runtime state. */
    public static final String COMPOSE_DIR = "webfunctions-compose";

    /** Extracted orchestrator wasm filename inside {@link #COMPOSE_DIR}. */
    public static final String ORCHESTRATOR_WASM = "orchestrator.wasm";

    /** WASI preopen sub-directory for input blob storage. */
    public static final String BLOBS_SUBDIR = "blobs";

    /** WASI preopen sub-directory for emit-side scratch/cache. */
    public static final String CACHE_SUBDIR = "cache";

    /** WASI preopen sub-directory for trust-store material. */
    public static final String TRUST_SUBDIR = "trust";

    private final Path rootDir;
    private final Path orchestratorWasm;
    private final Path blobsDir;
    private final Path cacheDir;
    private final Path trustDir;

    private volatile boolean extracted;

    /**
     * Construct a loader rooted at the given base directory. Callers that
     * want the default {@code stardog.home}-driven path should use
     * {@link #forDefaultRoot()}.
     */
    public ComposeOrchestratorLoader(final Path baseDir) {
        this.rootDir = baseDir.toAbsolutePath().normalize();
        this.orchestratorWasm = rootDir.resolve(ORCHESTRATOR_WASM);
        this.blobsDir = rootDir.resolve(BLOBS_SUBDIR);
        this.cacheDir = rootDir.resolve(CACHE_SUBDIR);
        this.trustDir = rootDir.resolve(TRUST_SUBDIR);
    }

    /**
     * Loader rooted at the platform-default compose directory:
     * {@code ${stardog.home}/webfunctions-compose} when
     * {@code stardog.home} is set, otherwise
     * {@code ${java.io.tmpdir}/webfunctions-compose}.
     */
    public static ComposeOrchestratorLoader forDefaultRoot() {
        return new ComposeOrchestratorLoader(defaultRootDir());
    }

    /**
     * Resolve the default compose root directory using the same fallback
     * shape as
     * {@link ai.tegmentum.stardog.kibble.webfunctions.WebFunctionConfig#auditDiskDirectory()}.
     */
    public static Path defaultRootDir() {
        final String home = System.getProperty("stardog.home");
        if (home != null && !home.isEmpty()) {
            return Paths.get(home, COMPOSE_DIR);
        }
        final String tmp = System.getProperty("java.io.tmpdir", "/tmp");
        return Paths.get(tmp, COMPOSE_DIR);
    }

    /**
     * Extract the orchestrator wasm out of the classpath (if not already
     * on disk) and materialize the three preopen directories. Idempotent
     * — repeat calls after the first no-op.
     */
    public synchronized void ensureExtracted() throws IOException {
        if (extracted) return;
        Files.createDirectories(rootDir);
        Files.createDirectories(blobsDir);
        Files.createDirectories(cacheDir);
        Files.createDirectories(trustDir);
        if (!Files.exists(orchestratorWasm)) {
            extractOrchestratorWasm();
        }
        extracted = true;
    }

    private void extractOrchestratorWasm() throws IOException {
        try (InputStream in = classpathResource()) {
            if (in == null) {
                throw new IOException("compose orchestrator wasm missing from classpath: "
                        + CLASSPATH_RESOURCE);
            }
            final Path temp = Files.createTempFile(rootDir, "orchestrator-", ".wasm.part");
            try {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, orchestratorWasm,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                try { Files.deleteIfExists(temp); } catch (IOException ignore) {}
                throw e;
            }
        }
    }

    private InputStream classpathResource() {
        final ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream in = cl != null ? cl.getResourceAsStream(CLASSPATH_RESOURCE) : null;
        if (in != null) return in;
        return ComposeOrchestratorLoader.class.getClassLoader()
                .getResourceAsStream(CLASSPATH_RESOURCE);
    }

    public Path rootDir() {
        return rootDir;
    }

    public Path orchestratorWasm() {
        return orchestratorWasm;
    }

    public Path blobsDir() {
        return blobsDir;
    }

    public Path cacheDir() {
        return cacheDir;
    }

    public Path trustDir() {
        return trustDir;
    }
}
