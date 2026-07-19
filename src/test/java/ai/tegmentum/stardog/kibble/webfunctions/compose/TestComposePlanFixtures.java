package ai.tegmentum.stardog.kibble.webfunctions.compose;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * Test-only plan fixture builder used by
 * {@link ComposeIntegrationIT}. Hand-crafts minimal and fuller
 * {@link PlanV1} instances plus stages their component bytes into the
 * orchestrator's {@code /blobs} preopen following the upstream
 * {@code FsBlobStore} on-disk convention.
 *
 * <p>Blob layout mirrors {@code compose_core::blobs::FsBlobStore::digest_path}:
 * {@code <blobs-root>/<hex[0:2]>/<hex[2:]>} (sharded by the first two hex
 * chars, no file extension). The orchestrator wasm looks up components by
 * digest via that layout when it validates a plan on
 * {@code sys:compose/emit#compose}.
 *
 * <p>Not part of the shipped plugin — kept alongside the compose ITs so
 * the fixture wiring lives next to the tests that consume it.
 */
final class TestComposePlanFixtures {

    private TestComposePlanFixtures() {}

    /** SHA-256 of {@code content}. */
    static byte[] sha256(final byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in JCE", e);
        }
    }

    /** Lowercase hexadecimal encoding of {@code bytes}. */
    static String toHex(final byte[] bytes) {
        final char[] hex = "0123456789abcdef".toCharArray();
        final char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            final int v = bytes[i] & 0xFF;
            out[i * 2]     = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0F];
        }
        return new String(out);
    }

    /**
     * Stage a component wasm blob into the orchestrator's blob store at
     * the sharded content-addressed path the orchestrator's
     * {@code FsBlobStore} expects.
     *
     * <p>The blobs root is the same {@code loader.blobsDir()} the
     * orchestrator preopens at guest path {@code /blobs} via
     * {@link ComposeOrchestratorInstance}.
     *
     * @return the raw 32-byte SHA-256 digest — the same value the
     *         plan's {@link PlanV1.ComponentSpec#digest()} field carries.
     */
    static byte[] stageBlob(final Path blobsRoot, final byte[] bytes) throws IOException {
        final byte[] digest = sha256(bytes);
        final String hex = toHex(digest);
        final Path shardDir = blobsRoot.resolve(hex.substring(0, 2));
        Files.createDirectories(shardDir);
        Files.write(shardDir.resolve(hex.substring(2)), bytes);
        return digest;
    }

    /**
     * Minimal PlanV1 fixture — one component, no bindings, no secrets,
     * default policy, static linkage, empty explicit-exports. Designed
     * to exercise the shortest possible path through the orchestrator's
     * deserialize + compose surface.
     */
    static PlanV1 minimalPlan(final String rootId, final byte[] componentDigest) {
        final PlanV1.ComponentSpec spec = new PlanV1.ComponentSpec(
                rootId, componentDigest, Optional.empty());
        return PlanV1.builder()
                .version("v1")
                .root(rootId)
                .addComponent(spec)
                .build();
    }

    /**
     * Fuller PlanV1 fixture — same single component, but with a non-empty
     * policy (deterministic + tenant tag). Used for tests that need a
     * plan-shape distinguishable from {@link #minimalPlan} so a repeat
     * composition of the two produces distinct content-addressed
     * artifact URLs.
     */
    static PlanV1 fullerPlan(final String rootId, final byte[] componentDigest,
                             final String tenantTag) {
        final PlanV1.ComponentSpec spec = new PlanV1.ComponentSpec(
                rootId, componentDigest, Optional.of("test://" + tenantTag));
        final PlanV1.Policy policy = new PlanV1.Policy(
                PlanV1.DeterminismMode.STRICT,
                java.util.Collections.emptyList(),
                Optional.of(tenantTag),
                PlanV1.ResourceLimits.empty());
        return PlanV1.builder()
                .version("v1")
                .root(rootId)
                .addComponent(spec)
                .policy(policy)
                .build();
    }
}
