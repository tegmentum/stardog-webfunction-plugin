package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.plan.filter.*;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.complexible.stardog.plan.filter.functions.FunctionRegistry;
import com.complexible.stardog.plan.filter.functions.UserDefinedFunction;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static com.complexible.stardog.plan.filter.functions.AbstractFunction.assertIntegerLiteral;
import static java.util.stream.Collectors.toList;

public final class Memoize extends AbstractExpression implements UserDefinedFunction {

    private static final WebFunctionVocabulary names = WebFunctionVocabulary.memoize;

    private Cache<Integer, ValueOrError> cache;

    public Memoize() {
        super(new Expression[0]);
    }

    @Override
    public void initialize() {
        cache = null;
    }

    private Memoize(final Memoize memoize) {
        super(memoize);
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
    public Memoize copy() {
        return new Memoize(this);
    }

    @Override
    public void accept(final ExpressionVisitor expressionVisitor) {
        expressionVisitor.visit(this);
    }

    @Override
    public ValueOrError evaluate(ValueSolution valueSolution) {

        //TODO single arg to allow default for cache size
        if(getArgs().size() >= 2) {
            final ValueOrError firstArgValueOrError = getFirstArg().evaluate(valueSolution);
            if(!firstArgValueOrError.isError() && assertIntegerLiteral(firstArgValueOrError.value())) {
                final long cacheSize = Literal.longValue((Literal)firstArgValueOrError.value());

                if(cache == null) {
                    cache = CacheBuilder.newBuilder().maximumSize(cacheSize).build();
                }

                final ValueOrError secondArgValueOrError = getSecondArg().evaluate(valueSolution);

                if(!secondArgValueOrError.isError()) {

                    Value functionIri = secondArgValueOrError.value();

                    final List<Expression> functionArgs = getArgs().stream().skip(2).collect(toList());

                    Optional<ValueOrError> cachedValueOrError = Optional.ofNullable(cache.getIfPresent(Objects.hash(functionIri, functionArgs)));

                    if (cachedValueOrError.isPresent()) {
                        return cachedValueOrError.get();
                    } else {
                        try {
                            final ValueOrError valueOrError = FunctionRegistry.Instance.get(WebFunctionVocabulary.call.getImmutableName(), Stream.concat(Stream.of(Expressions.constant(functionIri)), functionArgs.stream()).collect(toList()), null).evaluate(valueSolution);
                            cache.put(Objects.hash(functionIri, functionArgs), valueOrError);
                            return valueOrError;
                        } catch(UnsupportedOperationException e) {
                            return ValueOrError.Error;
                        }
                    }
                } else {
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
    public String toString() {
        return names.name();
    }
}