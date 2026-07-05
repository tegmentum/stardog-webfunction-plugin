package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.plan.PlanNode;
import com.complexible.stardog.plan.QueryTerm;
import com.complexible.stardog.plan.eval.ExecutionContext;
import com.complexible.stardog.plan.eval.service.*;
import com.complexible.stardog.plan.filter.Expressions;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.stardog.stark.IRI;
import com.stardog.stark.Value;
import com.stardog.stark.Values;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.stream.Collectors.toList;

final class WebFunctionService extends SingleQueryService<WebFunctionServiceQuery> {

    //TODO move to WebFunctionVocabulary

    @Override
    public boolean canEvaluate(final IRI theIRI) {
        return WebFunctionVocabulary.service.getNames().contains(theIRI.toString());
    }

    @Override
    public WebFunctionServiceQuery createQuery(final IRI theIRI, final PlanNode body, final ExecutionContext theContext) {
        return createWebFunctionQuery(body);
    }

    public static WebFunctionServiceQuery createWebFunctionQuery(final PlanNode theBody) {
        Map<QueryTerm, ServiceParameters> subjToParams = ServiceParameterUtils.build(theBody);
        Preconditions.checkArgument(subjToParams.size() == 1, "Parameters must correspond to a single subject");
        ServiceParameters params = subjToParams.values().iterator().next();
        (new WebFunctionService.WebFunctionServiceParamValidator(params)).validate();
        Value webFunctionValue = WebFunctionVocabulary.call.getNames().stream().map(Values::iri).map(params::first).filter(Optional::isPresent).map(Optional::get).findFirst()
                .filter(Expressions::isConstant)
                .map((t) -> t.getValue().value())
                .orElse(null);
        //TODO function is constanct might want to relax that later
        return createServiceQuery(theBody, webFunctionValue, params);
    }

    private static WebFunctionServiceQuery createServiceQuery(PlanNode body, Value webFunctionValue, ServiceParameters params) {
        List<QueryTerm> args = WebFunctionVocabulary.args.getNames().stream().map(Values::iri).map(params::get).filter(l -> !l.isEmpty()).findFirst().orElse(Collections.emptyList());
        List<QueryTerm> results = WebFunctionVocabulary.result.getNames().stream().map(Values::iri).map(params::get).filter(l -> !l.isEmpty()).findFirst().get();
        return new WebFunctionServiceQuery(body, webFunctionValue, args, results);
    }

    private static class WebFunctionServiceParamValidator extends Validator {

        private final ServiceParameters mParameters;

        WebFunctionServiceParamValidator(ServiceParameters theParameters) {
            super(theParameters);
            this.mParameters = theParameters;
        }

        @Override
        public void validate() {
            Preconditions.checkArgument(WebFunctionVocabulary.call.getNames().stream().map(Values::iri).filter(iri -> mParameters.contains(iri)).count() == 1);
            Preconditions.checkArgument(WebFunctionVocabulary.args.getNames().stream().map(Values::iri).filter(iri -> mParameters.contains(iri)).count() <= 1);
            Preconditions.checkArgument(WebFunctionVocabulary.result.getNames().stream().map(Values::iri).filter(iri -> mParameters.contains(iri)).count() == 1);
            Set<IRI> allParameters = Sets.newHashSet(mParameters.predicates());
            allParameters.removeAll(WebFunctionVocabulary.call.getNames().stream().map(Values::iri).collect(toList()));
            allParameters.removeAll(WebFunctionVocabulary.args.getNames().stream().map(Values::iri).collect(toList()));
            allParameters.removeAll(WebFunctionVocabulary.result.getNames().stream().map(Values::iri).collect(toList()));
            Preconditions.checkArgument(allParameters.isEmpty());
            IRI callParameter = mParameters.predicates().stream().filter(iri -> WebFunctionVocabulary.call.getNames().stream().map(Values::iri).collect(toList()).contains(iri)).findFirst().get();
            IRI resultParameter = mParameters.predicates().stream().filter(v -> WebFunctionVocabulary.result.getNames().stream().map(Values::iri).collect(toList()).contains(v)).findFirst().get();
            Preconditions.checkArgument(mParameters.get(callParameter).size() == 1 && mParameters.get(callParameter).get(0).isConstant());
            Preconditions.checkArgument(mParameters.get(resultParameter).size() >= 1);
            mParameters.get(resultParameter).stream().forEach(QueryTerm::isVariable);
        }
    }
}