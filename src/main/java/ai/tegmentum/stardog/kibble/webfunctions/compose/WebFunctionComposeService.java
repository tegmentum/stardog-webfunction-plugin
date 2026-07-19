package ai.tegmentum.stardog.kibble.webfunctions.compose;

import ai.tegmentum.stardog.kibble.webfunctions.WebFunctionVocabulary;
import com.complexible.stardog.Kernel;
import com.complexible.stardog.plan.PlanException;
import com.complexible.stardog.plan.PlanNode;
import com.complexible.stardog.plan.QueryTerm;
import com.complexible.stardog.plan.ScanNode;
import com.complexible.stardog.plan.eval.ExecutionContext;
import com.complexible.stardog.plan.eval.service.ServiceQuery;
import com.complexible.stardog.plan.eval.service.SingleQueryService;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.complexible.stardog.plan.util.ScanCollector;
import com.google.inject.Inject;
import com.stardog.stark.BNode;
import com.stardog.stark.IRI;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SPARQL SERVICE trigger for the compose orchestrator plan-composition
 * surface.
 *
 * <p>Registered under
 * {@code http://semantalytics.com/2021/03/ns/stardog/webfunction/<version>/composePlan}.
 * The SERVICE body carries plan RDF (the {@code comp:CompositionPlan}
 * vocabulary — see {@code libs/compose-rdf}) plus a single
 * {@code wf:artifactUrl ?var} output-binding triple naming the SPARQL
 * variable that receives the composed artifact URL (default
 * {@code sha256://<hex>}).
 *
 * <p>End-to-end dispatch:
 * <ol>
 *   <li>Extract all triples from the SERVICE body via
 *       {@link ScanCollector} (SCAN-only body — no joins, unions,
 *       filters).</li>
 *   <li>Partition into plan triples (all constants) and the output
 *       binding (predicate {@code wf:artifactUrl}, object a variable).</li>
 *   <li>Serialize the plan triples as Turtle on the Java side.</li>
 *   <li>Ship the Turtle to the orchestrator's
 *       {@code sys:compose/rdf@1.0.0#plan-from-turtle} — get canonical
 *       CBOR plan bytes back.</li>
 *   <li>Hand the CBOR to {@link ComposeAdmin#compose(byte[])} — the
 *       plan gets composed, artifact-persisted, and RDF-projected
 *       under the composition named graph.</li>
 *   <li>Bind the composed artifact URL to the SERVICE's output variable
 *       and emit a single solution row.</li>
 * </ol>
 *
 * <p>The Guice binding lives in
 * {@code WebFunctionServiceModule#configure}; the {@link Kernel} injection
 * defers the {@link ComposeAdmin} + {@link ComposeOrchestratorClient} wire-up
 * until first use so a plugin loaded on a kernel that has no compose state
 * on disk (integration tests, cold-start scenarios) still gets a usable
 * service instance.
 */
public final class WebFunctionComposeService extends SingleQueryService<ServiceQuery> {

    /**
     * Output-binding predicate: {@code wf:artifactUrl ?var} in a SERVICE
     * body marks {@code ?var} as the variable receiving the composed
     * artifact URL. Only one such triple is permitted per SERVICE clause.
     */
    static final String WF_ARTIFACT_URL_PREDICATE_LOCAL = "artifactUrl";

    private final Kernel kernel;
    private volatile ComposeAdmin admin;
    private volatile ComposeOrchestratorClient client;

    @Inject
    public WebFunctionComposeService(final Kernel kernel) {
        this.kernel = Objects.requireNonNull(kernel, "kernel");
    }

    /**
     * Package-visible test constructor — wires pre-materialized
     * dependencies so unit tests don't need to spin up a full Kernel.
     */
    WebFunctionComposeService(final ComposeAdmin admin,
                              final ComposeOrchestratorClient client) {
        this.kernel = null;
        this.admin = Objects.requireNonNull(admin, "admin");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public boolean canEvaluate(final IRI iri) {
        return WebFunctionVocabulary.composePlan.getNames().contains(iri.toString());
    }

    @Override
    public ServiceQuery createQuery(final IRI iri, final PlanNode body,
                                    final ExecutionContext context) {
        final List<ScanNode> scans = ScanCollector.collect(body);
        if (scans.isEmpty()) {
            throw new PlanException("SERVICE " + iri + ": body is empty");
        }

        int outputVarId = -1;
        final List<TripleRow> planTriples = new ArrayList<>(scans.size());
        for (final ScanNode scan : scans) {
            final QueryTerm subject = scan.getSubject();
            final QueryTerm predicate = scan.getPredicate();
            final QueryTerm object = scan.getObject();

            if (!predicate.isConstant() || !isIri(predicate)) {
                throw new PlanException("SERVICE " + iri + ": predicate must be a constant IRI (got: "
                        + predicate + ")");
            }
            final IRI pred = (IRI) predicate.getValue().value();

            // Output-binding recognition: any wf:artifactUrl predicate
            // whose object is a variable names the SPARQL var receiving
            // the composed artifact URL. Only the local name is enforced
            // so mutable + immutable vocab versions both work.
            if (WF_ARTIFACT_URL_PREDICATE_LOCAL.equals(pred.localName())
                    && pred.namespace().startsWith(
                            "http://semantalytics.com/2021/03/ns/stardog/webfunction/")) {
                if (!object.isVariable()) {
                    throw new PlanException("SERVICE " + iri
                            + ": wf:artifactUrl object must be a variable (got constant: "
                            + object + ")");
                }
                if (outputVarId != -1) {
                    throw new PlanException("SERVICE " + iri
                            + ": multiple wf:artifactUrl output bindings; only one is permitted");
                }
                outputVarId = object.getName();
                continue;
            }

            // Everything else is a plan triple. All terms must be
            // constants (or blank nodes, which are constants in this
            // context — no free variables allowed in the plan).
            if (!subject.isConstant()) {
                throw new PlanException("SERVICE " + iri
                        + ": plan triple subject must be a constant (got variable: " + subject + ")");
            }
            if (!object.isConstant()) {
                throw new PlanException("SERVICE " + iri
                        + ": plan triple object must be a constant (got variable: " + object + ")");
            }
            planTriples.add(new TripleRow(
                    valueOf(subject),
                    pred,
                    valueOf(object)));
        }

        if (outputVarId == -1) {
            throw new PlanException("SERVICE " + iri
                    + ": missing wf:artifactUrl output binding — "
                    + "add `[] wf:artifactUrl ?var .` to the SERVICE body");
        }
        if (planTriples.isEmpty()) {
            throw new PlanException("SERVICE " + iri
                    + ": no plan triples (need at least a CompositionPlan subject typing)");
        }

        final String turtle = TurtleSerializer.render(planTriples);
        return new WebFunctionComposeServiceQuery(
                body, turtle, outputVarId,
                orchestratorClient(),
                composeAdmin());
    }

    // --- helpers ---------------------------------------------------

    private ComposeAdmin composeAdmin() {
        ComposeAdmin local = admin;
        if (local == null) {
            synchronized (this) {
                local = admin;
                if (local == null) {
                    local = ComposeAdmin.wire(kernel);
                    admin = local;
                }
            }
        }
        return local;
    }

    private ComposeOrchestratorClient orchestratorClient() {
        ComposeOrchestratorClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    // The admin holds the wired-up client — reuse it
                    // rather than re-instantiating the orchestrator.
                    local = composeAdmin().client();
                    client = local;
                }
            }
        }
        return local;
    }

    private static boolean isIri(final QueryTerm term) {
        final ValueOrError voe = term.getValue();
        return voe != null && !voe.isError() && voe.value() instanceof IRI;
    }

    private static Value valueOf(final QueryTerm term) {
        final ValueOrError voe = term.getValue();
        if (voe == null || voe.isError()) {
            throw new PlanException("SERVICE compose: term has no bound value: " + term);
        }
        return voe.value();
    }

    /**
     * Wire-shape record for a plan triple — subject/predicate/object
     * pinned to Stark {@link Value} so the serializer stays typed.
     */
    static final class TripleRow {
        final Value subject;
        final IRI predicate;
        final Value object;

        TripleRow(final Value subject, final IRI predicate, final Value object) {
            this.subject = subject;
            this.predicate = predicate;
            this.object = object;
        }
    }

    /**
     * Minimal Turtle serializer keyed to the compose vocabulary. Emits
     * an {@code @prefix comp:} header + one triple per line so the
     * output threads through
     * {@code sys:compose/rdf@1.0.0#plan-from-turtle} without any
     * dialect drift against the writer half in {@code compose-rdf}.
     *
     * <p>Deliberately hand-rolled to avoid a stark {@code TurtleWriter}
     * dep (the plugin's shade excludes stark I/O) — only IRIs, blank
     * nodes, and literals show up in a compose SERVICE body, so the
     * grammar covered is tight.
     */
    static final class TurtleSerializer {

        private static final String COMP_NS = "http://tegmentum.ai/ns/composition/";

        static String render(final List<TripleRow> triples) {
            final StringBuilder out = new StringBuilder();
            out.append("@prefix comp: <").append(COMP_NS).append("> .\n");
            out.append("@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n\n");
            for (final TripleRow row : triples) {
                out.append(formatTerm(row.subject))
                        .append(' ')
                        .append(formatPredicate(row.predicate))
                        .append(' ')
                        .append(formatTerm(row.object))
                        .append(" .\n");
            }
            return out.toString();
        }

        private static String formatPredicate(final IRI pred) {
            if ("http://www.w3.org/1999/02/22-rdf-syntax-ns#type".equals(pred.toString())) {
                return "a";
            }
            return formatIri(pred);
        }

        private static String formatTerm(final Value v) {
            if (v instanceof IRI) return formatIri((IRI) v);
            if (v instanceof BNode) return "_:" + safeBNodeLabel(((BNode) v).id());
            if (v instanceof Literal) {
                final Literal lit = (Literal) v;
                final StringBuilder sb = new StringBuilder();
                sb.append('"').append(escapeLiteral(lit.label())).append('"');
                final java.util.Optional<String> lang = lit.lang();
                if (lang.isPresent()) {
                    sb.append('@').append(lang.get());
                } else {
                    final IRI dt = lit.datatypeIRI();
                    // xsd:string is the default — omit for round-trip
                    // parity with compose-rdf's writer.
                    if (dt != null
                            && !"http://www.w3.org/2001/XMLSchema#string".equals(dt.toString())) {
                        sb.append("^^<").append(escapeIri(dt.toString())).append('>');
                    }
                }
                return sb.toString();
            }
            throw new PlanException("SERVICE compose: unsupported term type: "
                    + (v == null ? "null" : v.getClass().getName()));
        }

        private static String formatIri(final IRI iri) {
            final String s = iri.toString();
            if (s.startsWith(COMP_NS)) {
                final String local = s.substring(COMP_NS.length());
                if (isPnLocal(local)) return "comp:" + local;
            }
            return "<" + escapeIri(s) + ">";
        }

        private static boolean isPnLocal(final String s) {
            if (s.isEmpty()) return false;
            final char first = s.charAt(0);
            if (!(Character.isLetter(first) || first == '_')) return false;
            for (int i = 1; i < s.length(); i++) {
                final char c = s.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.')) return false;
            }
            return !s.endsWith(".");
        }

        private static String safeBNodeLabel(final String id) {
            final StringBuilder sb = new StringBuilder(id.length());
            for (int i = 0; i < id.length(); i++) {
                final char c = id.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                    sb.append(c);
                } else {
                    sb.append('x').append(Integer.toHexString(c));
                }
            }
            return sb.length() == 0 ? "b0" : sb.toString();
        }

        private static String escapeLiteral(final String s) {
            final StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                final char c = s.charAt(i);
                switch (c) {
                    case '\\': sb.append("\\\\"); break;
                    case '"':  sb.append("\\\""); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04X", (int) c));
                        else sb.append(c);
                }
            }
            return sb.toString();
        }

        private static String escapeIri(final String s) {
            final StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                final char c = s.charAt(i);
                switch (c) {
                    case '<': case '>': case '"': case '{': case '}':
                    case '|': case '^': case '`': case '\\':
                        sb.append(String.format("\\u%04X", (int) c));
                        break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04X", (int) c));
                        else sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}
