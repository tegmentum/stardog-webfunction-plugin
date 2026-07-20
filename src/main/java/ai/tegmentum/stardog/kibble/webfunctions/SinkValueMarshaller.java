package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.wasmtime4j.component.ComponentVariant;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.TripleTerm;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * WIT {@code term} variant &lt;-&gt; RDF4J {@link Value} marshaller for the
 * sink-SPARQL evaluator. Sibling of {@link SinkStatementMarshaller}
 * (whole-quad rollup) and {@link SinkBindingMarshaller} (WIT
 * {@code binding} record encoder).
 *
 * <p>WIT term arms (from {@code types.wit}):
 * <ul>
 *   <li>{@code named-node(iri)} &lt;-&gt; {@link IRI}</li>
 *   <li>{@code blank-node(string)} &lt;-&gt; {@link BNode}. The id
 *       round-trips verbatim: on inbound we call
 *       {@link ValueFactory#createBNode(String)} so the same id key
 *       matches across load / query. Blank-node identity is scoped to
 *       the per-invocation {@link org.eclipse.rdf4j.sail.memory.MemoryStore},
 *       so id collisions across separate invocations are impossible
 *       by construction.</li>
 *   <li>{@code literal(record{value, datatype?, language?})} &lt;-&gt;
 *       {@link Literal}. Absent {@code datatype} maps to xsd:string on
 *       the inbound; xsd:string maps back to absent {@code datatype}
 *       on the outbound (matches the WIT contract's RDF 1.1 defaulting
 *       rule and mirrors {@link HostCallbacks#encodeTermV1(com.stardog.stark.Value)}
 *       for the base graph-callbacks path).</li>
 *   <li>{@code triple(quoted-triple)} &lt;-&gt; {@link TripleTerm}.
 *       Recursion is bounded at depth 1 by the WIT's {@code flat-term}
 *       inner shape (no {@code triple} arm inside a quoted triple).</li>
 * </ul>
 */
public final class SinkValueMarshaller {

    static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private SinkValueMarshaller() {}

    /**
     * Marshal a WIT {@code term} variant into an RDF4J {@link Value}.
     * The returned value is created via the supplied {@link ValueFactory}
     * so it interoperates with whichever Sail is loading the quads.
     *
     * @throws IllegalArgumentException on a malformed variant (unknown
     *         arm name, missing required record fields, non-{@code
     *         named-node} predicate inside a quoted triple).
     */
    public static Value fromWitTerm(final ComponentVal witTerm, final ValueFactory vf) {
        if (witTerm == null) {
            throw new IllegalArgumentException(
                    "SinkValueMarshaller.fromWitTerm: null term");
        }
        final ComponentVariant variant = witTerm.asVariant();
        final String armName = variant.getCaseName();
        switch (armName) {
            case "named-node":
                return vf.createIRI(payloadAsString(variant, "named-node"));
            case "blank-node":
                return vf.createBNode(payloadAsString(variant, "blank-node"));
            case "literal":
                return literalFromWit(variant.getPayload().orElseThrow(
                        () -> new IllegalArgumentException(
                                "WIT literal variant missing payload record")),
                        vf);
            case "triple":
                return tripleTermFromWit(variant.getPayload().orElseThrow(
                        () -> new IllegalArgumentException(
                                "WIT triple variant missing payload record")),
                        vf);
            default:
                throw new IllegalArgumentException(
                        "SinkValueMarshaller.fromWitTerm: unknown WIT term arm '"
                                + armName + "'");
        }
    }

    /**
     * Marshal an RDF4J {@link Value} into a WIT {@code term} variant.
     * Every {@code named-node} the {@link ValueFactory} produced flows
     * back verbatim; every {@link Literal} with {@code xsd:string}
     * datatype flows back with {@code datatype = none} per the WIT
     * defaulting rule.
     *
     * @throws IllegalArgumentException on an rdf4j Value shape the
     *         marshaller does not recognise (should not fire — Value's
     *         5-way {@code getType()} covers every subtype).
     */
    public static ComponentVal toWitTerm(final Value v) {
        if (v == null) {
            throw new IllegalArgumentException(
                    "SinkValueMarshaller.toWitTerm: null value");
        }
        if (v.isIRI()) {
            return ComponentVal.variant("named-node",
                    ComponentVal.string(((IRI) v).stringValue()));
        }
        if (v.isBNode()) {
            return ComponentVal.variant("blank-node",
                    ComponentVal.string(((BNode) v).getID()));
        }
        if (v.isLiteral()) {
            return ComponentVal.variant("literal", literalToWit((Literal) v));
        }
        if (v.isTripleTerm()) {
            return ComponentVal.variant("triple", tripleTermToWit((TripleTerm) v));
        }
        throw new IllegalArgumentException(
                "SinkValueMarshaller.toWitTerm: unsupported rdf4j Value type: "
                        + v.getClass().getName());
    }

    /** Convenience: {@link #fromWitTerm} but constrained to a
     *  {@link Resource} (subject / graph position). Throws if the term
     *  is a plain literal. */
    static Resource fromWitTermResource(final ComponentVal witTerm, final ValueFactory vf) {
        final Value v = fromWitTerm(witTerm, vf);
        if (v instanceof Resource) return (Resource) v;
        throw new IllegalArgumentException(
                "SinkValueMarshaller: expected Resource (iri / bnode / triple-term) "
                        + "at subject or graph position, got: " + v);
    }

    /** Convenience: {@link #fromWitTerm} but constrained to an
     *  {@link IRI} (predicate position). */
    static IRI fromWitTermIri(final ComponentVal witTerm, final ValueFactory vf) {
        final Value v = fromWitTerm(witTerm, vf);
        if (v instanceof IRI) return (IRI) v;
        throw new IllegalArgumentException(
                "SinkValueMarshaller: expected IRI at predicate position, got: " + v);
    }

    private static Literal literalFromWit(final ComponentVal record, final ValueFactory vf) {
        final Map<String, ComponentVal> fields = record.asRecord();
        final ComponentVal valueField = fields.get("value");
        if (valueField == null) {
            throw new IllegalArgumentException(
                    "SinkValueMarshaller: WIT literal record missing 'value' field");
        }
        final String label = valueField.asString();

        final ComponentVal langField = fields.get("language");
        final Optional<String> lang = langField == null
                ? Optional.empty()
                : langField.asSome().map(ComponentVal::asString);
        if (lang.isPresent()) {
            return vf.createLiteral(label, lang.get());
        }

        final ComponentVal dtField = fields.get("datatype");
        final Optional<String> dtIri = dtField == null
                ? Optional.empty()
                : dtField.asSome().map(ComponentVal::asString);
        if (dtIri.isPresent()) {
            return vf.createLiteral(label, vf.createIRI(dtIri.get()));
        }
        // datatype = none, language = none -> xsd:string per WIT defaulting.
        return vf.createLiteral(label);
    }

    private static ComponentVal literalToWit(final Literal lit) {
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("value", ComponentVal.string(lit.getLabel()));

        final Optional<String> lang = lit.getLanguage();
        if (lang.isPresent()) {
            // A language-tagged literal's datatype is rdf:langString by
            // RDF 1.1; the WIT contract expresses it via the language
            // slot alone and leaves datatype absent to mirror how the
            // base encoder ({@link HostCallbacks#encodeTermV1}) surfaces
            // language literals to the guest.
            fields.put("datatype", ComponentVal.none());
            fields.put("language", ComponentVal.some(ComponentVal.string(lang.get())));
            return ComponentVal.record(fields);
        }

        final IRI dt = lit.getDatatype();
        final String dtIri = dt == null ? null : dt.stringValue();
        if (dtIri == null || dtIri.isEmpty() || XSD_STRING.equals(dtIri)) {
            fields.put("datatype", ComponentVal.none());
        } else {
            fields.put("datatype", ComponentVal.some(ComponentVal.string(dtIri)));
        }
        fields.put("language", ComponentVal.none());
        return ComponentVal.record(fields);
    }

    private static TripleTerm tripleTermFromWit(final ComponentVal record, final ValueFactory vf) {
        final Map<String, ComponentVal> fields = record.asRecord();
        final Value subj = flatTermFromWit(requiredField(fields, "subject", "triple"), vf);
        final Value pred = flatTermFromWit(requiredField(fields, "predicate", "triple"), vf);
        final Value obj  = flatTermFromWit(requiredField(fields, "object", "triple"), vf);
        if (!(subj instanceof Resource)) {
            throw new IllegalArgumentException(
                    "SinkValueMarshaller: quoted-triple subject must be resource, got: " + subj);
        }
        if (!(pred instanceof IRI)) {
            throw new IllegalArgumentException(
                    "SinkValueMarshaller: quoted-triple predicate must be IRI, got: " + pred);
        }
        return vf.createTripleTerm((Resource) subj, (IRI) pred, obj);
    }

    private static ComponentVal tripleTermToWit(final TripleTerm t) {
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("subject", flatTermToWit(t.getSubject()));
        fields.put("predicate", flatTermToWit(t.getPredicate()));
        fields.put("object", flatTermToWit(t.getObject()));
        return ComponentVal.record(fields);
    }

    /**
     * The WIT {@code flat-term} variant is a strict 3-arm subset of
     * {@code term} — same encoding for named-node / blank-node / literal
     * (an inner triple is out of scope at v0.1 per {@code types.wit}
     * &sect;flat-term). Reuse {@link #fromWitTerm} and reject a nested
     * {@code triple} arm at the boundary.
     */
    private static Value flatTermFromWit(final ComponentVal witFlat, final ValueFactory vf) {
        final String arm = witFlat.asVariant().getCaseName();
        if ("triple".equals(arm)) {
            throw new IllegalArgumentException(
                    "SinkValueMarshaller: WIT flat-term rejects nested 'triple' arm "
                            + "(RDF-star depth > 1 unsupported at v0.1)");
        }
        return fromWitTerm(witFlat, vf);
    }

    /** Encode a Resource / IRI / Literal as a WIT {@code flat-term};
     *  a nested {@link TripleTerm} would collapse to a triple arm the
     *  WIT does not permit at v0.1 so it maps to a placeholder BNode
     *  ({@code _:triple-out-of-range}) rather than blowing up
     *  mid-query. */
    private static ComponentVal flatTermToWit(final Value v) {
        if (v.isTripleTerm()) {
            return ComponentVal.variant("blank-node",
                    ComponentVal.string("triple-out-of-range"));
        }
        return toWitTerm(v);
    }

    private static String payloadAsString(final ComponentVariant variant, final String armName) {
        return variant.getPayload().orElseThrow(() -> new IllegalArgumentException(
                "WIT " + armName + " variant missing string payload")).asString();
    }

    private static ComponentVal requiredField(final Map<String, ComponentVal> fields,
                                              final String name,
                                              final String recordShape) {
        final ComponentVal v = fields.get(name);
        if (v == null) {
            throw new IllegalArgumentException(
                    "SinkValueMarshaller: WIT " + recordShape
                            + " record missing '" + name + "' field");
        }
        return v;
    }
}
