package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.plan.aggregates.WebFunctionAbstractAggregate;
import com.complexible.stardog.plan.filter.Expression;
import com.complexible.stardog.plan.filter.ValueSolution;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.complexible.stardog.plan.filter.functions.UserDefinedFunction;
import com.google.common.base.Preconditions;
import com.stardog.stark.Value;
import com.stardog.stark.query.SelectQueryResult;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class Aggregate extends WebFunctionAbstractAggregate implements UserDefinedFunction {

    private StardogWasmInstance instance;
    private static final WebFunctionVocabulary names = WebFunctionVocabulary.agg;

    @Override
    public void initialize() {
        super.initialize();
    }

    @Override
    public void reset() {
        if(instance != null) {
            instance.close();
        }
        instance = null;
    }

    public Aggregate() {
        super(names.getNames().toArray(new String[0]));
    }

    public Aggregate(final Aggregate aggregate) {
        super(aggregate);
    }

    @Override
    protected ValueOrError compute(final ValueSolution valueSolution) {

        final ValueOrError[] valueOrErrors = getArgs().stream().map(e -> e.evaluate(valueSolution)).toArray(ValueOrError[]::new);
        if (Arrays.stream(valueOrErrors).noneMatch(ValueOrError::isError)) {
            final Value[] values = Arrays.stream(valueOrErrors).map(ValueOrError::value).toArray(Value[]::new);

            try {
                if(instance == null) {
                    instance = StardogWasmInstance.from(values[0], valueSolution.getDictionary());
                }
                try {
                    try(final SelectQueryResult selectQueryResult = instance.compute(Arrays.stream(values).skip(1).toArray(Value[]::new), valueSolution.getMultiplicity())) {
                        return instance.selectQueryResultToValueOrError(selectQueryResult);
                    }
                } catch (IOException e) {
                    return ValueOrError.Error;
                }
            } catch (MalformedURLException | ExecutionException e) {
                return ValueOrError.Error;
            }
        } else {
            return ValueOrError.Error;
        }
    }

    @Override
    public void setArgs(final List<Expression> theArgs) {
        Preconditions.checkArgument(theArgs.size() >= 1, "Agg web function takes only at least one argument, 0 found");
        super.setArgs(theArgs);
    }

    @Override
    protected ValueOrError _getValue() {
        final ValueOrError result;
        if (instance != null) {
            try(final SelectQueryResult selectQueryResult = instance.aggregateGetValue()) {
                result = instance.selectQueryResultToValueOrError(selectQueryResult);
            }
            if (instance != null) {
                instance.close();
                instance = null;
            }
            return result;
        } else {
            return ValueOrError.Error;
        }
    }

    @Override
    protected ValueOrError aggregate(final long theMultiplicity, final Value theValue, final Value... theOtherValues) {
        // overriding compute
        return null;
    }

    @Override
    public com.complexible.stardog.plan.aggregates.Aggregate copy() {
        return new Aggregate(this);
    }
}