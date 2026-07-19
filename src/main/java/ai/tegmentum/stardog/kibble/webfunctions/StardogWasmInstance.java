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
import ai.tegmentum.webassembly4j.api.WitCallableResource;
import ai.tegmentum.wasmtime4j.wit.WitList;
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

    // Component-mode export paths for the base tegmentum:webfunction@0.1.0
    // world's sparql-extension surface. wasmtime4j surfaces interface exports
    // as `<interface-name>#<function-name>` (mirroring HostCallbacks' import
    // registration pattern in this same class).
    static final String EXT_REGISTER              = "tegmentum:webfunction/extension@0.1.0#register";
    static final String EXT_CALL                  = "tegmentum:webfunction/extension@0.1.0#call";
    static final String AGG_REGISTER_AGGREGATES   = "tegmentum:webfunction/aggregate@0.1.0#register-aggregates";
    static final String AGG_NEW_AGGREGATE         = "tegmentum:webfunction/aggregate@0.1.0#new-aggregate";
    // Resource-method paths: `<interface>#[method]<type>.<name>` per
    // webassembly4j WitCallableResource javadoc.
    static final String AGG_STATE_STEP            = "tegmentum:webfunction/aggregate@0.1.0#[method]aggregate-state.step";
    static final String AGG_STATE_FINISH          = "tegmentum:webfunction/aggregate@0.1.0#[method]aggregate-state.finish";

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

    // Component-mode caches: one shared Engine built lazily on first component
    // call (WebFunctionConfig properties are frozen at that point); one
    // Component per URL, so repeat wf:call invocations skip download + compile
    // and only pay instantiation cost. Module-mode still uses per-call Engine
    // + Module because its linking context binds a per-instance MappingDictionary
    // reference and the current wiring is easier to reason about that way.
    private static volatile Engine COMPONENT_MODE_SHARED_ENGINE;
    private static final Object COMPONENT_MODE_ENGINE_LOCK = new Object();
    private static final java.util.concurrent.ConcurrentHashMap<URL, Component> COMPONENT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

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

    // Component-mode dispatch state. The base sparql-extension world routes
    // every filter call through `extension.call(name, args)` — the name is
    // discovered once via `extension.register()` and cached for this
    // instance's lifetime. Aggregate lifecycle is: `new-aggregate(name)`
    // returns a per-group resource handle, `step` accumulates, `finish`
    // returns the value; the resource is dropped when this instance closes.
    private volatile String cachedFilterFunctionName;
    private volatile String cachedAggregateName;
    private WitCallableResource aggregateResource;

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

    /**
     * Capability-policy Phase 1 — load the sidecar manifest for
     * {@code wasmUrl} through {@link ExtensionManifestLoader}. Returns
     * {@link ExtensionManifest#ABSENT} when the sidecar is missing and
     * {@code webfunctions.capability.require-manifest} is false; propagates
     * {@link WfCapabilityError.ManifestMalformed} otherwise so admins see the
     * parse failure surface. Callers only invoke this when the capability
     * master gate is on, so an ABSENT return still flows to the resolver.
     */
    private static ExtensionManifest loadManifestForCapability(final URL wasmUrl) {
        try {
            return ExtensionManifestLoader.load(wasmUrl);
        } catch (WfCapabilityError.ManifestMalformed malformed) {
            if (WebFunctionConfig.isRequireManifest()) throw malformed;
            return ExtensionManifest.ABSENT;
        }
    }

    public StardogWasmInstance(final URL wasmURL, final MappingDictionary mappingDictionary) throws ExecutionException {
        this(wasmURL);
        this.setMappingDictionary(mappingDictionary);
    }

    public StardogWasmInstance(final URL wasmUrl) throws ExecutionException {
        switch (WebFunctionConfig.engineMode()) {
            case COMPONENT:
                // Reuse the shared engine + cached component; do NOT hold the engine
                // in `this.engine` since we don't want close() to release the shared
                // resources. The instance is per-call.
                this.component = null;
                this.engine = null;
                final Component cached;
                try {
                    cached = componentModeComponentFor(wasmUrl);
                } catch (IOException ioe) {
                    throw new RuntimeException(ioe);
                }
                // Capability-policy Phase 1 — resolve the effective grant
                // before wiring the linker so we only wire interfaces the
                // grant permits. When the master gate is off (default),
                // {@link CapabilityEnforcer#activePolicy} returns empty
                // and we fall through to the pre-capability code path
                // that wires every interface unconditionally.
                //
                // On LoadTimeDenied, the enforcer's throw unwinds this
                // constructor; Call.evaluate / WebFunctionServiceOperator
                // surface it as-is (WfCapabilityError is a StardogException
                // subtype, mirroring WfBudgetError).
                final Optional<CapabilityEnforcer> enforcer = CapabilityEnforcer.activePolicy();
                final CapabilityGrant grant;
                if (enforcer.isPresent()) {
                    final ExtensionManifest manifest = loadManifestForCapability(wasmUrl);
                    final CallbackContext preCtx = CallbackContext.current();
                    final FuelContext fuelCtx = preCtx == null
                            ? FuelContext.extract(wasmUrl.toString())
                            : new FuelContext(FuelContext.extract(wasmUrl.toString()).userId(),
                                              "",
                                              wasmUrl.toString());
                    grant = enforcer.get().preInvocation(fuelCtx, cached, manifest);
                    if (preCtx != null) preCtx.setCapabilityGrant(grant);
                } else {
                    grant = null;
                }
                final DefaultLinkingContext.Builder componentLinker = DefaultLinkingContext.builder();
                if (WebFunctionConfig.callbackEnabled()) {
                    // tegmentum:webfunction/graph-callbacks@0.1.0 —
                    // base-substrate graph callback surface for guests
                    // targeting `world extension-with-host-callbacks`.
                    // Signatures differ from the legacy shape:
                    // execute-query takes one string (no bindings, no
                    // max-rows) and returns `result<query-result,
                    // graph-call-error>`; execute-update takes one string
                    // and returns `result<_, graph-call-error>`. Error
                    // discrimination lifts MalformedQuery onto syntax-error
                    // and Shiro auth exceptions onto not-permitted, with
                    // backend-error preserved as the default. Gated behind
                    // the webfunctions.callback.enabled knob.
                    //
                    // The legacy stardog:webfunction/host@0.3.x-0.4.0
                    // registrations that used to sit here were dead code
                    // (zero consumers on the shape) and were retired; see
                    // webfunction-wit/hostcallbacks-legacy-retirement.md.
                    if (grant == null || grant.allowsInterface("graph-callbacks")) {
                        componentLinker.addWitHostFunction(
                            "tegmentum:webfunction/graph-callbacks@0.1.0#execute-query",
                            HostCallbacks.graphExecuteQuery());
                        componentLinker.addWitHostFunction(
                            "tegmentum:webfunction/graph-callbacks@0.1.0#execute-update",
                            HostCallbacks.graphExecuteUpdate());
                    }
                }
                // tegmentum:webfunction/http-callbacks@0.1.0 —
                // outbound HTTP for guests reaching services outside SPARQL
                // (vector-index endpoints, LLM inference, blob stores).
                // Impl uses JDK-native java.net.http.HttpClient — no
                // external HTTP dep needed. Registered outside the
                // callback-enabled gate because the flag controls
                // re-entering the graph; HTTP reaches external services.
                if (grant == null || grant.allowsInterface("http-callbacks")) {
                    componentLinker.addWitHostFunction(
                        "tegmentum:webfunction/http-callbacks@0.1.0#http-get",
                        HostCallbacks.httpGet());
                    componentLinker.addWitHostFunction(
                        "tegmentum:webfunction/http-callbacks@0.1.0#http-post-json",
                        HostCallbacks.httpPostJsonV1());
                }
                // tegmentum:webfunction/wasm-callbacks@0.1.0 —
                // sub-component dispatch. MVP registers both invoke-wasm
                // (scalar-return) and invoke-wasm-service (list<binding>-
                // return) as not-permitted stubs so guests importing the
                // interface can link. Full JVM-host component composition
                // is separate future work; a guest that reaches these
                // callbacks receives a typed policy denial with a
                // descriptive message instead of a link-time trap.
                if (grant == null || grant.allowsInterface("wasm-callbacks")) {
                    componentLinker.addWitHostFunction(
                        "tegmentum:webfunction/wasm-callbacks@0.1.0#invoke-wasm",
                        HostCallbacks.invokeWasmV1());
                    componentLinker.addWitHostFunction(
                        "tegmentum:webfunction/wasm-callbacks@0.1.0#invoke-wasm-service",
                        HostCallbacks.invokeWasmService());
                }
                this.instance = cached.instantiate(
                        componentLinker.build(),
                        WebFunctionConfig.componentConfigFromSystemProperties());
                break;
            case MODULE:
            default:
                final ai.tegmentum.webassembly4j.api.WebAssemblyBuilder engineBuilder =
                        WebAssembly.builder()
                                .provider(WebFunctionConfig.engineProvider())
                                .config(WebFunctionConfig.fromSystemProperties());
                WebFunctionConfig.engineId().ifPresent(engineBuilder::engine);
                this.engine = engineBuilder.build();

                final byte[] bytes = loadingCache.get(wasmUrl);

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

    private static Engine componentModeSharedEngine() {
        Engine e = COMPONENT_MODE_SHARED_ENGINE;
        if (e != null) return e;
        synchronized (COMPONENT_MODE_ENGINE_LOCK) {
            if (COMPONENT_MODE_SHARED_ENGINE == null) {
                final ai.tegmentum.webassembly4j.api.WebAssemblyBuilder engineBuilder =
                        WebAssembly.builder()
                                .provider(WebFunctionConfig.engineProvider())
                                .config(WebFunctionConfig.fromSystemProperties());
                WebFunctionConfig.engineId().ifPresent(engineBuilder::engine);
                final Engine built = engineBuilder.build();
                if (!built.capabilities().supportsComponents()) {
                    built.close();
                    throw new IllegalStateException(
                            WebFunctionConfig.PROP_ENGINE_MODE + "=component but engine '"
                                    + built.info().engineId() + "' does not support components");
                }
                COMPONENT_MODE_SHARED_ENGINE = built;
            }
            return COMPONENT_MODE_SHARED_ENGINE;
        }
    }

    private static Component componentModeComponentFor(final URL wasmUrl) throws IOException, ExecutionException {
        Component cached = COMPONENT_CACHE.get(wasmUrl);
        if (cached != null) return cached;
        final Engine engine = componentModeSharedEngine();
        final byte[] bytes = loadingCache.get(wasmUrl);
        // computeIfAbsent — engine.loadComponent doesn't throw checked IOException
        // so the lambda stays clean.
        return COMPONENT_CACHE.computeIfAbsent(wasmUrl, u -> engine.loadComponent(bytes));
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
        // In component mode the shared engine + cached component are not held
        // on this instance (they live in the static cache), so nothing to
        // release beyond the per-call ComponentInstance. Module mode still
        // owns its engine + module.
        //
        // Drop any live aggregate resource FIRST — the underlying store-side
        // handle it owns lives inside `instance`, so releasing after we null
        // `instance` would leak the handle.
        if (aggregateResource != null) {
            try {
                aggregateResource.close();
            } catch (RuntimeException ignore) {
                // Best-effort — a failing close here shouldn't mask the real
                // close reason (this instance is going away anyway).
            }
            aggregateResource = null;
        }
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
            // `cardinality-estimate` lives on the stardog-extension overlay
            // world (planner interface), not the base sparql-extension world.
            // Test components targeting the base world only cannot answer;
            // return the input cardinality (pessimistic identity estimate)
            // so the planner sees a valid Cardinality object rather than a
            // trap. Module mode still calls the real export below.
            return inputCardinality;
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
            stampComponentInstanceOnCurrentContext();
            return componentExtensionCall(values);
        }
        final WasmMemoryRef input = writeToWasmMemory("memory", values);

        final Function evaluateFunction = instance.function(WASM_FUNCTION_EVALUATE).get();
        final Integer output_pointer = ((Number) evaluateFunction.invoke(input.addr)).intValue();
        free(input);
        return readFromWasmMemorySelectQueryResult("memory", output_pointer);
    }

    public SelectQueryResult doc() throws IOException {
        if (isComponentMode()) {
            // `doc` lives on the stardog-extension overlay world, not the base
            // sparql-extension world. Test components target the base world only,
            // so component-mode `doc()` has no export to dispatch into. Return
            // an empty binding-sets rather than trap — module-mode still serves
            // the real doc export for callers that need it.
            return new SelectQueryResultImpl(java.util.Collections.emptyList(), java.util.Collections.emptyList());
        }
        final Function evaluateFunction = instance.function(WASM_FUNCTION_DOC).get();
        final Integer output_pointer = ((Number) evaluateFunction.invoke()).intValue();
        return readFromWasmMemorySelectQueryResult("memory", output_pointer);
    }

    public SelectQueryResult compute(final Value[] values, long multiplicity) throws IOException {
        if (isComponentMode()) {
            stampComponentInstanceOnCurrentContext();
            componentAggregateStep(values, multiplicity);
            // aggregate.step returns void; the materialized value is fetched
            // separately via aggregateGetValue() through resource.finish.
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
            stampComponentInstanceOnCurrentContext();
            return componentAggregateFinish();
        }
        final Function evaluateFunction = instance.function(WASM_FUNCTION_GET_VALUE).get();
        final Integer output_pointer = ((Number) evaluateFunction.invoke()).intValue();
        return readFromWasmMemorySelectQueryResult("memory", output_pointer);
    }

    private boolean isComponentMode() {
        // Component mode uses a shared cached component (not held on `this`),
        // so distinguish by mode config rather than by nullness of `component`.
        return WebFunctionConfig.engineMode() == WebFunctionConfig.EngineMode.COMPONENT;
    }

    /**
     * Plumb the underlying {@link ComponentInstance} onto the current
     * {@link CallbackContext} so {@link CallbackContext#chargeToll(String)}
     * (and {@link CallbackContext#fuelConsumed()}) can debit / read the
     * real store fuel through the wasmtime4j 1.4.7 / webassembly4j 2.4.3
     * API. No-op when there is no bound CallbackContext (embedded/test
     * callers that dispatch without binding a callback context) or when
     * {@code instance} is not a {@link ComponentInstance} (module-mode
     * dispatch reaches this only via {@link #stampComponentInstanceOnCurrentContext()}
     * called from the component-mode branch, so the cast is safe there).
     */
    private void stampComponentInstanceOnCurrentContext() {
        final CallbackContext ctx = CallbackContext.current();
        if (ctx == null) return;
        if (instance instanceof ComponentInstance) {
            ctx.setComponentInstance((ComponentInstance) instance);
        }
    }

    private WitValueMarshaller marshaller() {
        // A null MappingDictionary is tolerated; it is only consulted by paths that
        // still require dictionary translation (currently none in the simplified WIT).
        return new WitValueMarshaller(mappingDictionaryRef.get());
    }

    /**
     * Dispatch to the base extension's {@code call(name, args)} export.
     * Discovers the extension's function name lazily on first use via
     * {@code register()} — the pre-migration ABI had one flat {@code
     * evaluate} export per component, so caching the first registered
     * descriptor's name here preserves the invariant that a Stardog
     * webfunction wasm URL binds to exactly one filter.
     */
    private SelectQueryResult componentExtensionCall(final Value[] values) throws IOException {
        final WitValueMarshaller m = marshaller();
        final String fnName = ensureFilterFunctionName();
        final WitValue result = (WitValue) ((ComponentInstance) instance).invokeWit(
                EXT_CALL,
                WitValueMarshaller.witString(fnName),
                m.toWitArgs(values));
        final WitValue ok;
        try {
            ok = unwrapOkWit(result, IOException::new);
        } catch (RuntimeException re) {
            if (re.getCause() instanceof IOException) throw (IOException) re.getCause();
            throw re;
        }
        // extension.call returns a single term; wrap it as a 1x1 SelectQueryResult
        // under the well-known `value_0` binding name for backward compatibility
        // with the flat-world SelectQueryResult shape callers still expect.
        return marshaller().singleTermToSelectQueryResult(ok);
    }

    /**
     * Discover and cache the filter function name to pass to {@code
     * extension.call}. Called on first {@code evaluate()} — thereafter
     * reused for every subsequent call on this instance.
     */
    private String ensureFilterFunctionName() throws IOException {
        String name = cachedFilterFunctionName;
        if (name != null) return name;
        synchronized (this) {
            if (cachedFilterFunctionName == null) {
                final WitValue descriptors = (WitValue) ((ComponentInstance) instance).invokeWit(EXT_REGISTER);
                cachedFilterFunctionName = firstDescriptorName(descriptors, "register",
                        "component exports no filter functions; extension.register returned []");
            }
            return cachedFilterFunctionName;
        }
    }

    /**
     * Aggregate step lifecycle: on first call for this instance, dispatch
     * {@code new-aggregate(name)} to allocate the guest-side accumulator
     * resource; wrap the returned WitResource as a {@link
     * WitCallableResource} so method invocations avoid the manual
     * receiver-threading dance. Then call {@code step(args)} once per
     * multiplicity unit — the base aggregate world has no multiplicity
     * parameter, so N copies of the same row are stepped N times.
     */
    private void componentAggregateStep(final Value[] values, final long multiplicity) throws IOException {
        if (aggregateResource == null) {
            openAggregateResource();
        }
        final WitList argsList = marshaller().toWitArgs(values);
        for (long i = 0; i < multiplicity; i++) {
            final Object stepReturn = aggregateResource.invokeMethodWit(AGG_STATE_STEP, argsList);
            unwrapVoidResult((WitValue) stepReturn);
        }
    }

    private void openAggregateResource() throws IOException {
        final ComponentInstance ci = (ComponentInstance) instance;
        if (cachedAggregateName == null) {
            final WitValue descriptors = (WitValue) ci.invokeWit(AGG_REGISTER_AGGREGATES);
            cachedAggregateName = firstDescriptorName(descriptors, "register-aggregates",
                    "component exports no aggregates; aggregate.register-aggregates returned []");
        }
        final WitValue newAggReturn = (WitValue) ci.invokeWit(
                AGG_NEW_AGGREGATE, WitValueMarshaller.witString(cachedAggregateName));
        final WitValue okResource;
        try {
            okResource = unwrapOkWit(newAggReturn, IOException::new);
        } catch (RuntimeException re) {
            if (re.getCause() instanceof IOException) throw (IOException) re.getCause();
            throw re;
        }
        // asCallableResource binds the WitResource returned by new-aggregate to
        // this ComponentInstance, so downstream `step`/`finish` calls dispatch
        // through the receiver without manual argument prepending.
        aggregateResource = ci.asCallableResource(okResource);
    }

    /**
     * Aggregate finish: call {@code finish()} on the cached resource, drop
     * it, and wrap the returned term as a 1x1 SelectQueryResult with the
     * conventional {@code value_0} binding name.
     */
    private SelectQueryResult componentAggregateFinish() {
        if (aggregateResource == null) {
            // No step ever ran — return empty, matching the module-mode contract
            // for aggregate-with-no-inputs.
            return new SelectQueryResultImpl(java.util.Collections.emptyList(), java.util.Collections.emptyList());
        }
        try {
            final WitValue finishReturn = (WitValue) aggregateResource.invokeMethodWit(AGG_STATE_FINISH);
            final WitValue ok = unwrapOkWit(finishReturn, StardogException::new);
            return marshaller().singleTermToSelectQueryResult(ok);
        } finally {
            aggregateResource.close();
            aggregateResource = null;
        }
    }

    /**
     * Extract the first descriptor's name from a {@code list<function-descriptor>}
     * or {@code list<aggregate-descriptor>} return. Both descriptor records
     * carry a {@code name: string} field; the caller has already declared the
     * ABI it expects.
     */
    private static String firstDescriptorName(final WitValue witValue,
                                              final String registerFnName,
                                              final String emptyMessage) throws IOException {
        if (!(witValue instanceof WitList)) {
            throw new IOException("extension." + registerFnName + " return is not a list: "
                    + (witValue == null ? "null" : witValue.getClass().getName()));
        }
        final java.util.List<WitValue> elems = ((WitList) witValue).getElements();
        if (elems.isEmpty()) {
            throw new IOException(emptyMessage);
        }
        final WitRecord first = (WitRecord) elems.get(0);
        return ((WitString) first.getField("name")).getValue();
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
