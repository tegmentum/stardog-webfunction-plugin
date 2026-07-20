package ai.tegmentum.stardog.kibble.webfunctions.compose;

import ai.tegmentum.webassembly4j.api.ComponentInstance;
import ai.tegmentum.webassembly4j.api.Function;
import ai.tegmentum.webassembly4j.api.Global;
import ai.tegmentum.webassembly4j.api.Memory;
import ai.tegmentum.webassembly4j.api.Table;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ComposeAdmin}'s compose-flow URL emission.
 *
 * <p>Exercises both artifact-URL shapes:
 * <ul>
 *   <li>Default: {@code webfunctions.compose.artifact-url-prefix} unset
 *       → emitted URL is {@code sha256://<hex>} — the canonical local
 *       blob-store form.</li>
 *   <li>Configured: prefix set → emitted URL is {@code <prefix><hex>};
 *       persistence path is unchanged (bytes still land under the
 *       artifact store's hex-named file).</li>
 * </ul>
 *
 * <p>Uses a hand-crafted mock {@link ComponentInstance} — same pattern
 * as {@link TestComposeOrchestratorClient} — so no orchestrator wasm is
 * loaded from disk. The policy-store write side is stubbed via the
 * test-only {@link ComposeAdmin} constructor's {@code writeSink} arg —
 * lets us assert what turtle bytes and plan IRI would flow to the
 * writer without needing a live Kernel.
 */
public class TestComposeAdmin {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void defaultPrefixEmitsSha256UrlToTurtleAndResult() throws Exception {
        final byte[] composed = wasmHeader();
        final FakeInstance fake = new FakeInstance().wireComposeReturning(composed);
        final AtomicReference<String> capturedTurtleUrl = new AtomicReference<>();
        fake.turtleUrlCapture = capturedTurtleUrl;

        final ComposedArtifactStore store = new ComposedArtifactStore(tmp.newFolder().toPath());
        final AtomicReference<byte[]> writtenBytes = new AtomicReference<>();
        final AtomicReference<String> writtenPlanIri = new AtomicReference<>();

        final ComposeAdmin admin = new ComposeAdmin(
                new ComposeOrchestratorClient(new NoopOrchestratorInstance(fake)),
                store,
                null /* policyStoreWriter unused with a custom writeSink */,
                Optional::empty,
                (bytes, planIri) -> {
                    writtenBytes.set(bytes);
                    writtenPlanIri.set(planIri);
                });

        final ComposeAdmin.ComposedResult result = admin.composeFromCbor(new byte[]{0x01}, null);

        final String expectedHex = ComposedArtifactStore.hexDigestFor(composed);
        final String expectedUrl = "sha256://" + expectedHex;

        assertThat(result.artifactUrl())
                .as("default artifact URL should be sha256://<hex>")
                .isEqualTo(expectedUrl);
        assertThat(result.digestHex()).isEqualTo(expectedHex);
        assertThat(result.size()).isEqualTo(composed.length);
        assertThat(result.planIri()).isEqualTo("urn:composition:plan");

        // The orchestrator got the same URL to emit in the RDF —
        // proves the RDF-facing string matches the result surface.
        assertThat(capturedTurtleUrl.get())
                .as("planToTurtleWithArtifact should be handed the default sha256:// URL")
                .isEqualTo(expectedUrl);

        // Persistence unchanged: local blob store carries the composed
        // bytes under the content-addressed filename.
        assertThat(store.load(expectedUrl)).contains(composed);
        final Path artifactFile = store.artifactsDir().resolve(expectedHex + ".wasm");
        assertThat(artifactFile.toFile().exists())
                .as("persistence path unchanged — bytes should land at <hex>.wasm")
                .isTrue();

        // Writer received the effective plan IRI + turtle bytes.
        assertThat(writtenPlanIri.get()).isEqualTo("urn:composition:plan");
        assertThat(new String(writtenBytes.get(), StandardCharsets.UTF_8))
                .as("writer must receive the orchestrator's Turtle document")
                .isNotEmpty();
    }

    @Test
    public void configuredPrefixOverridesEmittedUrlKeepsPersistencePath() throws Exception {
        final byte[] composed = wasmHeader();
        final FakeInstance fake = new FakeInstance().wireComposeReturning(composed);
        final AtomicReference<String> capturedTurtleUrl = new AtomicReference<>();
        fake.turtleUrlCapture = capturedTurtleUrl;

        final ComposedArtifactStore store = new ComposedArtifactStore(tmp.newFolder().toPath());
        final String prefix = "https://cdn.example.com/artifacts/";

        final ComposeAdmin admin = new ComposeAdmin(
                new ComposeOrchestratorClient(new NoopOrchestratorInstance(fake)),
                store,
                null,
                () -> Optional.of(prefix),
                (bytes, planIri) -> { /* no-op */ });

        final ComposeAdmin.ComposedResult result =
                admin.composeFromCbor(new byte[]{0x02}, "urn:test:plan:override");

        final String expectedHex = ComposedArtifactStore.hexDigestFor(composed);
        final String expectedUrl = prefix + expectedHex;

        assertThat(result.artifactUrl())
                .as("configured prefix should override the emitted URL")
                .isEqualTo(expectedUrl);
        assertThat(result.digestHex()).isEqualTo(expectedHex);
        assertThat(result.planIri()).isEqualTo("urn:test:plan:override");

        // Orchestrator was handed the same overridden URL.
        assertThat(capturedTurtleUrl.get())
                .as("planToTurtleWithArtifact should be handed the configured URL")
                .isEqualTo(expectedUrl);

        // Persistence path is unchanged — the on-disk file is keyed on
        // the digest hex regardless of what URL the RDF surfaces.
        final Path artifactFile = store.artifactsDir().resolve(expectedHex + ".wasm");
        assertThat(artifactFile.toFile().exists())
                .as("persistence path must not change when prefix is configured")
                .isTrue();
        // The store's load surface still resolves the sha256:// form
        // for round-trip via the artifact URL handler — proves the
        // configured prefix is a purely RDF-facing rewrite.
        assertThat(store.load("sha256://" + expectedHex)).contains(composed);
    }

    @Test
    public void configuredPrefixWithSchemePrefixEmitsExactConcatenation() throws Exception {
        // Naive prefix without a trailing separator — the plugin does
        // no massage; the emitted URL is exact concatenation, so an
        // operator who wants a delimiter has to include one in the
        // config value. This test pins that behavior.
        final byte[] composed = wasmHeader();
        final FakeInstance fake = new FakeInstance().wireComposeReturning(composed);

        final ComposedArtifactStore store = new ComposedArtifactStore(tmp.newFolder().toPath());
        final String prefix = "ipfs://";

        final ComposeAdmin admin = new ComposeAdmin(
                new ComposeOrchestratorClient(new NoopOrchestratorInstance(fake)),
                store,
                null,
                () -> Optional.of(prefix),
                (bytes, planIri) -> { });

        final ComposeAdmin.ComposedResult result = admin.composeFromCbor(new byte[]{0x03}, null);
        assertThat(result.artifactUrl())
                .isEqualTo(prefix + ComposedArtifactStore.hexDigestFor(composed));
    }

    /**
     * Two-byte wasm module header (magic prefix only). Content-addressed
     * enough to produce a stable digest per test method without needing
     * a real module on disk.
     */
    private static byte[] wasmHeader() {
        return new byte[]{0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00};
    }

    /**
     * Adapts a fake ComponentInstance into a ComposeOrchestratorInstance
     * — same shape TestComposeOrchestratorClient's NoopOrchestratorInstance
     * uses. Duplicated here rather than sharing to keep TestComposeAdmin
     * standalone (the sibling test class is package-private).
     */
    private static final class NoopOrchestratorInstance extends ComposeOrchestratorInstance {
        private final ComponentInstance backing;

        NoopOrchestratorInstance(final ComponentInstance backing) {
            super(new NoopLoader());
            this.backing = backing;
        }

        @Override
        public ComponentInstance instance() {
            return backing;
        }
    }

    private static final class NoopLoader extends ComposeOrchestratorLoader {
        NoopLoader() {
            super(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                    "wf-noop-loader-composeadmin"));
        }
        @Override
        public synchronized void ensureExtracted() { /* no-op */ }
    }

    /**
     * Scripted ComponentInstance — canned responses per export, plus a
     * capture hook for the artifact URL that flows through
     * plan-to-turtle-with-artifact so tests can assert on the RDF-facing
     * URL without parsing the turtle body.
     */
    private static final class FakeInstance implements ComponentInstance {
        final Map<String, Object> responses = new HashMap<>();
        final List<String> callsInOrder = new ArrayList<>();
        AtomicReference<String> turtleUrlCapture;

        FakeInstance wireComposeReturning(final byte[] composed) {
            // Minimal wiring for the compose chain
            // (plan-deserialize → emit-compose → emit-get-artifact) plus
            // the plan-to-turtle-with-artifact export.
            responses.put(ComposeOrchestratorClient.EXPORT_PLAN_DESERIALIZE, "PLAN");
            final Map<String, Object> composeReply = new HashMap<>();
            final byte[] emitKey = new byte[]{0x00, 0x00, 0x01};
            composeReply.put("digest", emitKey);
            composeReply.put("size", (long) composed.length);
            composeReply.put("emit-key", emitKey);
            responses.put(ComposeOrchestratorClient.EXPORT_EMIT_COMPOSE, composeReply);
            responses.put(ComposeOrchestratorClient.EXPORT_EMIT_GET_ARTIFACT, composed);
            responses.put(ComposeOrchestratorClient.EXPORT_RDF_PLAN_TO_TURTLE_WA,
                    "@prefix comp: <http://tegmentum.ai/ns/composition/> . "
                    + "<urn:composition:plan> comp:version \"v1\" . ");
            return this;
        }

        @Override
        public Object invoke(final String functionName, final Object... args) {
            callsInOrder.add(functionName);
            if (ComposeOrchestratorClient.EXPORT_RDF_PLAN_TO_TURTLE_WA.equals(functionName)
                    && turtleUrlCapture != null && args.length >= 3) {
                // planCbor, planIri, artifactUrl, digestHex — capture arg[2].
                turtleUrlCapture.set((String) args[2]);
            }
            return responses.get(functionName);
        }

        @Override public Optional<Function> function(final String name) { return Optional.empty(); }
        @Override public Optional<Memory> memory(final String name) { return Optional.empty(); }
        @Override public Optional<Table> table(final String name) { return Optional.empty(); }
        @Override public Optional<Global> global(final String name) { return Optional.empty(); }
        @Override public <T> Optional<T> unwrap(final Class<T> nativeType) { return Optional.empty(); }
        @Override public boolean hasFunction(final String name) { return true; }
        @Override public List<String> exportedFunctions() { return Collections.emptyList(); }
        @Override public List<String> exportedInterfaces() { return Collections.emptyList(); }
        @Override public boolean exportsInterface(final String name) { return true; }
    }
}
