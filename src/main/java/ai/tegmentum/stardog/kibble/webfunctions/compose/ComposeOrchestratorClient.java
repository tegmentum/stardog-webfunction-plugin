package ai.tegmentum.stardog.kibble.webfunctions.compose;

import ai.tegmentum.webassembly4j.api.ComponentInstance;
import ai.tegmentum.webassembly4j.api.exception.ExecutionException;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * High-level Java wrapper over the compose orchestrator's WIT exports.
 *
 * <p>Wraps a single {@link ComposeOrchestratorInstance} and exposes
 * three surfaces:
 * <ul>
 *   <li>{@link #compose(PlanV1)} — CBOR-encode a Java plan, hand it to
 *       {@code sys:compose/plan@1.0.0#deserialize} on the orchestrator,
 *       call {@code sys:compose/emit@1.0.0#compose} on the deserialized
 *       plan, then fetch the composed bytes via
 *       {@code sys:compose/emit@1.0.0#get-artifact}.</li>
 *   <li>{@link #planToTurtle(PlanV1)} + {@link #planToTurtle(PlanV1, String)}
 *       — CBOR-encode + call {@code sys:compose/rdf@1.0.0#plan-to-turtle}
 *       (or {@code #plan-to-turtle-with-iri}).</li>
 *   <li>{@link #planToTurtleCbor(byte[])} + {@link #planToTurtleCbor(byte[], String)}
 *       — the raw-CBOR admissions surface for callers that already hold
 *       canonical bytes (e.g. an admin CLI ingesting pre-serialized plans).</li>
 * </ul>
 *
 * <p>All calls use {@link ComponentInstance#invoke} — the "natural Java
 * shape" API — so return values arrive as {@code byte[]}, {@code String},
 * or {@code Map<String, Object>}. The orchestrator's WIT results
 * ({@code result<_, sys:compose/types#error>}) unwrap automatically:
 * on {@code ok} the value is returned; on {@code err} the provider
 * throws {@link ExecutionException}, which we translate to
 * {@link ComposeException}.
 */
public final class ComposeOrchestratorClient {

    // WIT export paths — <interface>#<function>. See the orchestrator's
    // world.wit; interface package version @1.0.0 is stable across the
    // active orchestrator wasm build.
    static final String EXPORT_PLAN_DESERIALIZE      = "sys:compose/plan@1.0.0#deserialize";
    static final String EXPORT_EMIT_COMPOSE          = "sys:compose/emit@1.0.0#compose";
    static final String EXPORT_EMIT_GET_ARTIFACT     = "sys:compose/emit@1.0.0#get-artifact";
    static final String EXPORT_RDF_PLAN_TO_TURTLE    = "sys:compose/rdf@1.0.0#plan-to-turtle";
    static final String EXPORT_RDF_PLAN_TO_TURTLE_WI = "sys:compose/rdf@1.0.0#plan-to-turtle-with-iri";
    static final String EXPORT_RDF_PLAN_TO_TURTLE_WA = "sys:compose/rdf@1.0.0#plan-to-turtle-with-artifact";
    static final String EXPORT_RDF_PLAN_FROM_TURTLE  = "sys:compose/rdf@1.0.0#plan-from-turtle";
    static final String EXPORT_RDF_PLAN_FROM_TURTLE_WI = "sys:compose/rdf@1.0.0#plan-from-turtle-with-iri";

    private final ComposeOrchestratorInstance orchestrator;

    public ComposeOrchestratorClient(final ComposeOrchestratorInstance orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    /**
     * Compose a plan and return the resulting composed wasm bytes.
     *
     * <p>Multi-step flow through the orchestrator:
     * <ol>
     *   <li>Deserialize the CBOR-encoded plan on the guest side (this
     *       validates the CBOR wire compat with ciborium+serde).</li>
     *   <li>Invoke {@code emit#compose} on the resulting plan record,
     *       yielding a {@code composition-result} with the digest.</li>
     *   <li>Invoke {@code emit#get-artifact(digest)} to retrieve the
     *       composed wasm blob.</li>
     * </ol>
     */
    public byte[] compose(final PlanV1 plan) {
        Objects.requireNonNull(plan, "plan");
        return composeFromCbor(PlanV1Cbor.encode(plan));
    }

    /**
     * Same as {@link #compose(PlanV1)} but accepts pre-encoded CBOR
     * bytes — useful for the admin surface which ingests arbitrary
     * planner-produced blobs.
     */
    public byte[] composeFromCbor(final byte[] planCbor) {
        Objects.requireNonNull(planCbor, "planCbor");
        final ComponentInstance instance;
        try {
            instance = orchestrator.instance();
        } catch (IOException e) {
            throw new ComposeException("orchestrator instance unavailable: " + e.getMessage());
        }
        final Object planRecord = invokeOrThrow(instance, EXPORT_PLAN_DESERIALIZE, planCbor);
        final Object composed   = invokeOrThrow(instance, EXPORT_EMIT_COMPOSE, planRecord);
        if (!(composed instanceof Map)) {
            throw new ComposeException("unexpected compose return type: "
                    + (composed == null ? "null" : composed.getClass().getName()));
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> compositionResult = (Map<String, Object>) composed;
        final Object digest = compositionResult.get("digest");
        if (!(digest instanceof byte[])) {
            throw new ComposeException("composition-result#digest was not byte[]: "
                    + (digest == null ? "null" : digest.getClass().getName()));
        }
        final Object artifact = invokeOrThrow(instance, EXPORT_EMIT_GET_ARTIFACT, digest);
        if (!(artifact instanceof byte[])) {
            throw new ComposeException("get-artifact return was not byte[]: "
                    + (artifact == null ? "null" : artifact.getClass().getName()));
        }
        return (byte[]) artifact;
    }

    /**
     * Render {@code plan} as a Turtle document with the orchestrator's
     * default plan subject IRI ({@code urn:composition:plan}).
     */
    public String planToTurtle(final PlanV1 plan) {
        Objects.requireNonNull(plan, "plan");
        return planToTurtleCbor(PlanV1Cbor.encode(plan));
    }

    /**
     * Render {@code plan} as a Turtle document with an explicit plan
     * subject IRI ({@code plan-iri}).
     */
    public String planToTurtle(final PlanV1 plan, final String planIri) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(planIri, "planIri");
        return planToTurtleCbor(PlanV1Cbor.encode(plan), planIri);
    }

    /**
     * Raw-CBOR variant of {@link #planToTurtle(PlanV1)}.
     */
    public String planToTurtleCbor(final byte[] planCbor) {
        Objects.requireNonNull(planCbor, "planCbor");
        return callTurtle(EXPORT_RDF_PLAN_TO_TURTLE, new Object[]{planCbor});
    }

    /**
     * Raw-CBOR variant of {@link #planToTurtle(PlanV1, String)}.
     */
    public String planToTurtleCbor(final byte[] planCbor, final String planIri) {
        Objects.requireNonNull(planCbor, "planCbor");
        Objects.requireNonNull(planIri, "planIri");
        return callTurtle(EXPORT_RDF_PLAN_TO_TURTLE_WI, new Object[]{planCbor, planIri});
    }

    /**
     * Render {@code planCbor} as a Turtle document with two additional
     * composed-artifact anchor triples on top of the standard plan RDF:
     * <ul>
     *   <li>{@code <planIri> comp:hasArtifact <artifactUrl>} — REQUIRED;
     *       the URL the composed wasm is served from (default
     *       {@code sha256://<hex>}, any URL scheme accepted).</li>
     *   <li>{@code <planIri> comp:compositionDigest "<hex>"} — OPTIONAL;
     *       emitted only when {@code digestHex} is present. Lowercase-hex
     *       SHA-256 of the composed bytes as an {@code xsd:string}.
     *       Stable across artifact-URL re-hosting.</li>
     * </ul>
     *
     * <p>Downstream admins SPARQL-join the {@code comp:hasArtifact}
     * object against capability-grant subjects directly, without needing
     * an external composed-artifact registry. See the composition-admin
     * memo §4.6 for the diff-query pattern.
     *
     * @param planCbor     canonical CBOR of a {@link PlanV1}.
     * @param planIri      plan subject IRI to anchor the artifact triples on.
     *                     When {@code null}, defaults to the orchestrator's
     *                     {@code urn:composition:plan}.
     * @param artifactUrl  URL the composed wasm is served from (required).
     * @param digestHex    optional lowercase-hex SHA-256 of the composed
     *                     bytes; when {@code Optional.empty()} the digest
     *                     anchor is omitted.
     */
    public String planToTurtleWithArtifact(final byte[] planCbor,
                                           final Optional<String> planIri,
                                           final String artifactUrl,
                                           final Optional<String> digestHex) {
        Objects.requireNonNull(planCbor, "planCbor");
        Objects.requireNonNull(planIri, "planIri");
        Objects.requireNonNull(artifactUrl, "artifactUrl");
        Objects.requireNonNull(digestHex, "digestHex");
        final String effectivePlanIri = planIri.orElse("urn:composition:plan");
        // WIT `option<string>` marshals through the natural-Java shape as
        // Optional<String>; hand it through untouched.
        return callTurtle(
                EXPORT_RDF_PLAN_TO_TURTLE_WA,
                new Object[]{planCbor, effectivePlanIri, artifactUrl, digestHex});
    }

    /**
     * Parse a Turtle document into canonical CBOR plan bytes via
     * {@code sys:compose/rdf@1.0.0#plan-from-turtle}. The Turtle must
     * describe the plan under the orchestrator's default plan IRI
     * ({@code urn:composition:plan}). The returned CBOR is byte-identical
     * to what {@link #compose(PlanV1)}'s implicit {@code plan.serialize}
     * would emit for the reconstructed plan, so callers can hand the
     * bytes straight to {@link ComposeAdmin#compose(byte[])}.
     */
    public byte[] planFromTurtle(final String turtle) {
        Objects.requireNonNull(turtle, "turtle");
        return callBytes(EXPORT_RDF_PLAN_FROM_TURTLE, new Object[]{turtle});
    }

    /**
     * Same as {@link #planFromTurtle(String)} but the caller specifies
     * the plan subject IRI — must match whatever IRI the Turtle uses.
     */
    public byte[] planFromTurtle(final String turtle, final String planIri) {
        Objects.requireNonNull(turtle, "turtle");
        Objects.requireNonNull(planIri, "planIri");
        return callBytes(EXPORT_RDF_PLAN_FROM_TURTLE_WI, new Object[]{turtle, planIri});
    }

    // --- helpers -----------------------------------------------------

    private byte[] callBytes(final String export, final Object[] args) {
        final ComponentInstance instance;
        try {
            instance = orchestrator.instance();
        } catch (IOException e) {
            throw new ComposeException("orchestrator instance unavailable: " + e.getMessage());
        }
        final Object result = invokeOrThrow(instance, export, args);
        if (!(result instanceof byte[])) {
            throw new ComposeException("expected byte[] from " + export + ", got "
                    + (result == null ? "null" : result.getClass().getName()));
        }
        return (byte[]) result;
    }

    private String callTurtle(final String export, final Object[] args) {
        final ComponentInstance instance;
        try {
            instance = orchestrator.instance();
        } catch (IOException e) {
            throw new ComposeException("orchestrator instance unavailable: " + e.getMessage());
        }
        final Object result = invokeOrThrow(instance, export, args);
        if (!(result instanceof String)) {
            throw new ComposeException("expected String from " + export + ", got "
                    + (result == null ? "null" : result.getClass().getName()));
        }
        return (String) result;
    }

    private static Object invokeOrThrow(final ComponentInstance instance,
                                        final String export,
                                        final Object... args) {
        try {
            return instance.invoke(export, args);
        } catch (ExecutionException e) {
            throw new ComposeException(export + " failed: " + e.getMessage());
        } catch (RuntimeException e) {
            // Any other runtime — component trap, WIT marshalling error,
            // etc. — surface as a ComposeException so callers have a
            // single exception type to handle.
            throw new ComposeException(null, export + " threw: " + e.getMessage(), e);
        }
    }
}
