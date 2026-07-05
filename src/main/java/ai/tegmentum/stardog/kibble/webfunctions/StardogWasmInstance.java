package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.common.rdf.model.ArrayLiteral;
import com.complexible.stardog.StardogException;
import com.complexible.stardog.index.dictionary.MappingDictionary;
import com.complexible.stardog.index.statistics.Accuracy;
import com.complexible.stardog.index.statistics.Cardinality;
import com.complexible.stardog.plan.PlanException;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import com.google.common.collect.Streams;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.Values;
import com.stardog.stark.query.Binding;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.BindingSets;
import com.stardog.stark.query.SelectQueryResult;
import com.stardog.stark.query.impl.SelectQueryResultImpl;
import com.stardog.stark.query.io.QueryResultFormats;
import com.stardog.stark.query.io.QueryResultParsers;
import com.stardog.stark.query.io.QueryResultWriters;
import ai.tegmentum.webassembly4j.api.Component;
import ai.tegmentum.webassembly4j.api.ComponentInstance;
import ai.tegmentum.webassembly4j.api.DefaultLinkingContext;
import ai.tegmentum.webassembly4j.api.Engine;
import ai.tegmentum.webassembly4j.api.Function;
import ai.tegmentum.webassembly4j.api.HostFunction;
import ai.tegmentum.webassembly4j.api.Instance;
import ai.tegmentum.webassembly4j.api.Memory;
import ai.tegmentum.webassembly4j.api.Module;
import ai.tegmentum.webassembly4j.api.ValueType;
import ai.tegmentum.webassembly4j.api.WebAssembly;
import ai.tegmentum.wasmtime4j.wit.WitRecord;
import ai.tegmentum.wasmtime4j.wit.WitResult;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitValue;
import com.complexible.stardog.security.ActionType;
import com.complexible.stardog.security.ShiroUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static com.stardog.stark.Values.iri;
import static java.util.stream.Collectors.toList;

public class StardogWasmInstance implements Closeable {

    public static final String WASM_FUNCTION_MALLOC = "malloc";
    public static final String WASM_FUNCTION_FREE = "free";
    public static final String WASM_FUNCTION_MAPPING_DICTIONARY_GET = "mapping_dictionary_get";
    public static final String WASM_FUNCTION_MAPPING_DICTIONARY_ADD = "mapping_dictionary_add";
    public static final String WASM_FUNCTION_AGGREGATE = "aggregate";
    public static final String WASM_FUNCTION_GET_VALUE = "get_value";
    public static final String WASM_FUNCTION_EVALUATE = "evaluate";
    public static final String WASM_FUNCTION_CARDINALITY_ESTIMATE = "cardinality_estimate";
    public static final String WASM_FUNCTION_DOC = "doc";

    public static final long WASM_PAGE_SIZE = 64 * FileUtils.ONE_KB;

    static LoadingCache<URL, byte[]> loadingCache = CacheBuilder
            .newBuilder()
            .softValues()
            .build(new CacheLoader<URL, byte[]>() {
                @Override
                public byte[] load(URL url) throws IOException {
                    return getWasm(url);
                }
            });

    private static byte[] getWasm(final URL wasmUrl) throws IOException {

        final URLConnection conn = wasmUrl.openConnection();
        //TODO This should be configurable
        conn.setConnectTimeout(240000);
        conn.setReadTimeout(240000);
        conn.connect();

        try(final ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            IOUtils.copy(conn.getInputStream(), baos);
            return baos.toByteArray();
        }
    }

    private Engine engine;
    private Module module;
    private Component component;

    private final AtomicReference<MappingDictionary> mappingDictionaryRef = new AtomicReference<>();
    private Instance instance;
    private boolean closed = false;
    private boolean mappingDictionaryIsSet;

    public boolean isMappingDictionaryIsSet() {
        return mappingDictionaryIsSet;
    }

    public MappingDictionary getMappingDictionary() {
        return mappingDictionaryRef.get();
    }

    public static URL getWasmUrl(final Value value) throws MalformedURLException {
        if (value instanceof Literal) {
            return new URL(((Literal) value).label());
        } else {
            return new URL(value.toString() + '/' + Version.PLUGIN_VERSION);
        }
    }

    public static class WasmMemoryRef {
        public int addr;
        public int len;
        //TODO not sure if these should be int or long

        public static WasmMemoryRef from(final int addr, final int len) {
            return new WasmMemoryRef(addr, len);
        }

        public WasmMemoryRef(final int addr, final int len) {
            this.addr = addr;
            this.len = len;
        }
    }

