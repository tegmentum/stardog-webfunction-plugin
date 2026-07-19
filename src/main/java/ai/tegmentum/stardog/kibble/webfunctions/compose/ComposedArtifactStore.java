package ai.tegmentum.stardog.kibble.webfunctions.compose;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * Content-addressed persistence layer for composed wasm artifacts.
 *
 * <p>Each composed wasm blob is stored under a directory rooted at
 * {@code ${stardog.home}/webfunctions-compose/artifacts/}. The blob's
 * filename is its SHA-256 digest in lowercase hexadecimal, with the
 * {@code .wasm} suffix — so the on-disk layout is content-addressed
 * and de-duplicates repeat compositions of the same plan for free.
 *
 * <p>Canonical CID form: {@code sha256:<hex>}. See {@link #cidFor(byte[])}.
 * The plugin's URL scheme is {@code sha256://<hex>}, which the loader
 * layer resolves back through {@link #load(String)} on grant-permitted
 * capability check. See {@link Sha256ArtifactUrlHandler}.
 *
 * <p>{@link #persist(byte[])} is idempotent and safe against concurrent
 * writers: it writes to a temp file in the artifacts directory and
 * uses an atomic-move to place the final file, so a partially-written
 * blob is never observable.
 */
public final class ComposedArtifactStore {

    public static final String ARTIFACTS_SUBDIR = "artifacts";
    private static final String CID_PREFIX = "sha256:";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final Path artifactsDir;

    public ComposedArtifactStore(final Path artifactsDir) {
        this.artifactsDir = artifactsDir.toAbsolutePath().normalize();
    }

    /**
     * Build a store rooted under the loader's compose directory —
     * {@code <loader-root>/artifacts}.
     */
    public static ComposedArtifactStore forLoader(final ComposeOrchestratorLoader loader) {
        return new ComposedArtifactStore(loader.rootDir().resolve(ARTIFACTS_SUBDIR));
    }

    /**
     * Compute the SHA-256 digest of {@code content} and return its
     * canonical CID form ({@code sha256:<hex>}).
     */
    public static String cidFor(final byte[] content) {
        return CID_PREFIX + toHex(sha256(content));
    }

    /**
     * Lowercase hexadecimal SHA-256 (no prefix).
     */
    public static String hexDigestFor(final byte[] content) {
        return toHex(sha256(content));
    }

    /**
     * Persist {@code content} under its content-addressed filename and
     * return the canonical CID.
     */
    public String persist(final byte[] content) throws IOException {
        final byte[] digest = sha256(content);
        final String hex = toHex(digest);
        Files.createDirectories(artifactsDir);
        final Path target = artifactsDir.resolve(hex + ".wasm");
        if (Files.exists(target)) {
            // Content-addressed: identical content → identical filename.
            // Skip re-write to keep the blob store append-only.
            return CID_PREFIX + hex;
        }
        final Path temp = Files.createTempFile(artifactsDir, hex + "-", ".wasm.part");
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(temp); } catch (IOException ignore) {}
            throw e;
        }
        return CID_PREFIX + hex;
    }

    /**
     * Load a previously-persisted artifact by its CID. Accepts both
     * canonical {@code sha256:<hex>} and URL-scheme {@code sha256://<hex>}
     * forms so callers coming from either the resolver or the URL
     * handler share the same lookup surface.
     */
    public Optional<byte[]> load(final String cid) throws IOException {
        final String hex = normalize(cid);
        if (hex == null) return Optional.empty();
        final Path target = artifactsDir.resolve(hex + ".wasm");
        if (!Files.exists(target)) return Optional.empty();
        return Optional.of(Files.readAllBytes(target));
    }

    public Path artifactsDir() {
        return artifactsDir;
    }

    /**
     * Strip a URI form prefix and return the lowercase-hex portion,
     * or {@code null} if the input doesn't parse as a SHA-256 CID.
     */
    static String normalize(final String cid) {
        if (cid == null) return null;
        // Match the URL-scheme form first — "sha256://" also starts
        // with the plain "sha256:" prefix, so ordering matters.
        final String hex;
        if (cid.startsWith("sha256://")) {
            hex = cid.substring("sha256://".length());
        } else if (cid.startsWith(CID_PREFIX)) {
            hex = cid.substring(CID_PREFIX.length());
        } else {
            return null;
        }
        if (hex.length() != 64) return null;
        for (int i = 0; i < hex.length(); i++) {
            final char c = hex.charAt(i);
            final boolean digit = c >= '0' && c <= '9';
            final boolean lower = c >= 'a' && c <= 'f';
            if (!digit && !lower) return null;
        }
        return hex;
    }

    private static byte[] sha256(final byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the JCE spec — any JVM missing it
            // is broken. Wrap in IllegalStateException so callers don't
            // need to declare a checked exception.
            throw new IllegalStateException("SHA-256 unavailable in JCE", e);
        }
    }

    private static String toHex(final byte[] bytes) {
        final char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            final int v = bytes[i] & 0xFF;
            out[i * 2]     = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
