package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.wit.WitList;
import ai.tegmentum.wasmtime4j.wit.WitOption;
import ai.tegmentum.wasmtime4j.wit.WitRecord;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitType;
import ai.tegmentum.wasmtime4j.wit.WitEnum;
import ai.tegmentum.wasmtime4j.wit.WitFloat64;
import ai.tegmentum.wasmtime4j.wit.WitU64;
import ai.tegmentum.wasmtime4j.wit.WitValue;
import ai.tegmentum.wasmtime4j.wit.WitVariant;

import com.complexible.common.rdf.model.ArrayLiteral;
import com.complexible.stardog.index.dictionary.MappingDictionary;
import com.complexible.stardog.index.statistics.Accuracy;
import com.complexible.stardog.index.statistics.Cardinality;
import com.stardog.stark.BNode;
import com.stardog.stark.IRI;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.Values;
import com.stardog.stark.query.Binding;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.BindingSets;
import com.stardog.stark.query.SelectQueryResult;
import com.stardog.stark.query.impl.SelectQueryResultImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Marshalling between Stardog values and the WIT value model.
 *
 * <p>The value model here mirrors the tegmentum:webfunction@0.1.0 base
 * (src/main/wit/base/types.wit) and the stardog:webfunction@0.3.0
 * overlay (src/main/wit/overlay/planner.wit). The plugin's runtime
 * dispatch (StardogWasmInstance) still uses the flat-world exports
 * (evaluate / aggregate-step / aggregate-finish / cardinality-estimate
 * / doc) rather than the base's sparql-extension world, so this
 * marshaller pairs those exports with the base's TYPE shapes:
 *
 * <ul>
 *   <li>{@code term} variant (4 arms — named-node / blank-node /
 *       literal / triple) instead of the pre-R5 {@code value} 3-arm
 *       variant. Quoted triples cannot flow through Stardog's planner,
 *       so a returned {@code triple(...)} arm is rejected with an
 *       IllegalArgumentException at the boundary.</li>
 *   <li>{@code literal.value} (was {@code label}),
 *       {@code literal.datatype: option<iri>} (was string), and
 *       {@code literal.language} (was {@code lang}).</li>
 *   <li>{@code binding.variable} (was {@code name}).</li>
 * </ul>
 *
 * <p>The test wasm crates at src/test/rust/{aggregate,function}/*_component
 * emit against a matching stardog:webfunction-test@0.3.0 test-only WIT
 * that reuses these type shapes verbatim; this marshaller and those
 * wasm artifacts therefore land the two sides of the type contract
 * together.
 *
 * <p>The full base sparql-extension world (extension.call + aggregate
 * resource lifecycle + property-function evaluate) requires
 * resource-handle plumbing in webassembly4j that hasn't yet been
 * designed for Java-side invocation; that migration is deferred and
 * tracked separately.
 */
public final class WitValueMarshaller {

    static final WitType LITERAL_TYPE;
    static final WitType FLAT_TERM_TYPE;
    static final WitType QUOTED_TRIPLE_TYPE;
    static final WitType TERM_TYPE;
    static final WitType BINDING_TYPE;
    static final WitType BINDING_SETS_TYPE;
    static final WitType ACCURACY_TYPE;
    static final WitType CARDINALITY_TYPE;

    static {
        final Map<String, WitType> literalFields = new LinkedHashMap<>();
        literalFields.put("value", WitType.createString());
        literalFields.put("datatype", WitType.option(WitType.createString()));
        literalFields.put("language", WitType.option(WitType.createString()));
        LITERAL_TYPE = WitType.record("literal", literalFields);

        final Map<String, Optional<WitType>> flatTermCases = new LinkedHashMap<>();
        flatTermCases.put("named-node", Optional.of(WitType.createString()));
        flatTermCases.put("blank-node", Optional.of(WitType.createString()));
        flatTermCases.put("literal", Optional.of(LITERAL_TYPE));
        FLAT_TERM_TYPE = WitType.variant("flat-term", flatTermCases);

        final Map<String, WitType> quotedTripleFields = new LinkedHashMap<>();
        quotedTripleFields.put("subject", FLAT_TERM_TYPE);
        quotedTripleFields.put("predicate", FLAT_TERM_TYPE);
        quotedTripleFields.put("object", FLAT_TERM_TYPE);
        QUOTED_TRIPLE_TYPE = WitType.record("quoted-triple", quotedTripleFields);

        final Map<String, Optional<WitType>> termCases = new LinkedHashMap<>();
        termCases.put("named-node", Optional.of(WitType.createString()));
        termCases.put("blank-node", Optional.of(WitType.createString()));
        termCases.put("literal", Optional.of(LITERAL_TYPE));
        termCases.put("triple", Optional.of(QUOTED_TRIPLE_TYPE));
        TERM_TYPE = WitType.variant("term", termCases);

        final Map<String, WitType> bindingFields = new LinkedHashMap<>();
        bindingFields.put("variable", WitType.createString());
        bindingFields.put("value", TERM_TYPE);
        BINDING_TYPE = WitType.record("binding", bindingFields);

        final Map<String, WitType> bindingSetsFields = new LinkedHashMap<>();
        bindingSetsFields.put("vars", WitType.list(WitType.createString()));
        bindingSetsFields.put("rows", WitType.list(WitType.list(BINDING_TYPE)));
        BINDING_SETS_TYPE = WitType.record("binding-sets", bindingSetsFields);

        ACCURACY_TYPE = WitType.enumType(
                "accuracy",
                java.util.Arrays.asList(
                        "verified",
                        "injected",
                        "accurate",
                        "mostly-accurate",
                        "probably-accurate",
                        "possibly-off",
                        "probably-off",
                        "random"));

        final Map<String, WitType> cardinalityFields = new LinkedHashMap<>();
        cardinalityFields.put("value", WitType.createFloat64());
        cardinalityFields.put("accuracy", ACCURACY_TYPE);
        CARDINALITY_TYPE = WitType.record("cardinality", cardinalityFields);
    }

