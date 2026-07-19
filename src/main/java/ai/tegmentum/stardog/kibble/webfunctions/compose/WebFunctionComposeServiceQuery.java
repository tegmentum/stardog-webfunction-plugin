package ai.tegmentum.stardog.kibble.webfunctions.compose;

import ai.tegmentum.stardog.kibble.webfunctions.WebFunctionVocabulary;
import com.complexible.common.base.Options;
import com.complexible.stardog.db.ConnectableConnection;
import com.complexible.stardog.index.dictionary.MappingDictionary;
import com.complexible.stardog.index.statistics.Cardinality;
import com.complexible.stardog.plan.PlanNode;
import com.complexible.stardog.plan.PlanVarInfo;
import com.complexible.stardog.plan.QueryTerm;
import com.complexible.stardog.plan.eval.ExecutionContext;
import com.complexible.stardog.plan.eval.operator.Operator;
import com.complexible.stardog.plan.eval.operator.OperatorException;
import com.complexible.stardog.plan.eval.operator.SolutionIterator;
import com.complexible.stardog.plan.eval.service.PlanNodeBodyServiceQuery;
import com.complexible.stardog.plan.eval.service.ServiceQuery;
import com.complexible.stardog.plan.filter.expr.Constant;
import com.complexible.stardog.plan.util.QueryTermRenderer;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;

import java.util.Set;
import java.util.function.UnaryOperator;

import static com.stardog.stark.Values.iri;

/**
 * SPARQL SERVICE query for the compose orchestrator's plan-composition
 * path. Extracted at translate time from the SERVICE body: a Turtle
 * document (the plan triples, serialized on the Java side) and the
 * single output variable that receives the composed
 * {@code sha256:...} CID.
 *
 * <p>Bound at evaluate-time to the plugin's
 * {@link ComposeAdmin} which runs plan-from-turtle → compose →
 * artifact persist end-to-end; the operator emits a single-row
 * solution with the CID bound. See
 * {@link WebFunctionComposeService} for the SERVICE-body parse.
 */
public final class WebFunctionComposeServiceQuery extends PlanNodeBodyServiceQuery {

    private final PlanNode planBody;
    private final String turtle;
    private final int outputVarId;
    private final ComposeOrchestratorClient client;
    private final ComposeAdmin admin;

    public WebFunctionComposeServiceQuery(final PlanNode body,
                                          final String turtle,
                                          final int outputVarId,
                                          final ComposeOrchestratorClient client,
                                          final ComposeAdmin admin) {
        super(iri(WebFunctionVocabulary.composePlan.getImmutableName()), body);
        this.planBody = body;
        this.turtle = turtle;
        this.outputVarId = outputVarId;
        this.client = client;
        this.admin = admin;
    }

    public String turtle() {
        return turtle;
    }

    public int outputVarId() {
        return outputVarId;
    }

    public ComposeOrchestratorClient client() {
        return client;
    }

    public ComposeAdmin admin() {
        return admin;
    }

    @Override
    public SolutionIterator evaluate(final ExecutionContext context,
                                     final Operator operator,
                                     final PlanVarInfo varInfo) throws OperatorException {
        return new WebFunctionComposeServiceOperator(context, this, operator);
    }

    @Override
    public Set<Integer> getRequiredUnboundOutputs() {
        return outputVarId == -1 ? ImmutableSet.of() : ImmutableSet.of(outputVarId);
    }

    @Override
    public Set<Integer> getRequiredInputBindings() {
        return ImmutableSet.of();
    }

    @Override
    public ImmutableSet<Integer> getAssuredVars() {
        return outputVarId == -1 ? ImmutableSet.of() : ImmutableSet.of(outputVarId);
    }

    @Override
    public ImmutableSet<Integer> getAllVars() {
        return planBody.getAllVars();
    }

    @Override
    public String explain(final PlanVarInfo varInfo, final QueryTermRenderer renderer) {
        final String var = outputVarId == -1 ? "_" : "?" + varInfo.getName(outputVarId);
        return String.format("compose(turtle:%d bytes) -> %s", turtle.length(), var);
    }

    @Override
    public String explainVerbose(final PlanVarInfo varInfo, final QueryTermRenderer renderer) {
        return explain(varInfo, renderer);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(turtle, outputVarId, serviceTerm());
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof WebFunctionComposeServiceQuery)) return false;
        final WebFunctionComposeServiceQuery other = (WebFunctionComposeServiceQuery) obj;
        return outputVarId == other.outputVarId
                && Objects.equal(turtle, other.turtle)
                && Objects.equal(serviceTerm(), other.serviceTerm());
    }

    @Override
    public PlanNodeBodyServiceQueryBuilder toBuilder() {
        return new ComposeServiceQueryBuilder(this).body(body());
    }

    @Override
    public Cardinality estimateCardinality(final ConnectableConnection connection,
                                           final Options options) {
        // A composePlan invocation emits exactly one solution row.
        return Cardinality.verified(1L);
    }

    /**
     * Rebuild the query when Stardog's plan-canonicalization pass
     * substitutes constants. Compose SERVICE bodies contain only
     * constants (plan triples), so the substitution is a no-op for us;
     * we hand the same turtle back through a new query instance.
     */
    private static final class ComposeServiceQueryBuilder
            extends PlanNodeBodyServiceQuery.PlanNodeBodyServiceQueryBuilder {

        private final WebFunctionComposeServiceQuery source;

        ComposeServiceQueryBuilder(final WebFunctionComposeServiceQuery source) {
            this.source = source;
        }

        @Override
        public PlanNodeBodyServiceQueryBuilder replaceConstants(
                final MappingDictionary dictionary,
                final UnaryOperator<Constant> mapping,
                final boolean performValidation) {
            return new PlanNodeBodyServiceQuery.CanonicalizedPlanNodeBodyServiceQueryBuilder(this)
                    .body(replaceBodyConstants(dictionary, mapping, false));
        }

        @Override
        public PlanNodeBodyServiceQuery build() {
            return new WebFunctionComposeServiceQuery(
                    mBody, source.turtle, source.outputVarId, source.client, source.admin);
        }
    }
}
