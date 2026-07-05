package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.common.base.Options;
import com.complexible.stardog.db.ConnectableConnection;
import com.complexible.stardog.index.dictionary.MappingDictionary;
import com.complexible.stardog.index.statistics.Accuracy;
import com.complexible.stardog.index.statistics.Cardinality;
import com.complexible.stardog.plan.*;
import com.complexible.stardog.plan.eval.ExecutionContext;
import com.complexible.stardog.plan.eval.operator.*;
import com.complexible.stardog.plan.eval.service.PlanNodeBodyServiceQuery;
import com.complexible.stardog.plan.eval.service.ServiceQuery;
import com.complexible.stardog.plan.filter.expr.Constant;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.complexible.stardog.plan.util.QueryTermRenderer;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.stardog.stark.Value;
import com.stardog.stark.Values;
import com.stardog.stark.query.io.QueryResultFormat;
import com.stardog.stark.query.io.QueryResultFormats;

import java.net.MalformedURLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.UnaryOperator;

import static com.stardog.stark.Values.iri;
import static java.util.stream.Collectors.*;

public class WebFunctionServiceQuery extends PlanNodeBodyServiceQuery {

    private PlanNode thePlanNode;
    private Value webFunctionIRI;
    private static final QueryResultFormat FORMAT = QueryResultFormats.JSON;
    private List<QueryTerm> args;
    private List<QueryTerm> results;
    private StardogWasmInstance stardogWasmInstance;

    public Value getWasm() {
        return webFunctionIRI;
    }

    public List<QueryTerm> getResultVars() {
        return results;
    }

    public List<QueryTerm> getInput() {
        return args;
    }

    public WebFunctionServiceQuery(final PlanNode body, Value webFunctionIRI, final List<QueryTerm> args, final List<QueryTerm> results) {
        super(iri(WebFunctionVocabulary.service.getImmutableName()), body);
        this.thePlanNode = body;
        this.webFunctionIRI = webFunctionIRI;
        this.args = args;
        this.results = results;
        try {
            this.stardogWasmInstance = StardogWasmInstance.from(webFunctionIRI);
        } catch (ExecutionException | MalformedURLException e) {
            throw new PlanException(e);
        }
    }

    @Override
    public SolutionIterator evaluate(final ExecutionContext theContext,
                                     final Operator theOperator,
                                     final PlanVarInfo theVarInfo) throws OperatorException {

        return new WebFunctionServiceOperator(theContext, webFunctionIRI, args, results, theOperator, stardogWasmInstance);
    }

    @Override
    public Set<Integer> getRequiredUnboundOutputs() {
        return results.stream().map(QueryTerm::getName).filter(i -> i != -1).collect(toSet());

    }

    @Override
    public Set<Integer> getRequiredInputBindings() {
        return this.args.stream().filter(QueryTerm::isVariable).map(QueryTerm::getName).collect(toSet());
    }

    @Override
    public ImmutableSet<Integer> getAssuredVars() {
        return thePlanNode.getAllVars();
    }

    @Override
    public ImmutableSet<Integer> getAllVars() {
        return thePlanNode.getAllVars();
    }

    @Override
    public String explain(PlanVarInfo planVarInfo, QueryTermRenderer queryTermRenderer) {
        return String.format("%s(%s) -> ?%s",
                this.webFunctionIRI,
                this.args.stream().map(queryTerm -> {
                    if (queryTerm.isConstant()) {
                        return Value.lex(queryTerm.getValue().value());
                    } else {
                        return planVarInfo.getName(queryTerm.getName());
                    }
                }).collect(joining(", ")),
                this.results.stream().map(queryTerm -> {
                    if (queryTerm.isConstant()) {
                        return Value.lex(queryTerm.getValue().value());
                    } else {
                        return planVarInfo.getName(queryTerm.getName());
                    }
                }).collect(joining(", "))
        );
    }

    @Override
    public String explainVerbose(PlanVarInfo planVarInfo, QueryTermRenderer queryTermRenderer) {
        return explain(planVarInfo, queryTermRenderer);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.getRequiredInputBindings(), this.getRequiredUnboundOutputs(), thePlanNode, this.serviceTerm());
    }

    @Override
    public boolean equals(Object object) {
        if (!this.getClass().equals(object.getClass())) {
            return false;
        } else {
            ServiceQuery q = (ServiceQuery) object;
            return this.getRequiredInputBindings().equals(q.getRequiredInputBindings()) && this.getRequiredUnboundOutputs().equals(q.getRequiredUnboundOutputs()) && Objects.equal(this.serviceTerm(), q.serviceTerm());
        }
    }

    @Override
    public PlanNodeBodyServiceQueryBuilder toBuilder() {
        return new WebFunctionServiceQuery.WebFunctionServiceQueryBuilder().body(body());
    }

    private static class WebFunctionServiceQueryBuilder extends PlanNodeBodyServiceQuery.PlanNodeBodyServiceQueryBuilder {

        @Override
        public PlanNodeBodyServiceQuery.PlanNodeBodyServiceQueryBuilder replaceConstants(MappingDictionary dictionary, UnaryOperator<Constant> mapping, boolean performValidation) {
            Preconditions.checkArgument(!performValidation);

            return new PlanNodeBodyServiceQuery.CanonicalizedPlanNodeBodyServiceQueryBuilder(this).body(replaceBodyConstants(dictionary, mapping, false));
        }

        @Override
        public PlanNodeBodyServiceQuery build() {
            return WebFunctionService.createWebFunctionQuery(mBody);
        }
    }

    @Override
    public Cardinality estimateCardinality(ConnectableConnection theConn, Options theOptions) {
        List<ValueOrError> argsValueOrError = args.stream().map(queryTerm -> {
            if(queryTerm.isVariable()) {
                return ValueOrError.General.of(Values.iri(WebFunctionVocabulary.var.getImmutableName()));
            } else {
                return queryTerm.getValue();
            }
        }).collect(toList());
        if(argsValueOrError.stream().anyMatch(ValueOrError::isError)) {
            throw new PlanException("Unable to generate cardinalty estimates");
        }

        Cardinality cardinality = stardogWasmInstance.getCardinality(thePlanNode.getCardinality(), argsValueOrError.stream().map(ValueOrError::value).collect(toList()));
        return Cardinality.of(cardinality.value(), Accuracy.takeLessAccurate(cardinality.accuracy(), thePlanNode.getCardinality().accuracy()));
    }
}