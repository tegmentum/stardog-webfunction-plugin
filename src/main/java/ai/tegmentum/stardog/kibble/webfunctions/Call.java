package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.plan.filter.AbstractExpression;
import com.complexible.stardog.plan.filter.Expression;
import com.complexible.stardog.plan.filter.ExpressionVisitor;
import com.complexible.stardog.plan.filter.Expressions;
import com.complexible.stardog.plan.filter.ValueSolution;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.complexible.stardog.plan.filter.functions.FunctionRegistry;
import com.complexible.stardog.plan.filter.functions.UserDefinedFunction;
import com.stardog.stark.BNode;
import com.stardog.stark.IRI;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.query.SelectQueryResult;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static com.complexible.stardog.plan.filter.functions.AbstractFunction.assertLiteral;
import static java.util.stream.Collectors.toList;

public final class Call extends AbstractExpression implements UserDefinedFunction {

    private static final WebFunctionVocabulary names = WebFunctionVocabulary.call;

    public Call() {
        super(new Expression[0]);
    }

    private Call(final Call call) {
        super(call);
    }

    @Override
    public Call copy() {
        return new Call(this);
    }

    @Override
    public void accept(final ExpressionVisitor expressionVisitor) {
        expressionVisitor.visit(this);
    }

    @Override
    public ValueOrError evaluate(final ValueSolution valueSolution) {
        if (getArgs().size() >= 1) {
            final ValueOrError firstArgValueOrError = getFirstArg().evaluate(valueSolution);
            if (!firstArgValueOrError.isError()) {
                final String functionName;

                if (assertLiteral(firstArgValueOrError.value())) {
                    functionName = ((Literal) firstArgValueOrError.value()).label();
                } else if (firstArgValueOrError.value() instanceof IRI) {
                    functionName = firstArgValueOrError.value().toString();
                } else if (firstArgValueOrError.value() instanceof BNode) {
                    functionName = ((BNode) firstArgValueOrError.value()).id();
                } else {
                    return ValueOrError.Error;
                }

                final List<Expression> functionArgs = getArgs().stream().skip(1).collect(toList());

                final Expression function;
                if (Compose.compositionMap.containsKey(functionName)) {
                    List<Expression> compositeFunctions = Compose.compositionMap.get(functionName).stream().map(Expressions::constant).collect(toList());
                    Collections.reverse(compositeFunctions);
                    List<Expression> initial = Stream.concat(Stream.of(compositeFunctions.get(0)), functionArgs.stream()).collect(toList());
                    function = compositeFunctions.stream().skip(1).reduce(FunctionRegistry.Instance.get(WebFunctionVocabulary.call.getImmutableName(), initial, null), (e1, e2) ->
                            FunctionRegistry.Instance.get(WebFunctionVocabulary.call.getImmutableName(), Arrays.asList(e2, e1), null));


                } else if (Partial.partialMap.containsKey(functionName)) {
                    final List<Expression> partialArgs = Partial.partialMap.get(functionName).stream().map(Expressions::constant).collect(toList());
                    ListIterator<Expression> f = functionArgs.listIterator();
                    partialArgs.replaceAll(e -> {
                        if (WebFunctionVocabulary.var.getNames().contains(e.evaluate(valueSolution).value().toString())) {
                            if (f.hasNext()) {
                                return f.next();
                            } else {
                                return e;
                            }
                        } else {
                            return e;
                        }
                    });
                    f.forEachRemaining(partialArgs::add);

                    function = FunctionRegistry.Instance.get(WebFunctionVocabulary.call.getImmutableName(), partialArgs, null);
                } else {
                    try {
                        function = FunctionRegistry.Instance.get(functionName, functionArgs, null);
                    } catch (UnsupportedOperationException e) {
                        final ValueOrError[] valueOrErrors = getArgs().stream().map(exp -> exp.evaluate(valueSolution)).toArray(ValueOrError[]::new);
                        if (Arrays.stream(valueOrErrors).noneMatch(ValueOrError::isError)) {
                            final Value[] values = Arrays.stream(valueOrErrors).map(ValueOrError::value).toArray(Value[]::new);

                            // v0.3.0 host-callback context: nested wf:call reuses
                            // the outer binding; unbindIfOutermost clears only
                            // when this frame is the top of the stack.
                            // Pass the ValueSolution's MappingDictionary through
                            // so the v0.4 `invoke-wasm` host import (which lands
                            // inside a nested wf:call frame) can instantiate a
                            // recursive StardogWasmInstance bound to the same
                            // dictionary the caller is using.
                            final CallbackContext cbCtx =
                                CallbackContext.bind(valueSolution.getDictionary());
                            // Fuel metering Phase 1 — stamp the per-invocation
                            // budget + extension URI on the CallbackContext so
                            // host-callback tolls charge against this frame's
                            // budget and typed error attribution knows which
                            // extension tripped the cap.
                            // Fuel-metering Phase 1 attribution — capture the
                            // extension URI up-front (needed on both the success
                            // and trap paths). Filter-function wf:call has no
                            // ExecutionContext in scope, so no Stardog QueryId
                            // is available on this path — we record "".
                            final String attributionUri =
                                    values[0] == null ? "" : values[0].toString();
                            if (WebFunctionConfig.fuelEnabled()) {
                                cbCtx.setFuelMeteringContext(
                                        attributionUri,
                                        WebFunctionConfig.fuelPerInvocationMax(),
                                        WebFunctionConfig.fuelHostCallbackToll());
                            }
                            try (StardogWasmInstance stardogWasmInstance = StardogWasmInstance.from(values[0], valueSolution.getDictionary())) {
                                try(final SelectQueryResult selectQueryResult = stardogWasmInstance.evaluate(Arrays.stream(values).skip(1).toArray(Value[]::new))) {
                                    final ValueOrError result =
                                            stardogWasmInstance.selectQueryResultToValueOrError(selectQueryResult);
                                    AttributionRing.recordSuccess(
                                            attributionUri, cbCtx.tollUsed(), "");
                                    return result;
                                }
                            } catch (IOException | ExecutionException ex) {
                                final WfBudgetError typed = FuelTrapMapper.mapOrNull(ex, cbCtx);
                                if (typed != null) {
                                    AttributionRing.recordTrap(
                                            attributionUri, typed, cbCtx.tollUsed(), "");
                                    throw typed;
                                }
                                return ValueOrError.Error;
                            } catch (RuntimeException ex) {
                                // Direct throws from CallbackContext.chargeToll
                                // (WfBudgetError.HostCallbackTollExhausted) land
                                // here too — same promotion path.
                                final WfBudgetError typed = FuelTrapMapper.mapOrNull(ex, cbCtx);
                                if (typed != null) {
                                    AttributionRing.recordTrap(
                                            attributionUri, typed, cbCtx.tollUsed(), "");
                                    throw typed;
                                }
                                throw ex;
                            } finally {
                                CallbackContext.unbindIfOutermost(cbCtx);
                            }
                        } else {
                            return ValueOrError.Error;
                        }
                    }
                }
                return function.evaluate(valueSolution);
            } else {
                return ValueOrError.Error;
            }
        } else {
            return ValueOrError.Error;
        }
    }

    @Override
    public String getName() {
        return names.getImmutableName();
    }

    @Override
    public List<String> getNames() {
        return names.getNames();
    }


    @Override
    public String toString() {
        return names.name();
    }
}