    private final MappingDictionary dictionary;

    public WitValueMarshaller(final MappingDictionary dictionary) {
        this.dictionary = dictionary;
    }

    // ---- Stardog → WIT ------------------------------------------------------

    public WitValue toWit(final Value value) {
        if (value instanceof IRI) {
            return WitVariant.of(TERM_TYPE, "named-node", witString(value.toString()));
        }
        if (value instanceof BNode) {
            return WitVariant.of(TERM_TYPE, "blank-node", witString(((BNode) value).id()));
        }
        if (value instanceof Literal) {
            final Literal literal = (Literal) value;
            if (literal.datatypeIRI().equals(ArrayLiteral.ARRAY)) {
                throw new UnsupportedOperationException(
                        "ArrayLiteral is not supported in the component ABI; WIT does not permit direct recursive types");
            }
            return WitVariant.of(TERM_TYPE, "literal", toWitLiteral(literal));
        }
        throw new IllegalArgumentException("Unsupported Value type: " + value.getClass().getName());
    }

    private WitRecord toWitLiteral(final Literal literal) {
        final WitType optionStringType = WitType.option(WitType.createString());
        // datatype is option<iri> in the base WIT: absent means xsd:string
        // per RDF 1.1 defaulting. Stardog carries xsd:string explicitly for
        // simple literals; encode it explicitly here rather than reflecting
        // it as None so round-trips preserve the observed datatype without
        // an implicit defaulting step across the boundary.
        return WitRecord.builder()
                .field("value", witString(literal.label()))
                .field("datatype", (WitValue) WitOption.some(optionStringType,
                        witString(literal.datatypeIRI().toString())))
                .field("language", literal.lang().<WitValue>map(WitValueMarshaller::witString)
                        .map(v -> (WitValue) WitOption.some(optionStringType, v))
                        .orElseGet(() -> WitOption.none(optionStringType)))
                .build();
    }

    /**
     * Wrap {@link WitString#of} to reroute its checked {@link
     * ai.tegmentum.wasmtime4j.exception.ValidationException} to an
     * {@link IllegalArgumentException}; the sparql-extension dispatch
     * path in {@link StardogWasmInstance} passes function names and
     * argument strings through here so it doesn't have to declare a
     * wasmtime4j-internal checked exception on every call site.
     */
    static WitString witString(final String s) {
        try {
            return WitString.of(s);
        } catch (ai.tegmentum.wasmtime4j.exception.ValidationException e) {
            throw new IllegalArgumentException("invalid UTF-8 string for WIT: " + s, e);
        }
    }

    public WitList toWitArgs(final Value[] args) {
        if (args.length == 0) {
            return WitList.empty(TERM_TYPE);
        }
        final List<WitValue> elems = new ArrayList<>(args.length);
        for (Value v : args) {
            elems.add(toWit(v));
        }
        return WitList.of(elems);
    }

    public WitRecord toWitCardinality(final Cardinality cardinality) {
        return WitRecord.builder()
                .field("value", WitFloat64.of(cardinality.value()))
                .field("accuracy", accuracyToWit(cardinality.accuracy()))
                .build();
    }

    private WitValue accuracyToWit(final Accuracy accuracy) {
        return WitEnum.of(ACCURACY_TYPE, accuracy.name().toLowerCase().replace('_', '-'));
    }

    // ---- WIT-typed unmarshalling (from ComponentInstance.invokeWit return) ----

    public Value valueFromWit(final WitValue witValue) {
        final WitVariant variant = (WitVariant) witValue;
        switch (variant.getCaseName()) {
            case "named-node":
                return Values.iri(((WitString) variant.getPayload()
                        .orElseThrow(() -> missingPayload("named-node"))).getValue());
            case "blank-node":
                return Values.bnode(((WitString) variant.getPayload()
                        .orElseThrow(() -> missingPayload("blank-node"))).getValue());
            case "literal":
                return literalFromWit((WitRecord) variant.getPayload()
                        .orElseThrow(() -> missingPayload("literal")));
            case "triple":
                // Quoted triples are first-class in the base WIT `term` but
                // Stardog's planner surface has no notion of them. Reject
                // rather than lossily encode; downstream call paths should
                // never receive a triple arm from a well-behaved extension.
                throw new IllegalArgumentException(
                        "term variant 'triple' (RDF-star quoted triple) is not supported at the Stardog boundary");
            default:
                throw new IllegalArgumentException("Unknown term case: " + variant.getCaseName());
        }
    }

