package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.plan.filter.ExpressionVisitor;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.complexible.stardog.plan.filter.functions.AbstractFunction;
import com.complexible.stardog.plan.filter.functions.UserDefinedFunction;
import com.google.common.collect.Range;
import com.stardog.stark.Value;

import java.net.MalformedURLException;
import java.net.URL;

public class CacheRefresh extends AbstractFunction implements UserDefinedFunction {

    private static final WebFunctionVocabulary names = WebFunctionVocabulary.cacheRefresh;

    public CacheRefresh() {
        super(Range.all(), names.getNames().toArray(new String[0]));
    }

    public CacheRefresh(final CacheRefresh clearCache) {
        super(clearCache);
    }

    @Override
    protected ValueOrError internalEvaluate(final Value... values) {
        if(values.length == 0) {
            StardogWasmInstance.loadingCache.asMap().keySet().stream().forEach(StardogWasmInstance.loadingCache::refresh);
        } else {
            for (Value value : values) {
                try {
                    final URL wasmUrl = StardogWasmInstance.getWasmUrl(value);
                    StardogWasmInstance.loadingCache.refresh(wasmUrl);
                } catch (MalformedURLException e) {
                    return ValueOrError.Error;
                }
            }
        }
        return ValueOrError.General.of(null);
    }

    @Override
    public CacheRefresh copy() {
        return new CacheRefresh(this);
    }

    @Override
    public void accept(final ExpressionVisitor expressionVisitor) {
        expressionVisitor.visit(this);
    }
}

