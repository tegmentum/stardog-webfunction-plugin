package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.common.rdf.model.ArrayLiteral;
import com.complexible.stardog.plan.filter.AbstractExpression;
import com.complexible.stardog.plan.filter.Expression;
import com.complexible.stardog.plan.filter.ExpressionVisitor;
import com.complexible.stardog.plan.filter.ValueSolution;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.complexible.stardog.plan.filter.functions.UserDefinedFunction;
import com.stardog.stark.Values;

import java.net.URL;
import java.util.List;

public class CacheList extends AbstractExpression implements UserDefinedFunction {

    private static final WebFunctionVocabulary names = WebFunctionVocabulary.cacheList;

    public CacheList() {
        super(new Expression[0]);
    }

    public CacheList(final CacheList cacheList) {
        super(cacheList);
    }

    @Override
    public ValueOrError evaluate(final ValueSolution valueSolution) {
        if (getArgs().isEmpty()) {
            return StardogWasmInstance.loadingCache
                    .asMap()
                    .keySet()
                    .stream()
                    .map(URL::toString)
                    .map(Values::literal)
                    .map(valueSolution.getDictionary()::add)
                    .map(ArrayLiteral::new)
                    .map(ValueOrError.General::of)
                    .findFirst()
                    .orElse(ValueOrError.General.of(null));
        } else {
            return ValueOrError.Error;
        }
    }

    @Override
    public CacheList copy() {
        return new CacheList(this);
    }

    @Override
    public void accept(final ExpressionVisitor expressionVisitor) {
        expressionVisitor.visit(this);
    }

    @Override
    public String getName() {
        return names.getImmutableName();
    }

    @Override
    public List<String> getNames() {
        return names.getNames();
    }
}