    private static IllegalArgumentException missingPayload(final String kase) {
        return new IllegalArgumentException("term variant '" + kase + "' is missing payload");
    }

    @SuppressWarnings("unchecked")
    private Literal literalFromWit(final WitRecord record) {
        final String value = ((WitString) record.getField("value")).getValue();
        final Optional<Object> datatypeOpt = ((WitOption) record.getField("datatype")).toJava();
        final Optional<Object> languageOpt = ((WitOption) record.getField("language")).toJava();
        if (languageOpt.isPresent()) {
            return Values.literal(value, (String) languageOpt.get());
        }
        if (datatypeOpt.isPresent()) {
            return Values.literal(value, Values.iri((String) datatypeOpt.get()));
        }
        // RDF 1.1: absent datatype + absent language ≡ xsd:string.
        return Values.literal(value);
    }

    /**
     * Wrap a single {@code term} returned by {@code extension.call} or
     * {@code aggregate-state.finish} into a {@link SelectQueryResult}
     * carrying one row with a single binding under the well-known
     * {@code value_0} variable name. The plugin's callers (Filter,
     * Aggregate, Compose, Reduce, ...) still consume the module-mode
     * {@link SelectQueryResult} shape; this keeps the sparql-extension
     * component-mode dispatch a drop-in replacement without changing
     * the plugin's higher-level ABI.
     */
    public SelectQueryResult singleTermToSelectQueryResult(final WitValue witValue) {
        final Value value = valueFromWit(witValue);
        final BindingSets.Builder bsb = BindingSets.builder();
        bsb.add("value_0", value);
        return new SelectQueryResultImpl(
                Collections.singletonList("value_0"),
                Collections.singletonList(bsb.build()));
    }

    public SelectQueryResult bindingSetsFromWit(final WitValue witValue) {
        final WitRecord record = (WitRecord) witValue;
        final List<String> vars = new ArrayList<>();
        for (WitValue v : ((WitList) record.getField("vars")).getElements()) {
            vars.add(((WitString) v).getValue());
        }
        final List<BindingSet> rows = new ArrayList<>();
        for (WitValue rowVal : ((WitList) record.getField("rows")).getElements()) {
            final BindingSets.Builder bsb = BindingSets.builder();
            for (WitValue bindingVal : ((WitList) rowVal).getElements()) {
                final WitRecord binding = (WitRecord) bindingVal;
                bsb.add(((WitString) binding.getField("variable")).getValue(),
                        valueFromWit(binding.getField("value")));
            }
            rows.add(bsb.build());
        }
        return new SelectQueryResultImpl(vars, rows);
    }

    public Cardinality cardinalityFromWit(final WitValue witValue) {
        final WitRecord record = (WitRecord) witValue;
        final double value = ((WitFloat64) record.getField("value")).getValue();
        final String accName = ((WitEnum) record.getField("accuracy")).getDiscriminant();
        final Accuracy accuracy = Accuracy.valueOf(accName.toUpperCase().replace('-', '_'));
        return Cardinality.of(value, accuracy);
    }

    // Empty binding-sets record for aggregate-step returning result<_, string>.
    public static WitRecord emptyBindingSets() {
        return WitRecord.builder()
                .field("vars", WitList.empty(WitType.createString()))
                .field("rows", WitList.empty(WitType.list(BINDING_TYPE)))
                .build();
    }

    // Java List<Binding> → single-row binding-sets for tests.
    public static WitRecord singleRowBindingSets(
            final List<String> vars,
            final List<WitValue> rowValues) {
        final WitList.Builder rowsBuilder = WitList.builder(WitType.list(BINDING_TYPE));
        final WitList.Builder rowBuilder = WitList.builder(BINDING_TYPE);
        for (int i = 0; i < vars.size(); i++) {
            rowBuilder.add(WitRecord.builder()
                    .field("variable", witString(vars.get(i)))
                    .field("value", rowValues.get(i))
                    .build());
        }
        rowsBuilder.add(rowBuilder.build());
        final List<WitValue> varStrings = new ArrayList<>();
        for (String v : vars) varStrings.add(witString(v));
        return WitRecord.builder()
                .field("vars", vars.isEmpty()
                        ? WitList.empty(WitType.createString())
                        : WitList.of(varStrings))
                .field("rows", rowsBuilder.build())
                .build();
    }

    @SuppressWarnings("unused")
    private static final List<WitValue> EMPTY_VALUE_LIST = Collections.emptyList();
}