    private HostFunction mappingDictionaryGetHostFunction() {
        return (Object... args) -> {
            final long id = ((Number) args[0]).longValue();
            final Value value = mappingDictionaryRef.get().getValue(id);

            WasmMemoryRef buf = null;
            //TODO fix this. possible NPE
            try {
                buf = writeToWasmMemory("memory", new Value[]{value});
            } catch (IOException e) {
                //TODO ???
            }

            return new Object[]{buf.addr};
        };
    }

    private HostFunction mappingDictionaryAddHostFunction() {
        return (Object... args) -> {
            final int addr = ((Number) args[0]).intValue();
            final long result = Arrays.stream(readFromWasmMemory("memory", addr))
                    .map(mappingDictionaryRef.get()::add)
                    .findFirst()
                    .orElse(-1L);
            return new Object[]{result};
        };
    }

    public static StardogWasmInstance from(final Value wasmURL) throws ExecutionException, MalformedURLException {
        final URL url = getWasmUrl(wasmURL);
        checkExecutePermission(url);
        return new StardogWasmInstance(url);
    }

    public static StardogWasmInstance from(final Value wasmURL, final MappingDictionary mappingDictionary) throws ExecutionException, MalformedURLException {
        final URL url = getWasmUrl(wasmURL);
        checkExecutePermission(url);
        return new StardogWasmInstance(url, mappingDictionary);
    }

    private static void checkExecutePermission(final URL wasmUrl) {
        ShiroUtils.require(ActionType.EXECUTE, WebFunctionResourceType.INSTANCE, wasmUrl.toString());
    }

    public StardogWasmInstance(final URL wasmURL, final MappingDictionary mappingDictionary) throws ExecutionException {
        this(wasmURL);
        this.setMappingDictionary(mappingDictionary);
    }

    public StardogWasmInstance(final URL wasmUrl) throws ExecutionException {
        final ai.tegmentum.webassembly4j.api.WebAssemblyBuilder engineBuilder =
                WebAssembly.builder()
                        .provider(WebFunctionConfig.engineProvider())
                        .config(WebFunctionConfig.fromSystemProperties());
        WebFunctionConfig.engineId().ifPresent(engineBuilder::engine);
        this.engine = engineBuilder.build();

        final byte[] bytes = loadingCache.get(wasmUrl);

        switch (WebFunctionConfig.engineMode()) {
            case COMPONENT:
                if (!engine.capabilities().supportsComponents()) {
                    engine.close();
                    throw new IllegalStateException(
                            WebFunctionConfig.PROP_ENGINE_MODE + "=component but engine '"
                                    + engine.info().engineId() + "' does not support components");
                }
                // Component-mode linking context declares no core host functions.
                // The wasmtime provider rejects any core-shaped host imports when
                // instantiating a component; WIT-level host imports must be defined
                // via native ComponentLinker.defineFunction (out of scope here — the
                // current component ABI declares no imports beyond WASI).
                this.component = engine.loadComponent(bytes);
                this.instance = component.instantiate(DefaultLinkingContext.builder().build());
                break;
            case MODULE:
            default:
                final DefaultLinkingContext linkingContext = DefaultLinkingContext.builder()
                        .addHostFunction("env", WASM_FUNCTION_MAPPING_DICTIONARY_ADD,
                                new ValueType[]{ValueType.I32}, new ValueType[]{ValueType.I64},
                                mappingDictionaryAddHostFunction())
                        .addHostFunction("env", WASM_FUNCTION_MAPPING_DICTIONARY_GET,
                                new ValueType[]{ValueType.I64}, new ValueType[]{ValueType.I32},
                                mappingDictionaryGetHostFunction())
                        .build();
                this.module = engine.loadModule(bytes);
                this.instance = module.instantiate(linkingContext);
                break;
        }
    }

    private Value[] readFromWasmMemory(final String name, final int output_pointer) {
        final Memory memory = instance.memory(name).get();
        try (final SelectQueryResult selectQueryResult = QueryResultParsers.readSelect(new ByteArrayInputStream(readResult(memory, output_pointer).toByteArray()), QueryResultFormats.JSON)) {
            final Optional<BindingSet> bs = selectQueryResult.stream().findFirst();

            if (bs.isPresent()) {
                return bs.get().stream().map(Binding::value).toArray(Value[]::new);
            } else {
                return new Value[0];
            }
        } catch (IOException e) {
            return new Value[0];
        }
    }

