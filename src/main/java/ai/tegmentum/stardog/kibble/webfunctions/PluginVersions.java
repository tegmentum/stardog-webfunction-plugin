package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.common.base.Streams;
import com.complexible.common.rdf.model.ArrayLiteral;
import com.complexible.stardog.plan.filter.AbstractExpression;
import com.complexible.stardog.plan.filter.Expression;
import com.complexible.stardog.plan.filter.ExpressionVisitor;
import com.complexible.stardog.plan.filter.ValueSolution;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.complexible.stardog.plan.filter.functions.UserDefinedFunction;
import com.stardog.stark.Values;
import com.vdurmont.semver4j.Semver;

import java.util.List;
import java.util.ServiceLoader;

public class PluginVersions extends AbstractExpression implements UserDefinedFunction {

    private static final WebFunctionVocabulary names = WebFunctionVocabulary.pluginVersions;

    public PluginVersions() {
        super(new Expression[0]);
    }

    public PluginVersions(final PluginVersions pluginVersions) {
        super(pluginVersions);
    }

    @Override
    public ValueOrError evaluate(final ValueSolution valueSolution) {

        if (getArgs().size() == 0) {
            final Semver latest = Streams.stream(ServiceLoader.load(PluginVersionService.class).iterator()).map(PluginVersionService::pluginVersion).map(Semver::new).max(Semver::compareTo).orElse(new Semver("0.0.0"));
            return ValueOrError.General.of(new ArrayLiteral(Streams.stream(ServiceLoader.load(PluginVersionService.class).iterator()).map(PluginVersionService::pluginVersion).map(Semver::new).map(semver ->
                    new ArrayLiteral(valueSolution.getDictionary().add(Values.literal(semver.toString())), valueSolution.getDictionary().add(Values.literal(semver.isEqualTo(latest))))).mapToLong(valueSolution.getDictionary()::add).toArray()));
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
    public PluginVersions copy() {
        return new PluginVersions(this);
    }

    @Override
    public void accept(final ExpressionVisitor expressionVisitor) {
        expressionVisitor.visit(this);
    }
}
