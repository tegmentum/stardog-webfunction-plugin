package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.wasmtime4j.component.ComponentVariant;
import ai.tegmentum.webassembly4j.api.WitHostFunction;

import com.stardog.stark.BNode;
import com.stardog.stark.IRI;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.Values;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.SelectQueryResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Host callbacks satisfying the v0.3.0 WIT world's {@code host} import
 * interface. Uses {@link ComponentVal} at the linker boundary — same pattern
 * as the RDF4J and Jena bindings, adapted to Stardog's {@link Value} hierarchy.
 */
public final class HostCallbacks {

    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private HostCallbacks() {}

    /** {@code execute-query: func(sparql: string, bindings: list<binding>,
     *  max-rows: option<u32>) -> result<binding-sets, string>}. */
    public static WitHostFunction executeQuery() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound — needs SERVICE wf:call to "
                    + "carry the ExecutionContext through")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                final Map<String, Value> initial = decodeBindings((ComponentVal) args[1]);
                final int rowCap = decodeOptionalU32((ComponentVal) args[2]).orElseGet(ctx::maxRows);

                ctx.enter();
                try (SelectQueryResult rs = ctx.executeSelect(sparql, initial)) {
                    return new Object[] { ComponentVal.ok(encodeBindingSets(rs, rowCap)) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /** {@code follow-predicate: func(subject: value, predicate: value)
     *  -> result<list<value>, string>}  (v0.3.3). */
    public static WitHostFunction followPredicate() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound")) };
            }
            try {
                final Value subj = decodeNode((ComponentVal) args[0]);
                final Value pred = decodeNode((ComponentVal) args[1]);
                ctx.enter();
                try {
                    final java.util.List<Value> objs = ctx.followPredicate(subj, pred);
                    final java.util.List<ComponentVal> encoded =
                        new java.util.ArrayList<>(objs.size());
                    for (Value v : objs) encoded.add(encodeNode(v));
                    return new Object[] { ComponentVal.ok(ComponentVal.list(encoded)) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /** {@code prepare-query: func(sparql: string) -> result<u32, string>}
     *  (v0.3.2). */
    public static WitHostFunction prepareQuery() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                return new Object[] { ComponentVal.ok(ComponentVal.u32((long) ctx.prepare(sparql))) };
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /** {@code run-prepared: func(handle: u32, bindings: list<binding>,
     *  max-rows: option<u32>) -> result<binding-sets, string>}  (v0.3.2). */
    public static WitHostFunction runPrepared() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound")) };
            }
            try {
                final int handle = (int) ((ComponentVal) args[0]).asU32();
                final Map<String, Value> initial = decodeBindings((ComponentVal) args[1]);
                final int rowCap = decodeOptionalU32((ComponentVal) args[2]).orElseGet(ctx::maxRows);
                ctx.enter();
                try (SelectQueryResult rs = ctx.runPrepared(handle, initial)) {
                    return new Object[] { ComponentVal.ok(encodeBindingSets(rs, rowCap)) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /** {@code execute-update: func(sparql: string, bindings: list<binding>)
     *  -> result<_, string>}  (v0.3.1). */
    public static WitHostFunction executeUpdate() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: no context bound — SERVICE wf:call binds one, "
                    + "filter-function wf:call does not")) };
            }
            try {
                final String sparql = ((ComponentVal) args[0]).asString();
                final Map<String, Value> initial = decodeBindings((ComponentVal) args[1]);
                ctx.enter();
                try {
                    ctx.executeUpdate(sparql, initial);
                    return new Object[] { ComponentVal.ok(null) };
                } finally {
                    ctx.exit();
                }
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /** {@code callback-depth: func() -> u32}. */
    public static WitHostFunction callbackDepth() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            return new Object[] { ComponentVal.u32(ctx == null ? 0L : (long) ctx.depth()) };
        };
    }

    // ---- marshalling -------------------------------------------------------

    private static Map<String, Value> decodeBindings(final ComponentVal list) {
        final Map<String, Value> map = new LinkedHashMap<>();
        for (ComponentVal elem : list.asList()) {
            final Map<String, ComponentVal> fields = elem.asRecord();
            final String name = fields.get("name").asString();
            map.put(name, decodeNode(fields.get("value")));
        }
        return map;
    }

    private static Value decodeNode(final ComponentVal variant) {
        final ComponentVariant cv = variant.asVariant();
        final String caseName = cv.getCaseName();
        final ComponentVal payload = cv.getPayload().orElse(null);

        switch (caseName) {
            case "iri":
                return Values.iri(payload == null ? "" : payload.asString());
            case "bnode":
                return Values.bnode(payload == null ? "" : payload.asString());
            case "literal": {
                if (payload == null) {
                    throw new IllegalStateException("wf: literal variant has no payload");
                }
                final Map<String, ComponentVal> fields = payload.asRecord();
                final String label = fields.get("label").asString();
                final String datatype = fields.get("datatype").asString();
                final Optional<ComponentVal> lang = fields.get("lang").asSome();
                if (lang.isPresent()) {
                    return Values.literal(label, lang.get().asString());
                }
                return Values.literal(label, Values.iri(datatype));
            }
            default:
                throw new IllegalStateException("wf: unknown value variant case: " + caseName);
        }
    }

    private static Optional<Integer> decodeOptionalU32(final ComponentVal option) {
        return option.asSome().map(v -> (int) v.asU32());
    }

    private static ComponentVal encodeBindingSets(final SelectQueryResult rs, final int rowCap) {
        final List<String> vars = rs.variables();
        final LinkedHashSet<String> varsSeen = new LinkedHashSet<>(vars);
        final List<ComponentVal> rows = new ArrayList<>();
        int rowsSeen = 0;
        while (rs.hasNext() && rowsSeen < rowCap) {
            final BindingSet bs = rs.next();
            final List<ComponentVal> bindings = new ArrayList<>();
            for (String var : vars) {
                final Value v = bs.get(var);
                if (v == null) continue;
                final Map<String, ComponentVal> bindingFields = new LinkedHashMap<>();
                bindingFields.put("name", ComponentVal.string(var));
                bindingFields.put("value", encodeNode(v));
                bindings.add(ComponentVal.record(bindingFields));
            }
            rows.add(ComponentVal.list(bindings));
            rowsSeen++;
        }
        final List<ComponentVal> varsVals = new ArrayList<>();
        for (String v : varsSeen) varsVals.add(ComponentVal.string(v));

        final Map<String, ComponentVal> bindingSetsRec = new LinkedHashMap<>();
        bindingSetsRec.put("vars", ComponentVal.list(varsVals));
        bindingSetsRec.put("rows", ComponentVal.list(rows));
        return ComponentVal.record(bindingSetsRec);
    }

    private static ComponentVal encodeNode(final Value v) {
        if (v instanceof IRI) {
            return ComponentVal.variant("iri", ComponentVal.string(v.toString()));
        }
        if (v instanceof BNode) {
            return ComponentVal.variant("bnode", ComponentVal.string(((BNode) v).id()));
        }
        if (v instanceof Literal) {
            final Literal lit = (Literal) v;
            final String label = lit.label();
            String datatype = lit.datatypeIRI().toString();
            if (datatype == null || datatype.isEmpty()) datatype = XSD_STRING;
            final Map<String, ComponentVal> fields = new LinkedHashMap<>();
            fields.put("label", ComponentVal.string(label));
            fields.put("datatype", ComponentVal.string(datatype));
            final Optional<String> lang = lit.lang();
            fields.put("lang", lang.isPresent()
                    ? ComponentVal.some(ComponentVal.string(lang.get()))
                    : ComponentVal.none());
            return ComponentVal.variant("literal", ComponentVal.record(fields));
        }
        throw new IllegalArgumentException("wf: unsupported Value kind: " + v);
    }
}
