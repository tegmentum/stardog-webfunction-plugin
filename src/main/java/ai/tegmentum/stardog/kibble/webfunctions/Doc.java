package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.plan.filter.AbstractExpression;
import com.complexible.stardog.plan.filter.Expression;
import com.complexible.stardog.plan.filter.ExpressionVisitor;
import com.complexible.stardog.plan.filter.ValueSolution;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.complexible.stardog.plan.filter.functions.UserDefinedFunction;
import com.stardog.stark.Value;
import com.stardog.stark.query.SelectQueryResult;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class Doc extends AbstractExpression implements UserDefinedFunction {

    private static final WebFunctionVocabulary names = WebFunctionVocabulary.doc;

    @Override
    public void initialize() {
    }

    public Doc() {
        super(new Expression[0]);
    }

    public Doc(final Doc call) {
        super(call);
    }

    @Override
    public ValueOrError evaluate(final ValueSolution valueSolution) {

        if (getArgs().size() >= 1) {

            final ValueOrError[] valueOrErrors = getArgs().stream().map(e -> e.evaluate(valueSolution)).toArray(ValueOrError[]::new);
            if (Arrays.stream(valueOrErrors).noneMatch(ValueOrError::isError)) {
                final Value[] values = Arrays.stream(valueOrErrors).map(ValueOrError::value).toArray(Value[]::new);

                try (StardogWasmInstance stardogWasmInstance = StardogWasmInstance.from(values[0], valueSolution.getDictionary())) {
                    try(final SelectQueryResult selectQueryResult = stardogWasmInstance.doc()) {
                        return stardogWasmInstance.selectQueryResultToValueOrError(selectQueryResult);
                    }
                } catch (IOException | ExecutionException e) {
                    return ValueOrError.Error;
                }
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
    public Doc copy() {
        return new Doc(this);
    }

    @Override
    public void accept(final ExpressionVisitor expressionVisitor) {
        expressionVisitor.visit(this);
    }
}