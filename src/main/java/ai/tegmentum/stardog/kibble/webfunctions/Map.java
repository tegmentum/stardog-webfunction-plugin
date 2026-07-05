package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.common.rdf.model.ArrayLiteral;
import com.complexible.stardog.index.dictionary.MappingDictionary;
import com.complexible.stardog.plan.filter.*;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.complexible.stardog.plan.filter.functions.FunctionRegistry;
import com.complexible.stardog.plan.filter.functions.UserDefinedFunction;
import com.google.common.collect.Lists;
import com.stardog.stark.Value;

import java.util.Arrays;
import java.util.List;

import static com.complexible.stardog.plan.filter.functions.AbstractFunction.assertArrayLiteral;
import static java.util.stream.Collectors.toList;

public final class Map extends AbstractExpression implements UserDefinedFunction {

    private static final WebFunctionVocabulary names = WebFunctionVocabulary.map;

    public Map() {
        super(new Expression[0]);
    }

    private Map(final Map map) {
        super(map);
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
    public Map copy() {
        return new Map(this);
    }

    @Override
    public void accept(final ExpressionVisitor expressionVisitor) {
        expressionVisitor.visit(this);
    }

    @Override
    public ValueOrError evaluate(final ValueSolution valueSolution) {

        if(getArgs().size() == 2) {
            final ValueOrError firstArgValueOrError = getFirstArg().evaluate(valueSolution);
            if(firstArgValueOrError.isError()) {
                return ValueOrError.Error;
            } else {
                final Value functionIri = firstArgValueOrError.value();

                final ValueOrError secondArgValueOrError = getSecondArg().evaluate(valueSolution);
                if(secondArgValueOrError.isError()) {
                    return ValueOrError.Error;
                } else {
                    final ArrayLiteral elements;

                    if (assertArrayLiteral(secondArgValueOrError.value())) {
                        elements = (ArrayLiteral) secondArgValueOrError.value();
                        MappingDictionary dictionary = valueSolution.getDictionary();

                        try {
                            List<ValueOrError> elementResults = Arrays.stream(elements.getValues())
                                    .mapToObj(dictionary::getValue)
                                    .map(v -> FunctionRegistry
                                            .Instance
                                            .get(WebFunctionVocabulary.call.getImmutableName(), Lists.newArrayList(Expressions.constant(functionIri), Expressions.constant(v)), null)
                                            .evaluate(valueSolution))
                                    .collect(toList());

                            if(elementResults.stream().anyMatch(ValueOrError::isError)) {
                                return ValueOrError.Error;
                            } else {
                                long[] elementResultIds = elementResults.stream().map(ValueOrError::value).map(dictionary::add).mapToLong(Long::longValue).toArray();
                                return ValueOrError.General.of(new ArrayLiteral(elementResultIds));
                            }
                        } catch(UnsupportedOperationException e) {
                            return ValueOrError.Error;
                        }
                    } else {
                        return ValueOrError.Error;
                    }
                }
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