package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.common.rdf.model.ArrayLiteral;
import com.complexible.stardog.plan.QueryTerm;
import com.complexible.stardog.plan.SortType;
import com.complexible.stardog.plan.eval.ExecutionContext;
import com.complexible.stardog.plan.eval.operator.*;
import com.complexible.stardog.plan.eval.operator.impl.AbstractOperator;
import com.complexible.stardog.plan.eval.operator.impl.Solutions;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.SelectQueryResult;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.stardog.stark.Values.iri;

/**
 * Executable operator for the repeat function
 *
 * @author Michael Grove
 */
public final class WebFunctionServiceOperator extends AbstractOperator implements PropertyFunctionOperator {

    private Value wasmIRI ;

    /**
     * The current solution
     */
    private Solution solution;

    /**
     * The child argument
     */
    private final Optional<Operator> mArg;

    /**
     * An iterator over the child solutions of this operator
     */
    private Iterator<Solution> mInputs = null;

    private SelectQueryResult selectQueryResult;

    private final List<QueryTerm> args;
    private final List<QueryTerm> results;
    private final StardogWasmInstance stardogWasmInstance;

    // Fuel-metering Phase 1 attribution — the operator's wasm invocation
    // (stardogWasmInstance.evaluate) runs at most once per operator
    // lifecycle, but computeNext() is called repeatedly to iterate the
    // resulting binding stream. This flag makes the append idempotent
    // across the iteration so exactly one AttributionRow lands per
    // wf:call, not one per row of output.
    private boolean attributionRecorded = false;

    public WebFunctionServiceOperator(final ExecutionContext theExecutionContext,
                                      final Value wasmIRI,
                                      final List<QueryTerm> args,
                                      final List<QueryTerm> results,
                                      final Operator theOperator,
                                      final StardogWasmInstance stardogWasmInstance) {
        super(theExecutionContext, SortType.UNSORTED);

        mArg = Optional.ofNullable(theOperator);
        this.wasmIRI = wasmIRI;
        this.args = args;
        this.results = results;
        this.stardogWasmInstance = stardogWasmInstance;
        this.stardogWasmInstance.setMappingDictionary(theExecutionContext.getMappings());
    }

    @Override
    protected Solution computeNext() {
        // Bind the ExecutionContext for the duration of this operator's
        // stream so wf callbacks (execute-query, callback-depth) can reach
        // the outer query's connection. This is the SERVICE-path bind;
        // filter-function wf:call binds via Call.evaluate but without an
        // ExecutionContext, so execute-query is unavailable on that path.
        final CallbackContext cbCtx = CallbackContext.bind(mExecutionContext);
        // Fuel metering Phase 1 — stamp per-invocation budget + extension
        // URI on this frame's CallbackContext so host-callback tolls
        // charge against it and typed error attribution names the wasm
        // extension being invoked.
        final String extensionUri = wasmIRI == null ? "" : wasmIRI.toString();
        if (WebFunctionConfig.fuelEnabled()) {
            cbCtx.setFuelMeteringContext(
                    extensionUri,
                    WebFunctionConfig.fuelPerInvocationMax(),
                    WebFunctionConfig.fuelHostCallbackToll());
        }
        // Fuel metering Phase 2 — extract Shiro identity + pre-invocation
        // quota check per fuel-implementation.md §4 steps 2-6. No-op when
        // fuel is disabled or per-user quota unset (activePolicy() returns
        // empty). Throws WfBudgetError.UserQuotaExhausted on cap hit;
        // propagates through the catch chain below intact.
        final FuelContext fuelCtx = FuelContext.extract(extensionUri);
        final UserFuelPolicy policy = UserFuelPolicy.activePolicy().orElse(null);
        if (policy != null) {
            policy.preInvocation(fuelCtx);
        }
        try {
            final Solution s = computeNextInternal();
            // Post-invocation counter increment for each yielded solution
            // (matches memo §4 step 11 "on success"). Prefer the store's
            // real fuel_consumed reading via cbCtx.fuelConsumed(); when the
            // provider returns -1 (non-wasmtime provider without fuel
            // support), fall back to the toll counter clamped to at least
            // 1 unit so the counter still advances for extensions that
            // made no host callbacks. Only account when a real solution
            // was produced (endOfData signals streaming complete; skip
            // the tail no-op).
            if (policy != null && s != null) {
                policy.postInvocation(fuelCtx, cbCtx, Math.max(1L, cbCtx.tollUsed()));
            }
            return s;
        } catch (WfBudgetError e) {
            // Already-typed — rethrow so Stardog's query engine surfaces
            // it as a plugin error with the JSON payload intact.
            recordAttributionOnce(e, cbCtx);
            if (policy != null && !(e instanceof WfBudgetError.UserQuotaExhausted)) {
                // Charge on trap: prefer real fuel_consumed, else the cap.
                // Skip UserQuotaExhausted — that error trips pre-invocation
                // and consumes no fuel.
                policy.postInvocation(fuelCtx, cbCtx, WebFunctionConfig.fuelPerInvocationMax());
            }
            throw e;
        } catch (RuntimeException e) {
            final WfBudgetError typed = FuelTrapMapper.mapOrNull(e, cbCtx);
            if (typed != null) {
                recordAttributionOnce(typed, cbCtx);
            }
            if (policy != null && !(typed instanceof WfBudgetError.UserQuotaExhausted)) {
                policy.postInvocation(fuelCtx, cbCtx, WebFunctionConfig.fuelPerInvocationMax());
            }
            if (typed != null) {
                throw typed;
            }
            throw e;
        } finally {
            CallbackContext.unbindIfOutermost(cbCtx);
        }
    }