    private WasmMemoryRef writeToWasmMemory(final String name, final Value[] values) throws IOException {

        final List<String> vars = Lists.newArrayListWithCapacity(values.length);
        final BindingSets.Builder bindingSetsBuilder = BindingSets.builder();
        IntStream.range(0, values.length).forEach(i -> {
            vars.add(String.format("value_%d", i));
            bindingSetsBuilder.add(String.format("value_%d", i), values[i]);
        });
        final List<BindingSet> bindings = Collections.singletonList(bindingSetsBuilder.build());

        final SelectQueryResult queryResult = new SelectQueryResultImpl(vars, bindings);
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        QueryResultWriters.write(queryResult, byteArrayOutputStream, QueryResultFormats.JSON);

        final Function mallocFunction = instance.function(WASM_FUNCTION_MALLOC).get();
        byteArrayOutputStream.write('\0');
        final int inputLength = byteArrayOutputStream.toByteArray().length;
        mallocFunction.invoke(inputLength);
        final Integer input_pointer = ((Number) mallocFunction.invoke(inputLength)).intValue();

        final Memory memory = instance.memory(name).get();
        final byte[] input = byteArrayOutputStream.toByteArray();
        memory.write(input_pointer, input);
        return WasmMemoryRef.from(input_pointer, byteArrayOutputStream.toByteArray().length);
    }

    public void setMappingDictionary(final MappingDictionary mappingDictionary) {
        this.mappingDictionaryIsSet = true;
        this.mappingDictionaryRef.set(mappingDictionary);
    }

    private WasmMemoryRef writeToWasmMemoryWithCardinality(final String name, final Cardinality cardinality, final Value[] values) throws IOException {

        final List<String> vars = Lists.newArrayListWithCapacity(values.length);
        final BindingSets.Builder bindingSetsBuilder = BindingSets.builder();
        IntStream.range(0, values.length).forEach(i -> {
            vars.add(String.format("value_%d", i));
            bindingSetsBuilder.add(String.format("value_%d", i), values[i]);
        });
        vars.add("cardinality");
        bindingSetsBuilder.add("cardinality", Values.literal(cardinality.value()));
        final List<BindingSet> bindings = Collections.singletonList(bindingSetsBuilder.build());

        final SelectQueryResult queryResult = new SelectQueryResultImpl(vars, bindings);
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        QueryResultWriters.write(queryResult, byteArrayOutputStream, QueryResultFormats.JSON);

        final Function mallocFunction = instance.function(WASM_FUNCTION_MALLOC).get();
        byteArrayOutputStream.write('\0');
        final int inputLength = byteArrayOutputStream.toByteArray().length;
        mallocFunction.invoke(inputLength);
        final Integer input_pointer = ((Number) mallocFunction.invoke(inputLength)).intValue();

        final Memory memory = instance.memory(name).get();
        final byte[] input = byteArrayOutputStream.toByteArray();
        memory.write(input_pointer, input);
        return WasmMemoryRef.from(input_pointer, byteArrayOutputStream.toByteArray().length);
    }


    private WasmMemoryRef writeToWasmMemoryWithMultiplicity(final String name, final Value[] values, final long multiplicity) throws IOException {

        final List<String> vars = Lists.newArrayListWithCapacity(values.length);
        final BindingSets.Builder bindingSetsBuilder = BindingSets.builder();
        IntStream.range(0, values.length).forEach(i -> {
            vars.add(String.format("value_%d", i));
            bindingSetsBuilder.add(String.format("value_%d", i), values[i]);
        });
        vars.add("multiplicity");
        bindingSetsBuilder.add("multiplicity", Values.literal(multiplicity));
        final List<BindingSet> bindings = Collections.singletonList(bindingSetsBuilder.build());

        final SelectQueryResult queryResult = new SelectQueryResultImpl(vars, bindings);
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        QueryResultWriters.write(queryResult, byteArrayOutputStream, QueryResultFormats.JSON);

        final Function mallocFunction = instance.function(WASM_FUNCTION_MALLOC).get();
        byteArrayOutputStream.write('\0');
        final int inputLength = byteArrayOutputStream.toByteArray().length;
        mallocFunction.invoke(inputLength);
        final Integer input_pointer = ((Number) mallocFunction.invoke(inputLength)).intValue();

        final Memory memory = instance.memory(name).get();
        final byte[] input = byteArrayOutputStream.toByteArray();
        memory.write(input_pointer, input);
        return WasmMemoryRef.from(input_pointer, byteArrayOutputStream.toByteArray().length);
    }

