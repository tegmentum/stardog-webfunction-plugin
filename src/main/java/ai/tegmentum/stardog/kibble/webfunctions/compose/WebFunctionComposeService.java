package ai.tegmentum.stardog.kibble.webfunctions.compose;

import ai.tegmentum.stardog.kibble.webfunctions.WebFunctionVocabulary;
import com.complexible.stardog.plan.PlanException;
import com.complexible.stardog.plan.PlanNode;
import com.complexible.stardog.plan.eval.ExecutionContext;
import com.complexible.stardog.plan.eval.service.ServiceQuery;
import com.complexible.stardog.plan.eval.service.SingleQueryService;
import com.stardog.stark.IRI;

/**
 * SPARQL SERVICE trigger for the compose orchestrator plan-composition
 * surface.
 *
 * <p>Registered under the IRI produced by
 * {@link WebFunctionVocabulary#composePlan}:
 * {@code http://semantalytics.com/2021/03/ns/stardog/webfunction/<version>/composePlan}.
 *
 * <p>MVP wiring: the service is discoverable and its IRI is a valid
 * SPARQL SERVICE endpoint per Stardog's Service registry, but the
 * consumable dispatch path is the Java-callable
 * {@link ComposeAdmin#compose(byte[])} — the SERVICE evaluate path
 * throws {@link PlanException} with an actionable message directing
 * callers to the admin entry point. Full plan-triple parsing out of a
 * SERVICE clause (build a PlanV1 from arbitrary triples handed to the
 * service) is Wave C+ work — the WIT surface is deep enough that the
 * MVP admin path serves the same use cases (script-driven,
 * admin-authenticated) without the plumbing overhead.
 *
 * <p>Once the SERVICE-body parse is in, the operator will:
 * <ol>
 *   <li>Extract {@code wf:planCbor} literal → CBOR bytes.</li>
 *   <li>Extract optional {@code wf:planIri} literal.</li>
 *   <li>Delegate to {@link ComposeAdmin#composeFromCbor(byte[], String)}.</li>
 *   <li>Bind the resulting {@code sha256://} URL to {@code wf:cid} in
 *       the SERVICE output bindings.</li>
 * </ol>
 */
public final class WebFunctionComposeService extends SingleQueryService<ServiceQuery> {

    @Override
    public boolean canEvaluate(final IRI iri) {
        return WebFunctionVocabulary.composePlan.getNames().contains(iri.toString());
    }

    @Override
    public ServiceQuery createQuery(final IRI iri, final PlanNode body, final ExecutionContext context) {
        // MVP scope fence: SPARQL SERVICE dispatch requires substantial
        // PlanNode-to-PlanV1 parameter parsing that the C10 admin entry
        // does not need. The service IRI is reserved and discoverable;
        // callers wanting composition at MVP call ComposeAdmin.compose
        // directly (script, embedded, or a future admin CLI subcommand).
        throw new PlanException(
                "compose SPARQL SERVICE dispatch is not yet wired; call "
                        + "ComposeAdmin.compose(byte[]) programmatically or from an "
                        + "admin script (SERVICE IRI reserved: " + iri + ")");
    }
}