    /**
     * Emit exactly one attribution row per operator lifetime, guarded by
     * {@link #attributionRecorded}. Called from the trap path in
     * {@link #computeNext()} and (with {@code trap=null}) from the success
     * path in {@link #computeNextInternal()} once evaluate has returned
     * a live SelectQueryResult.
     *
     * <p>{@code queryId} is best-effort — pulled from the bound
     * {@link com.complexible.stardog.plan.eval.ExecutionMonitor}. When the
     * monitor is unavailable (test/embedded contexts), records "".
     */
    private void recordAttributionOnce(final WfBudgetError trap,
                                       final CallbackContext cbCtx) {
        if (attributionRecorded) return;
        if (!WebFunctionConfig.attributionLogEnabled()) return;
        attributionRecorded = true;
        final String uri = wasmIRI == null ? "" : wasmIRI.toString();
        // Prefer the store's real fuel_consumed (wasmtime4j 1.4.7+); fall back
        // to the Java-side toll counter when the provider returns -1.
        final long fuel;
        if (cbCtx == null) {
            fuel = 0L;
        } else {
            final long real = cbCtx.fuelConsumed();
            fuel = real >= 0L ? real : cbCtx.tollUsed();
        }
        final String queryId = safeQueryId();
        if (trap == null) {
            AttributionRing.recordSuccess(uri, fuel, queryId);
        } else {
            AttributionRing.recordTrap(uri, trap, fuel, queryId);
        }
    }

    private String safeQueryId() {
        try {
            if (mExecutionContext == null) return "";
            final com.complexible.stardog.plan.eval.ExecutionMonitor monitor =
                    mExecutionContext.getMonitor();
            if (monitor == null) return "";
            final String id = monitor.getQueryId();
            return id == null ? "" : id;
        } catch (RuntimeException ignore) {
            // Monitor state can throw when the query has been cancelled or
            // the context is torn down; a missing queryId is not worth
            // failing an attribution write over.
            return "";
        }
    }

