package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.plan.filter.ExpressionVisitor;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.complexible.stardog.plan.filter.functions.AbstractFunction;
import com.complexible.stardog.plan.filter.functions.UserDefinedFunction;
import com.google.common.collect.Range;
import com.stardog.stark.Value;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ExecutionException;

public class CacheLoad extends AbstractFunction implements UserDefinedFunction {

    private static final WebFunctionVocabulary names = WebFunctionVocabulary.cacheLoad;

    public CacheLoad() {
        super(Range.all(), names.getNames().toArray(new String[0]));
    }

    public CacheLoad(final CacheLoad clearCache) {
        super(clearCache);
    }

    @Override
    protected ValueOrError internalEvaluate(final Value... values) {
        for(Value value : values) {
            try {
                final URL wasmUrl = StardogWasmInstance.getWasmUrl(value);
               StardogWasmInstance.loadingCache.get(wasmUrl);
            } catch (ExecutionException | MalformedURLException e) {
                return ValueOrError.Error;
            }
        }
        return ValueOrError.General.of(null);
    }

    @Override
    public CacheLoad copy() {
        return new CacheLoad(this);
    }

    @Override
    public void accept(final ExpressionVisitor expressionVisitor) {
        expressionVisitor.visit(this);
    }
}

