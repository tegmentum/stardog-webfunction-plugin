package ai.tegmentum.stardog.kibble.webfunctions.compose;

import com.complexible.stardog.plan.SortType;
import com.complexible.stardog.plan.eval.ExecutionContext;
import com.complexible.stardog.plan.eval.operator.Operator;
import com.complexible.stardog.plan.eval.operator.OperatorVisitor;
import com.complexible.stardog.plan.eval.operator.PropertyFunctionOperator;
import com.complexible.stardog.plan.eval.operator.Solution;
import com.complexible.stardog.plan.eval.operator.impl.AbstractOperator;
import com.google.common.collect.Sets;
import com.stardog.stark.Value;
import com.stardog.stark.Values;

import java.util.Set;

/**
 * Single-row emit operator for the compose SERVICE. On first
 * {@code computeNext()} runs the compose pipeline end-to-end
 * (turtle → CBOR via the orchestrator client, CBOR → artifact URL via
 * {@link ComposeAdmin#compose(byte[])}), binds the artifact URL to the
 * SERVICE's declared output variable, and returns the solution.
 * Subsequent calls return {@code endOfData}.
 */
public final class WebFunctionComposeServiceOperator extends AbstractOperator
        implements PropertyFunctionOperator {

    private final WebFunctionComposeServiceQuery query;
    private boolean emitted = false;

    public WebFunctionComposeServiceOperator(final ExecutionContext context,
                                              final WebFunctionComposeServiceQuery query,
                                              final Operator input) {
        super(context, SortType.UNSORTED);
        this.query = query;
    }

    @Override
    protected Solution computeNext() {
        if (emitted) {
            return endOfData();
        }
        emitted = true;
        final byte[] cbor = query.client().planFromTurtle(query.turtle());
        final ComposeAdmin.ComposedResult result = query.admin().compose(cbor);
        final Value artifactUrlLiteral = Values.literal(result.artifactUrl());
        final Solution solution = mExecutionContext.getSolutionFactory()
                .variables(Sets.newHashSet(query.outputVarId()))
                .newSolution();
        solution.setValue(query.outputVarId(), artifactUrlLiteral, getMappings());
        return solution;
    }

    @Override
    protected void performReset() {
        emitted = false;
    }

    @Override
    public Set<Integer> getVars() {
        return query.outputVarId() == -1
                ? Sets.newHashSet()
                : Sets.newHashSet(query.outputVarId());
    }

    @Override
    public void accept(final OperatorVisitor visitor) {
        visitor.visit(this);
    }
}
