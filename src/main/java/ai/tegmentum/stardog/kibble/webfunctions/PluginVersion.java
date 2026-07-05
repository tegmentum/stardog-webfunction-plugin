package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.plan.filter.ExpressionVisitor;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.complexible.stardog.plan.filter.functions.AbstractFunction;
import com.complexible.stardog.plan.filter.functions.UserDefinedFunction;
import com.stardog.stark.Value;

import static com.stardog.stark.Values.*;

public class PluginVersion extends AbstractFunction implements UserDefinedFunction {

    private static final WebFunctionVocabulary names = WebFunctionVocabulary.pluginVersion;

    public PluginVersion() {
        super(0, names.getNames().toArray(new String[0]));
    }

    public PluginVersion(final PluginVersion pluginVersion) {
        super(pluginVersion);
    }

    @Override
    protected ValueOrError internalEvaluate(final Value... values) {
        return ValueOrError.General.of(literal(Version.PLUGIN_VERSION));
    }


    @Override
    public PluginVersion copy() {
        return new PluginVersion(this);
    }

    @Override
    public void accept(final ExpressionVisitor expressionVisitor) {
        expressionVisitor.visit(this);
    }

}