    private SelectQueryResult readFromWasmMemorySelectQueryResult(final String name, final int output_pointer) {
        final Memory memory = instance.memory(name).get();
        try {
            return QueryResultParsers.readSelect(new ByteArrayInputStream(readResult(memory, output_pointer).toByteArray()), QueryResultFormats.JSON);
        } catch (IOException e) {
            throw new StardogException(e);
        }
    }

    public void free(final WasmMemoryRef wasmMemoryRef) {
        final Function freeFunction = instance.function(WASM_FUNCTION_FREE).get();
        freeFunction.invoke(wasmMemoryRef.addr, wasmMemoryRef.len);
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        if (module != null) {
            module.close();
        }
        if (component != null) {
            component.close();
        }
        if (engine != null) {
            engine.close();
        }
        instance = null;
        module = null;
        component = null;
        engine = null;
        closed = true;
    }

    public Cardinality getCardinality(final Cardinality inputCardinality, List<Value> args) {
        if (isComponentMode()) {
            return componentCardinalityEstimate(inputCardinality, args);
        }
        try {
            final WasmMemoryRef input = this.writeToWasmMemoryWithCardinality("memory", inputCardinality, args.toArray(new Value[0]));

            final Function evaluateFunction = instance.function(WASM_FUNCTION_CARDINALITY_ESTIMATE).get();
            final Integer output_pointer = ((Number) evaluateFunction.invoke(input.addr)).intValue();
            free(input);
            try (final SelectQueryResult selectQueryResult = readFromWasmMemorySelectQueryResult("memory", output_pointer)) {
                if (selectQueryResult.hasNext()) {
                    final BindingSet bs = selectQueryResult.next();
                    return Cardinality.of(Literal.longValue((Literal) bs.get("cardinality")), Accuracy.valueOf(((Literal) bs.get("accuracy")).label()));
                } else {
                    throw new PlanException("Unable to retrieve cardinality estimate");
                }
            }
        } catch (IOException e1) {
            throw new PlanException(e1);
        }
    }

    public SelectQueryResult evaluate(final Value... values) throws IOException {
        if (isComponentMode()) {
            return componentInvokeBindingSets("evaluate", (Object) marshaller().toWitArgs(values));
        }
        final WasmMemoryRef input = writeToWasmMemory("memory", values);

        final Function evaluateFunction = instance.function(WASM_FUNCTION_EVALUATE).get();
        final Integer output_pointer = ((Number) evaluateFunction.invoke(input.addr)).intValue();
        free(input);
        return readFromWasmMemorySelectQueryResult("memory", output_pointer);
    }

    public SelectQueryResult doc() throws IOException {
        if (isComponentMode()) {
            final WitValue result = (WitValue) ((ComponentInstance) instance).invokeWit("doc");
            return marshaller().bindingSetsFromWit(result);
        }
        final Function evaluateFunction = instance.function(WASM_FUNCTION_DOC).get();
        final Integer output_pointer = ((Number) evaluateFunction.invoke()).intValue();
        return readFromWasmMemorySelectQueryResult("memory", output_pointer);
    }

    public SelectQueryResult compute(final Value[] values, long multiplicity) throws IOException {
        if (isComponentMode()) {
            final WitValueMarshaller m = marshaller();
            final WitValue stepResult = (WitValue) ((ComponentInstance) instance).invokeWit(
                    "aggregate-step", m.toWitArgs(values), multiplicity);
            unwrapVoidResult(stepResult);
            // Aggregate-step returns result<_, string>; the final materialized value
            // is fetched separately via aggregateGetValue().
            return new SelectQueryResultImpl(java.util.Collections.emptyList(), java.util.Collections.emptyList());
        }
        final WasmMemoryRef input = writeToWasmMemoryWithMultiplicity("memory", Arrays.stream(values).toArray(Value[]::new), multiplicity);

        final Function evaluateFunction = instance.function(WASM_FUNCTION_AGGREGATE).get();
        final Integer output_pointer = ((Number) evaluateFunction.invoke(input.addr)).intValue();
        free(input);
        return readFromWasmMemorySelectQueryResult("memory", output_pointer);
    }

    public SelectQueryResult aggregateGetValue() {
        if (isComponentMode()) {
            try {
                return componentInvokeBindingSets("aggregate-finish");
            } catch (IOException e) {
                throw new StardogException(e);
            }
        }
        final Function evaluateFunction = instance.function(WASM_FUNCTION_GET_VALUE).get();
        final Integer output_pointer = ((Number) evaluateFunction.invoke()).intValue();
        return readFromWasmMemorySelectQueryResult("memory", output_pointer);
    }

