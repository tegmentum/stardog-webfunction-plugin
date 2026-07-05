package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.plan.filter.ExpressionVisitor;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.complexible.stardog.plan.filter.functions.AbstractFunction;
import com.complexible.stardog.plan.filter.functions.UserDefinedFunction;
import com.google.common.collect.Range;
import com.stardog.stark.Value;

import java.net.MalformedURLException;
import java.net.URL;

public class CacheClear extends AbstractFunction implements UserDefinedFunction {

    private static final WebFunctionVocabulary names = WebFunctionVocabulary.cacheClear;

    public CacheClear() {
        super(Range.all(), names.getNames().toArray(new String[0]));
    }

    public CacheClear(final CacheClear cacheClear) {
        super(cacheClear);
    }

    @Override
    protected ValueOrError internalEvaluate(final Value... values) {
        if(values.length == 0) {
            StardogWasmInstance.loadingCache.invalidateAll();
        } else {
            for (Value value : values) {
                try {
                    final URL wasmUrl = StardogWasmInstance.getWasmUrl(value);
                    StardogWasmInstance.loadingCache.invalidate(wasmUrl);
                } catch (MalformedURLException e) {
                    return ValueOrError.Error;
                }
            }
        }
        return ValueOrError.General.of(null);
    }


    @Override
    public CacheClear copy() {
        return new CacheClear(this);
    }

    @Override
    public void accept(final ExpressionVisitor expressionVisitor) {
        expressionVisitor.visit(this);
    }
}