    private Solution computeNextInternal() {
        if (mInputs == null) {
            // first call to compute results, perform some init
            // either use our child's solutions, or if we don't have a child, create a single solution to use
            if (mArg.filter(theOp -> !(theOp instanceof EmptyOperator)).isPresent()) {
                // these are the variables the child arg will bind
                Set<Integer> aVars = Sets.newHashSet(mArg.get().getVars());

                // and these are the ones that the pf will bind
                aVars.addAll(results.stream().map(QueryTerm::getName).collect(Collectors.toList()));

                // now we create a solution that contains room for bindings for these variables
                final Solution aSoln = mExecutionContext.getSolutionFactory()
                        .variables(aVars)
                        .newSolution();

                // and transform the child solutions to this one large enough to accomodate our vars
                mInputs = Iterators.transform(mArg.get(), theSoln -> {
                    Solutions.copy(aSoln, theSoln);
                    return aSoln;
                });
            } else if (args.stream().allMatch(QueryTerm::isVariable)) {
                return endOfData();
            } else {
                final Set<Integer> aVars = Sets.newHashSet();

                aVars.addAll(results.stream().filter(QueryTerm::isVariable).map(QueryTerm::getName).collect(Collectors.toList()));

                // we only want to create solutions with the minimum number of variables
                mInputs = Iterators.singletonIterator(mExecutionContext.getSolutionFactory()
                        .variables(aVars)
                        .newSolution());
            }
        }

        while (mInputs.hasNext() || solution != null) {
            if (solution == null && mInputs.hasNext()) {
                solution = mInputs.next();
            }
            if (selectQueryResult == null) {
                try {
                    ValueOrError[] valueOrErrors = args.stream().map(queryTerm -> {
                        if (queryTerm.isVariable()) {
                            return solution.getValue(queryTerm.getName(), getMappings());
                        } else {
                            return queryTerm.getValue();
                        }
                    }).toArray(ValueOrError[]::new);

                    if (Arrays.stream(valueOrErrors).noneMatch(ValueOrError::isError)) {
                        selectQueryResult = stardogWasmInstance.evaluate(Arrays.stream(valueOrErrors).map(ValueOrError::value).toArray(Value[]::new));
                        // Fuel-metering Phase 1 attribution — evaluate
                        // returned; record SUCCESS exactly once per
                        // operator lifetime (subsequent computeNext calls
                        // no-op via attributionRecorded).
                        recordAttributionOnce(null, CallbackContext.current());
                    } else {
                        stardogWasmInstance.close();
                        return endOfData();
                    }
                } catch (IOException e) {
                    stardogWasmInstance.close();
                    return endOfData();
                }
            }
            if(selectQueryResult.hasNext()) {
                final BindingSet bindingSet = selectQueryResult.next();
                // Look up bindings positionally against the SelectQueryResult's
                // declared variables list. Single-value returns from
                // extension.call are wrapped under the well-known value_0
                // name (see WitValueMarshaller#singleTermToSelectQueryResult);
                // component-mode WIT binding-sets can emit arbitrary names
                // (e.g. from a multi-var component). Falling back to
                // value_%d keeps compatibility if variables() is empty.
                final List<String> queryVars = selectQueryResult.variables();

                IntStream.range(0, results.size()).forEach(i -> {
                    final String varName = i < queryVars.size()
                            ? queryVars.get(i)
                            : String.format("value_%d", i);
                    final Value raw = bindingSet.get(varName);
                    final Value value;
                    if (raw instanceof Literal && ((Literal) raw).datatypeIRI().equals(iri("tag:stardog:api:array"))) {
                        value = ArrayLiteral.coerce((Literal) raw);
                    } else {
                        value = raw;
                    }
                    solution.setValue(results.get(i).getName(), value, getMappings());
                });
                return solution;
            } else {
                selectQueryResult.close();
                selectQueryResult = null;
                solution = null;
            }
        }
            stardogWasmInstance.close();
            return endOfData();
    }
            /*

            private long getValue() {
                return mNode.getInput().isConstant()
                        ? mNode.getInput().getIndex()
                        : mValue.get(mNode.getInput().getName());
            }

             */

    /**
     * {@inheritDoc}
     */
    @Override
    protected void performReset() {
        mArg.ifPresent(Operator::reset);
        stardogWasmInstance.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Integer> getVars() {
        return results.stream()
                .filter(QueryTerm::isVariable)
                .map(QueryTerm::getName)
                .collect(Collectors.toSet());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void accept(final OperatorVisitor theOperatorVisitor) {
        theOperatorVisitor.visit(this);
    }
}
