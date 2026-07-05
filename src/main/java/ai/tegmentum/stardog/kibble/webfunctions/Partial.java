package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.plan.filter.AbstractExpression;
import com.complexible.stardog.plan.filter.Expression;
import com.complexible.stardog.plan.filter.ExpressionVisitor;
import com.complexible.stardog.plan.filter.ValueSolution;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.complexible.stardog.plan.filter.functions.UserDefinedFunction;
import com.stardog.stark.BNode;
import com.stardog.stark.Value;
import com.stardog.stark.Values;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;

public final class Partial extends AbstractExpression implements UserDefinedFunction {

    private static final WebFunctionVocabulary names = WebFunctionVocabulary.partial;

    static Map<String, List<Value>> partialMap = new HashMap<>();

    public Partial() {
        super(new Expression[0]);
    }

    public Partial(final Partial partial) {
        super(partial);
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
    public ValueOrError evaluate(final ValueSolution valueSolution) {
        final BNode compositeFunctionName = Values.bnode();
        if(getArgs().size() >= 2) {
            List<ValueOrError> argsValueOrError = getArgs().stream().map(e -> e.evaluate(valueSolution)).collect(toList());
            if (argsValueOrError.stream().noneMatch(ValueOrError::isError)) {
                List<Value> values = argsValueOrError.stream().map(ValueOrError::value).collect(toList());
                partialMap.put(compositeFunctionName.id(), values);
                return ValueOrError.General.of(compositeFunctionName);
            } else {
                return ValueOrError.Error;
            }
        } else {
            return ValueOrError.Error;
        }
    }

    @Override
    public Partial copy() {
        return new Partial(this);
    }

    @Override
    public void accept(ExpressionVisitor expressionVisitor) {
        expressionVisitor.visit(this);
    }

    @Override
    public void initialize() {
        partialMap.clear();
    }
}
