package ai.tegmentum.stardog.kibble.webfunctions.compose;

import ai.tegmentum.stardog.kibble.webfunctions.WebFunctionConfig;
import com.complexible.stardog.Kernel;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Admin-callable entry point for the compose orchestrator.
 *
 * <p>Bundles together the four Wave B moving parts:
 * <ol>
 *   <li>{@link ComposeOrchestratorClient} — invokes the composed
 *       orchestrator wasm's {@code sys:compose/emit#compose} and
 *       {@code sys:compose/rdf#plan-to-turtle-with-artifact} exports.</li>
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
    private final Supplier<Optional<String>> artifactUrlPrefixSupplier;
    // Indirection between {@link #composeFromCbor} and the policy-store
    // write side. In production this is {@code policyStoreWriter::write};
    // tests supply a no-op or a recorder so they can drive the compose
    // flow without a live Kernel. Kept as a {@link BiConsumer} rather
    // than a bespoke functional interface to avoid adding a new type
    // to the public surface for a purely internal seam.
    private final BiConsumer<byte[], String> writeSink;

    /**
     * Result of a single admin composition — carries the artifact URL
     * of the composed wasm, the lowercase-hex SHA-256 of the composed
     * bytes, the size in bytes, and the plan IRI the RDF was inserted
     * under.
     */
    public static final class ComposedResult {
        private final String artifactUrl;
        private final String digestHex;
        private final long size;
        private final String planIri;

        public ComposedResult(final String artifactUrl,
                              final String digestHex,
                              final long size,
                              final String planIri) {
            this.artifactUrl = Objects.requireNonNull(artifactUrl, "artifactUrl");
            this.digestHex = Objects.requireNonNull(digestHex, "digestHex");
            this.size = size;
            this.planIri = planIri;
        }

        /**
         * RDF-facing composed-artifact URL — {@code sha256://<hex>} by
         * default (the plugin's in-tree content-addressed blob store).
         * When {@link WebFunctionConfig#PROP_COMPOSE_ARTIFACT_URL_PREFIX}
         * is set, the returned URL is {@code <prefix><hex>} instead —
         * so operators can point the composition RDF at an off-plugin
         * CDN or object store. The plugin still persists composed bytes
         * to its local blob store regardless of this setting; making
         * the emitted URL fetchable is the operator's responsibility.
         */
        public String artifactUrl() { return artifactUrl; }

        /**
         * Lowercase-hex SHA-256 of the composed bytes (no scheme
         * prefix). Same value that lands in the composition RDF as the
         * {@code comp:compositionDigest} literal — an anchor that stays
         * valid across artifact-URL re-hosting.
         */
        public String digestHex() { return digestHex; }

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
        this.client = instance.client;
        this.artifactStore = instance.artifactStore;
        this.policyStoreWriter = instance.policyStoreWriter;
        this.artifactUrlPrefixSupplier = instance.artifactUrlPrefixSupplier;
        this.writeSink = instance.writeSink;
    }

    /**
     * Explicit-dependency constructor for hand-wired callers (tests,
     * scripts that construct their own orchestrator + writer). Reads
     * the RDF-facing artifact URL prefix from
     * {@link WebFunctionConfig#getArtifactUrlPrefix()} — same shape the
     * Guice constructor uses in production.
     */
    public ComposeAdmin(final ComposeOrchestratorClient client,
                        final ComposedArtifactStore artifactStore,
                        final ComposePolicyStoreWriter policyStoreWriter) {
        this.client = Objects.requireNonNull(client, "client");
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.policyStoreWriter = Objects.requireNonNull(policyStoreWriter, "policyStoreWriter");
        this.artifactUrlPrefixSupplier = WebFunctionConfig::getArtifactUrlPrefix;
        this.writeSink = policyStoreWriter::write;
    }

    /**
     * Test-only constructor that pins the RDF-facing artifact URL
     * prefix through {@code artifactUrlPrefixSupplier} and redirects the
     * policy-store write side through {@code writeSink} — so unit tests
     * can drive the full compose flow without a live Kernel. Production
     * callers should use
     * {@link #ComposeAdmin(ComposeOrchestratorClient, ComposedArtifactStore,
     * ComposePolicyStoreWriter)}, which routes through
     * {@link WebFunctionConfig#getArtifactUrlPrefix()} and
     * {@link ComposePolicyStoreWriter#write}.
     */
    ComposeAdmin(final ComposeOrchestratorClient client,
                 final ComposedArtifactStore artifactStore,
                 final ComposePolicyStoreWriter policyStoreWriter,
                 final Supplier<Optional<String>> artifactUrlPrefixSupplier,
                 final BiConsumer<byte[], String> writeSink) {
        this.client = Objects.requireNonNull(client, "client");
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.policyStoreWriter = policyStoreWriter; // nullable — tests may skip the writer wiring
        this.artifactUrlPrefixSupplier = Objects.requireNonNull(
                artifactUrlPrefixSupplier, "artifactUrlPrefixSupplier");
        this.writeSink = Objects.requireNonNull(writeSink, "writeSink");
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
     *   <li>keyed into the {@code plan-to-turtle-with-artifact} guest
     *       call, so the resulting Turtle references it directly and
     *       carries a {@code comp:hasArtifact} anchor triple pointing
     *       at the composed artifact URL;</li>
     *   <li>keyed into {@link ComposePolicyStoreWriter#write}'s
     *       idempotent overwrite so a repeat composition of the same
     *       plan overwrites the same triples.</li>
     * </ul>
     *
     * <p>The resulting composition RDF carries two anchor triples on
     * top of the standard plan RDF:
     * <ul>
     *   <li>{@code <plan> comp:hasArtifact <sha256://<hex>>} — REQUIRED;
     *       the artifact URL. Downstream admins SPARQL-join against
     *       this to answer "which extension grant covers which
     *       composition."</li>
     *   <li>{@code <plan> comp:compositionDigest "<hex>"} — OPTIONAL
     *       (this method always emits it); content-identity anchor
     *       that stays valid across artifact-URL re-hosting.</li>
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
        // Persist to the local blob store first — the plugin owns
        // {@code sha256://<hex>} loads regardless of what URL surfaces
        // in the emitted RDF, so the store side is unaffected by the
        // operator-facing prefix override.
        try {
            artifactStore.persist(composed);
        } catch (IOException ioe) {
            throw new ComposeException(null, "artifact store persist failed: " + ioe.getMessage(), ioe);
        }
        final String digestHex = ComposedArtifactStore.hexDigestFor(composed);
        // Choose the RDF-facing artifact URL: when the operator sets
        // {@code webfunctions.compose.artifact-url-prefix}, emit
        // {@code <prefix><hex>} — otherwise default to the canonical
        // {@code sha256://<hex>} form the local blob store mints.
        // Persistence path is unchanged in either case.
        final String artifactUrl = artifactUrlPrefixSupplier.get()
                .map(prefix -> prefix + digestHex)
                .orElse("sha256://" + digestHex);
        final String effectivePlanIri = planIri == null ? "urn:composition:plan" : planIri;
        // Emit hasArtifact + compositionDigest triples so the composition
        // RDF is joinable against capability grants directly — no
        // side-channel registry needed. See the composition-admin memo
        // §4.6 for the diff-query pattern that this closes.
        final String turtle = client.planToTurtleWithArtifact(
                planCbor,
                Optional.of(effectivePlanIri),
                artifactUrl,
                Optional.of(digestHex));
        writeSink.accept(turtle.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                effectivePlanIri);
        return new ComposedResult(artifactUrl, digestHex, composed.length, effectivePlanIri);
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
