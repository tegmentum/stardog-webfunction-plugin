package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.wit.WitList;
import ai.tegmentum.wasmtime4j.wit.WitOption;
import ai.tegmentum.wasmtime4j.wit.WitRecord;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitType;
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
 * Marshalling between Stardog values and the WIT value model declared in
 * src/main/wit/webfunction.wit. All WitType instances mirror the WIT world exactly.
 */
public final class WitValueMarshaller {

    static final WitType LITERAL_TYPE;
    static final WitType VALUE_TYPE;
    static final WitType BINDING_TYPE;
    static final WitType BINDING_SETS_TYPE;
    static final WitType ACCURACY_TYPE;
    static final WitType CARDINALITY_TYPE;

    static {
        final Map<String, WitType> literalFields = new LinkedHashMap<>();
        literalFields.put("label", WitType.createString());
        literalFields.put("datatype", WitType.createString());
        literalFields.put("lang", WitType.option(WitType.createString()));
        LITERAL_TYPE = WitType.record("literal", literalFields);

        final Map<String, Optional<WitType>> valueCases = new LinkedHashMap<>();
        valueCases.put("iri", Optional.of(WitType.createString()));
        valueCases.put("literal", Optional.of(LITERAL_TYPE));
        valueCases.put("bnode", Optional.of(WitType.createString()));
        VALUE_TYPE = WitType.variant("value", valueCases);

        final Map<String, WitType> bindingFields = new LinkedHashMap<>();
        bindingFields.put("name", WitType.createString());
        bindingFields.put("value", VALUE_TYPE);
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
            return WitVariant.of(VALUE_TYPE, "iri", witString(value.toString()));
        }
        if (value instanceof BNode) {
            return WitVariant.of(VALUE_TYPE, "bnode", witString(((BNode) value).id()));
        }
        if (value instanceof Literal) {
            final Literal literal = (Literal) value;
            if (literal.datatypeIRI().equals(ArrayLiteral.ARRAY)) {
                throw new UnsupportedOperationException(
                        "ArrayLiteral is not supported in the component ABI; WIT does not permit direct recursive types");
            }
            return WitVariant.of(VALUE_TYPE, "literal", toWitLiteral(literal));
        }
        throw new IllegalArgumentException("Unsupported Value type: " + value.getClass().getName());
    }

    private WitRecord toWitLiteral(final Literal literal) {
        final WitType optionStringType = WitType.option(WitType.createString());
        return WitRecord.builder()
                .field("label", witString(literal.label()))
                .field("datatype", witString(literal.datatypeIRI().toString()))
                .field("lang", literal.lang().<WitValue>map(WitValueMarshaller::witString)
                        .map(v -> (WitValue) WitOption.some(optionStringType, v))
                        .orElseGet(() -> WitOption.none(optionStringType)))
                .build();
    }

    private static WitString witString(final String s) {
        try {
            return WitString.of(s);
        } catch (ai.tegmentum.wasmtime4j.exception.ValidationException e) {
            throw new IllegalArgumentException("invalid UTF-8 string for WIT: " + s, e);
        }
    }

    public WitList toWitArgs(final Value[] args) {
        if (args.length == 0) {
            return WitList.empty(VALUE_TYPE);
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
        // Enum values are represented as WitVariants with no payload in this SDK.
        return WitVariant.of(ACCURACY_TYPE, accuracy.name().toLowerCase().replace('_', '-'));
    }

    // ---- Java-shape unmarshalling (from ComponentInstance.invoke return) ------
    //
    // JniComponentInstanceImpl.invoke returns WitValue.toJava() rather than the raw
    // WitValue tree, so the return path receives plain Java shapes:
    //   variant → Map{ "case": String, "payload": <recursively> }
    //   record  → Map<String, Object>
    //   list    → List<Object>
    //   option  → Optional<Object>
    //   result  → Map{ "isOk": Boolean, "ok"|"err": <recursively> }
    //   enum    → treated same as variant (case name, no payload)
    // Documenting the asymmetry with the WitValue-typed input path for the upstream
    // reader.

    @SuppressWarnings("unchecked")
    public Value valueFromJava(final Object obj) {
        final Map<String, Object> m = (Map<String, Object>) obj;
        final String kase = (String) m.get("case");
        final Object payload = m.get("payload");
        switch (kase) {
            case "iri":
                return Values.iri((String) payload);
            case "bnode":
                return Values.bnode((String) payload);
            case "literal":
                return literalFromJava((Map<String, Object>) payload);
            default:
                throw new IllegalArgumentException("Unknown value case: " + kase);
        }
    }

    private Literal literalFromJava(final Map<String, Object> record) {
        final String label = (String) record.get("label");
        final String datatype = (String) record.get("datatype");
        final Object langObj = record.get("lang");
        if (langObj instanceof Optional && ((Optional<?>) langObj).isPresent()) {
            return Values.literal(label, (String) ((Optional<?>) langObj).get());
        }
        return Values.literal(label, Values.iri(datatype));
    }

    @SuppressWarnings("unchecked")
    public SelectQueryResult bindingSetsFromJava(final Object obj) {
        final Map<String, Object> m = (Map<String, Object>) obj;
        final List<String> vars = new ArrayList<>();
        for (Object v : (List<Object>) m.get("vars")) {
            vars.add((String) v);
        }
        final List<BindingSet> rows = new ArrayList<>();
        for (Object row : (List<Object>) m.get("rows")) {
            final BindingSets.Builder bsb = BindingSets.builder();
            for (Object bindingObj : (List<Object>) row) {
                final Map<String, Object> binding = (Map<String, Object>) bindingObj;
                bsb.add((String) binding.get("name"), valueFromJava(binding.get("value")));
            }
            rows.add(bsb.build());
        }
        return new SelectQueryResultImpl(vars, rows);
    }

    @SuppressWarnings("unchecked")
    public Cardinality cardinalityFromJava(final Object obj) {
        final Map<String, Object> m = (Map<String, Object>) obj;
        final double value = ((Number) m.get("value")).doubleValue();
        final Map<String, Object> accMap = (Map<String, Object>) m.get("accuracy");
        final String accName = (String) accMap.get("case");
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
                    .field("name", witString(vars.get(i)))
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