    private boolean isComponentMode() {
        return component != null;
    }

    private WitValueMarshaller marshaller() {
        // A null MappingDictionary is tolerated; it is only consulted by paths that
        // still require dictionary translation (currently none in the simplified WIT).
        return new WitValueMarshaller(mappingDictionaryRef.get());
    }

    private Cardinality componentCardinalityEstimate(final Cardinality in, final List<Value> args) {
        final WitValueMarshaller m = marshaller();
        final WitValue result = (WitValue) ((ComponentInstance) instance).invokeWit(
                "cardinality-estimate",
                m.toWitCardinality(in),
                m.toWitArgs(args.toArray(new Value[0])));
        final WitValue ok = unwrapOkWit(result, PlanException::new);
        return m.cardinalityFromWit(ok);
    }

    private SelectQueryResult componentInvokeBindingSets(final String funcName, final Object... args) throws IOException {
        final WitValue result = (WitValue) ((ComponentInstance) instance).invokeWit(funcName, args);
        final WitValue ok;
        try {
            ok = unwrapOkWit(result, IOException::new);
        } catch (RuntimeException re) {
            if (re.getCause() instanceof IOException) throw (IOException) re.getCause();
            throw re;
        }
        return marshaller().bindingSetsFromWit(ok);
    }

    private void unwrapVoidResult(final WitValue result) {
        unwrapOkWit(result, StardogException::new);
    }

    /**
     * WitResult tree returned by {@link ComponentInstance#invokeWit}: either
     * {@code ok(payload)} or {@code err(string)}. Returns the ok payload; on err,
     * throws a runtime exception built by {@code errFactory} wrapping the err
     * string. Wrapper indirection lets callers surface checked exceptions.
     */
    private static WitValue unwrapOkWit(final WitValue result,
                                        final java.util.function.Function<String, ? extends Throwable> errFactory) {
        if (!(result instanceof WitResult)) {
            throw new StardogException("Unexpected component invocation return type: "
                    + (result == null ? "null" : result.getClass().getName()));
        }
        final WitResult wr = (WitResult) result;
        if (wr.isErr()) {
            final String msg = wr.getErr()
                    .map(v -> ((WitString) v).getValue())
                    .orElse("component returned err with no payload");
            final Throwable t = errFactory.apply(msg);
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new RuntimeException(t);
        }
        return wr.getOk().orElse(null);
    }

    private ByteArrayOutputStream readResult(final Memory memory, final Integer output_pointer) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final ByteBuffer memoryBuffer = memory.asByteBuffer();

        for (Integer i = output_pointer, max = memoryBuffer.limit(); i < max; ++i) {
            final byte[] b = new byte[1];
            memoryBuffer.position(i);
            memoryBuffer.get(b);

            if (b[0] == 0) {
                break;
            }
            baos.write(b[0]);
        }
        return baos;
    }

    public ValueOrError selectQueryResultToValueOrError(final SelectQueryResult selectQueryResult) {
        final List<BindingSet> bindingSets = Streams.stream(selectQueryResult).collect(toList());

        if(bindingSets.size() == 0 || bindingSets.size() == 1 && bindingSets.get(0).size() == 0) {
            return ValueOrError.Error;
        }

        if(bindingSets.size() == 1 && bindingSets.get(0).size() == 1) {
            if (bindingSets.get(0).get("value_0") instanceof Literal && ((Literal) bindingSets.get(0).get("value_0")).datatypeIRI().equals(iri("tag:stardog:api:array"))) {
                return ValueOrError.General.of(ArrayLiteral.coerce((Literal) bindingSets.get(0).get("value_0")));
            } else {
                return ValueOrError.General.of(bindingSets.get(0).get("value_0"));
            }
        } else {
            return ValueOrError.General.of(bindingSetsToArrayLiteral(bindingSets));
        }
    }

    private ArrayLiteral bindingSetsToArrayLiteral(final List<BindingSet> bindingSets) {
        return new ArrayLiteral(
                bindingSets.stream().map(bs -> new ArrayLiteral(bs.stream().map(b -> {
                    if (b.get() instanceof Literal && ((Literal) b.get()).datatypeIRI().equals(iri("tag:stardog:api:array"))) {
                        return ArrayLiteral.coerce((Literal) b.get());
                    } else {
                        return b.get();
                    }
                }).mapToLong(mappingDictionaryRef.get()::add).toArray())).mapToLong(mappingDictionaryRef.get()::add).toArray());
    }
}
