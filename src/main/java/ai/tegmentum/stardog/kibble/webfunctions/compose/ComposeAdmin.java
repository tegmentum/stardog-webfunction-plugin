package ai.tegmentum.stardog.kibble.webfunctions.compose;

import com.complexible.stardog.Kernel;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Admin-callable entry point for the compose orchestrator.
 *
 * <p>Bundles together the four Wave B moving parts:
 * <ol>
 *   <li>{@link ComposeOrchestratorClient} — invokes the composed
 *       orchestrator wasm's {@code sys:compose/emit#compose} and
 *       {@code sys:compose/rdf#plan-to-turtle} exports.</li>
 *   <li>{@link ComposedArtifactStore} — persists the composed wasm
 *       under its {@code sha256://<hex>} content-addressed URL.</li>
 *   <li>{@link ComposePolicyStoreWriter} — projects the Turtle from
 *       the orchestrator into the composition named graph in the
 *       plugin's capability database.</li>
 * </ol>
 *
 * <p>Guice-injectable ({@code @Singleton}) so the future
 * {@code stardog-admin} CLI subcommand can construct it through the
 * kernel-owned injector. Also constructable via {@link #wire(Kernel)}
 * for scripts and embedded callers.
 *
 * <p>Thread safety: safe for concurrent use — each call is
 * self-contained; the orchestrator instance's own concurrency guard
 * applies within {@link ComposeOrchestratorClient}.
 */
@Singleton
public final class ComposeAdmin {

    private final ComposeOrchestratorClient client;
    private final ComposedArtifactStore artifactStore;
    private final ComposePolicyStoreWriter policyStoreWriter;

    /**
     * Result of a single admin composition — carries the artifact URL
     * of the composed wasm, the size in bytes, and the plan IRI the
     * RDF was inserted under.
     */
    public static final class ComposedResult {
        private final String artifactUrl;
        private final long size;
        private final String planIri;

        public ComposedResult(final String artifactUrl, final long size, final String planIri) {
            this.artifactUrl = Objects.requireNonNull(artifactUrl, "artifactUrl");
            this.size = size;
            this.planIri = planIri;
        }

        /**
         * Canonical composed-artifact URL — {@code sha256://<hex>} by
         * default (the plugin's in-tree content-addressed blob store).
         * If the operator re-hosts the artifact elsewhere they can
         * publish a different URL scheme, but the store always mints
         * one under {@code sha256://}.
         */
        public String artifactUrl() { return artifactUrl; }

        /** Length of the composed wasm in bytes. */
        public long size() { return size; }

        /** Plan subject IRI RDF was inserted under. */
        public String planIri() { return planIri; }
    }

    /**
     * Guice constructor — the kernel comes from the plugin injector.
     * Uses the platform-default orchestrator root
     * ({@code ${stardog.home}/webfunctions-compose}).
     */
    @Inject
    public ComposeAdmin(final Kernel kernel) {
        this(newDefaults(kernel));
    }

    private static ComposeAdmin newDefaults(final Kernel kernel) {
        final ComposeOrchestratorLoader loader = ComposeOrchestratorLoader.forDefaultRoot();
        final ComposeOrchestratorInstance orchestrator = new ComposeOrchestratorInstance(loader);
        final ComposeOrchestratorClient client = new ComposeOrchestratorClient(orchestrator);
        final ComposedArtifactStore store = ComposedArtifactStore.forLoader(loader);
        final ComposePolicyStoreWriter writer = new ComposePolicyStoreWriter(kernel);
        // Register the artifact store with the sha256:// URL handler so
        // composed artifact URLs can round-trip through
        // StardogWasmInstance's URL load path — see C11.
        Sha256ArtifactUrlHandler.setStore(store);
        Sha256ArtifactUrlHandler.install();
        return new ComposeAdmin(client, store, writer);
    }

    // Private "already-materialized" constructor — for use by both the
    // defaults path and by tests that hand-wire dependencies.
    private ComposeAdmin(final ComposeAdmin instance) {
        this(instance.client, instance.artifactStore, instance.policyStoreWriter);
    }

    /**
     * Explicit-dependency constructor for hand-wired callers (tests,
     * scripts that construct their own orchestrator + writer).
     */
    public ComposeAdmin(final ComposeOrchestratorClient client,
                        final ComposedArtifactStore artifactStore,
                        final ComposePolicyStoreWriter policyStoreWriter) {
        this.client = Objects.requireNonNull(client, "client");
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.policyStoreWriter = Objects.requireNonNull(policyStoreWriter, "policyStoreWriter");
    }

    /**
     * Hand-wired factory for scripts / embedded callers. Uses the
     * platform-default orchestrator root and hooks the artifact store
     * into the {@code sha256://} URL scheme.
     */
    public static ComposeAdmin wire(final Kernel kernel) {
        return new ComposeAdmin(newDefaults(kernel));
    }

    /**
     * Compose a plan handed as pre-encoded CBOR. Returns the composed
     * wasm's artifact URL + digest + size + plan IRI.
     *
     * @param planCbor canonical CBOR of a {@link PlanV1} (as produced
     *                 by {@link PlanV1Cbor#encode(PlanV1)} or by the
     *                 upstream Rust {@code ciborium} serializer).
     */
    public ComposedResult compose(final byte[] planCbor) {
        return composeFromCbor(planCbor, null);
    }

    /**
     * Compose a plan handed as pre-encoded CBOR under an explicit
     * plan subject IRI. The IRI is:
     * <ul>
     *   <li>keyed into the {@code plan-to-turtle-with-iri} guest call,
     *       so the resulting Turtle references it directly;</li>
     *   <li>keyed into {@link ComposePolicyStoreWriter#write}'s
     *       idempotent overwrite so a repeat composition of the same
     *       plan overwrites the same triples.</li>
     * </ul>
     *
     * @param planCbor canonical CBOR of a {@link PlanV1}.
     * @param planIri  optional plan subject IRI; when {@code null} the
     *                 orchestrator's default subject ({@code urn:composition:plan})
     *                 is used.
     */
    public ComposedResult composeFromCbor(final byte[] planCbor, final String planIri) {
        Objects.requireNonNull(planCbor, "planCbor");
        final byte[] composed = client.composeFromCbor(planCbor);
        final String artifactUrl;
        try {
            artifactUrl = artifactStore.persist(composed);
        } catch (IOException ioe) {
            throw new ComposeException(null, "artifact store persist failed: " + ioe.getMessage(), ioe);
        }
        final String turtle = planIri == null
                ? client.planToTurtleCbor(planCbor)
                : client.planToTurtleCbor(planCbor, planIri);
        policyStoreWriter.write(turtle.getBytes(java.nio.charset.StandardCharsets.UTF_8), planIri);
        return new ComposedResult(artifactUrl, composed.length,
                planIri == null ? "urn:composition:plan" : planIri);
    }

    /**
     * Compose a plan handed as a materialized Java {@link PlanV1}
     * instance. Convenience wrapper around
     * {@link #composeFromCbor(byte[], String)}.
     */
    public ComposedResult compose(final PlanV1 plan) {
        return composeFromCbor(PlanV1Cbor.encode(plan), null);
    }

    /**
     * Same as {@link #compose(PlanV1)} but with an explicit plan IRI.
     */
    public ComposedResult compose(final PlanV1 plan, final String planIri) {
        Objects.requireNonNull(plan, "plan");
        return composeFromCbor(PlanV1Cbor.encode(plan), planIri);
    }

    /**
     * Read back a previously composed artifact by its URL. Accepts the
     * canonical {@code sha256://<hex>} URL form and the bare
     * {@code sha256:<hex>} hash-pair form. Returns {@link Optional#empty()}
     * when the artifact URL has no on-disk match.
     */
    public Optional<byte[]> loadArtifact(final String artifactUrl) throws IOException {
        return artifactStore.load(artifactUrl);
    }

    // Package-visible test hooks.
    ComposedArtifactStore artifactStore() { return artifactStore; }
    ComposePolicyStoreWriter policyStoreWriter() { return policyStoreWriter; }
    ComposeOrchestratorClient client() { return client; }
}
