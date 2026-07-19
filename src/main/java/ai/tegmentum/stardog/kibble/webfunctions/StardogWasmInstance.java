package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.common.rdf.model.ArrayLiteral;
import com.complexible.stardog.StardogException;
import com.complexible.stardog.index.dictionary.MappingDictionary;
import com.complexible.stardog.index.statistics.Cardinality;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Streams;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.SelectQueryResult;
import com.stardog.stark.query.impl.SelectQueryResultImpl;
import ai.tegmentum.webassembly4j.api.Component;
import ai.tegmentum.webassembly4j.api.ComponentInstance;
import ai.tegmentum.webassembly4j.api.DefaultLinkingContext;
import ai.tegmentum.webassembly4j.api.Engine;
import ai.tegmentum.webassembly4j.api.WebAssembly;
import ai.tegmentum.webassembly4j.api.WitCallableResource;
import ai.tegmentum.wasmtime4j.wit.WitList;
import ai.tegmentum.wasmtime4j.wit.WitRecord;
import ai.tegmentum.wasmtime4j.wit.WitResult;
import ai.tegmentum.wasmtime4j.wit.WitString;
import ai.tegmentum.wasmtime4j.wit.WitValue;
import com.complexible.stardog.security.ActionType;
import com.complexible.stardog.security.ShiroUtils;
import com.complexible.stardog.security.StardogAuthorizationException;
import org.apache.commons.io.IOUtils;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static com.stardog.stark.Values.iri;
import static java.util.stream.Collectors.toList;

public class StardogWasmInstance implements Closeable {

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

    static LoadingCache<URL, byte[]> loadingCache = CacheBuilder
            .newBuilder()
            .softValues()
            .build(new CacheLoader<URL, byte[]>() {
                @Override
                public byte[] load(URL url) throws IOException {
                    return getWasm(url);
                }
            });

    // Shared cache: one Engine built lazily on first call (WebFunctionConfig
    // properties are frozen at that point); one Component per URL, so repeat
    // wf:call invocations skip download + compile and only pay instantiation
    // cost.
    private static volatile Engine SHARED_ENGINE;
    private static final Object SHARED_ENGINE_LOCK = new Object();
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

    private final AtomicReference<MappingDictionary> mappingDictionaryRef = new AtomicReference<>();
    private ComponentInstance instance;
    private boolean closed = false;
    private boolean mappingDictionaryIsSet;

    // Dispatch state. The base sparql-extension world routes every filter
    // call through `extension.call(name, args)` — the name is discovered
    // once via `extension.register()` and cached for this instance's
    // lifetime. Aggregate lifecycle is: `new-aggregate(name)` returns a
    // per-group resource handle, `step` accumulates, `finish` returns the
    // value; the resource is dropped when this instance closes.
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
     * Capture the invoker's Shiro subject at instantiation for Phase 4
     * {@code ShiroUtils.executeAs} wrapping in {@link HostCallbacks}.
     * Returns null when no authenticated subject is bound (unit tests,
     * embedded direct-instantiation, misconfigured server), mirroring
     * the defensive shape of {@link FuelContext#extract} and
     * {@link CapabilityEnforcer#currentSubjectOrNull()} — a broken auth
     * path must not take down the invocation. The per-callback wrap
     * then consults {@link WebFunctionConfig#getAnonymousPolicy()} to
     * decide whether to deny the anonymous invocation, permit the
     * ambient-credential fall-through, or inherit.
     */
    private static Subject currentInvokerSubjectOrNull() {
        try {
            final Subject s = SecurityUtils.getSubject();
            if (s == null || s.getPrincipal() == null) return null;
            return s;
        } catch (StardogAuthorizationException noSubject) {
            return null;
        } catch (RuntimeException ignore) {
            // Any other Shiro/Stardog runtime error resolving the
            // subject falls back to anonymous so a broken auth path
            // doesn't take down the invocation.
            return null;
        }
    }

    public StardogWasmInstance(final URL wasmURL, final MappingDictionary mappingDictionary) throws ExecutionException {
        this(wasmURL);
        this.setMappingDictionary(mappingDictionary);
    }

    public StardogWasmInstance(final URL wasmUrl) throws ExecutionException {
        // Reuse the shared engine + cached component; the per-call
        // ComponentInstance is the only resource owned by this instance.
        final Component cached;
        try {
            cached = cachedComponentFor(wasmUrl);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        // Capability-policy — resolve the effective grant against
        // the RDF-backed policy store before wiring the linker so
        // we only wire interfaces the grant permits. When the
        // master gate is off (default), {@link
        // CapabilityEnforcer#activePolicy} returns empty and we
        // fall through to the pre-capability code path that wires
        // every interface unconditionally.
        //
        // The resolver may throw LoadTimeDenied /
        // UnknownExtension / PolicyStoreUnavailable — all
        // StardogException subtypes, so they unwind this
        // constructor and Call.evaluate /
        // WebFunctionServiceOperator surface them as-is
        // (mirroring WfBudgetError).
        final Optional<CapabilityEnforcer> enforcer = CapabilityEnforcer.activePolicy();
        final CapabilityGrant grant;
        if (enforcer.isPresent()) {
            final CallbackContext preCtx = CallbackContext.current();
            grant = enforcer.get().preInvocation(cached, wasmUrl);
            if (preCtx != null) {
                preCtx.setCapabilityGrant(grant);
                // Phase 4 — capture the invoker's Shiro subject so
                // HostCallbacks can wrap Stardog operations in
                // ShiroUtils.executeAs(subject, ...). Null when
                // the invoker is anonymous; the per-callback wrap
                // consults webfunctions.capability.anonymous-policy
                // to decide the fallback.
                preCtx.setInvokerSubject(currentInvokerSubjectOrNull());
            }
            // Capability-ask wave — extract, parse, and record
            // the extension's declared ask so the admin has a
            // SPARQL-queryable review surface, and the warn-on-
            // undeclared diagnostic in HostCallbacks has an ask
            // to compare against ({@code capability-ask.md}
            // §§6+8). Best-effort: section absent or parse
            // failure both proceed with a warning per §6 — the
            // grant still gates execution regardless.
            extractAndRecordAsk(wasmUrl, preCtx);
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
        this.instance = (ComponentInstance) cached.instantiate(
                componentLinker.build(),
                WebFunctionConfig.componentConfigFromSystemProperties());
    }

    /**
     * Capability-ask wave — extract the extension-declared ask from the
     * wasm's {@code stardog.capability-ask} custom section, parse the
     * Turtle payload, record it under the ask named graph in the
     * policy store, and stamp it onto the callback context so the
     * warn-on-undeclared diagnostic in {@link HostCallbacks} has an ask
     * to compare against.
     *
     * <p>Fail-open on every branch per {@code capability-ask.md} §6:
     * <ul>
     *   <li>bytes unavailable → log info + proceed with no ask stamped</li>
     *   <li>custom section absent → log info + proceed (many legitimate
     *       extensions predate the feature)</li>
     *   <li>Turtle parse failure → log warning + proceed (admin cannot
     *       review via SPARQL, but grant still gates execution)</li>
     *   <li>store recordAsk failure → the store logs internally and
     *       proceeds; ask is still stamped on the context for the
     *       runtime diagnostic</li>
     * </ul>
     * Grant resolution decides authorization; ask insertion is
     * diagnostic + review UX only.
     *
     * @param wasmUrl the extension URL — used as the ask's base IRI and
     *                as the store key.
     * @param ctx     the callback context to stamp the parsed ask onto,
     *                or {@code null} when no context is bound (isolated
     *                unit-test path).
     */
    private static void extractAndRecordAsk(final URL wasmUrl,
                                            final CallbackContext ctx) {
        final byte[] bytes;
        try {
            bytes = loadingCache.get(wasmUrl);
        } catch (ExecutionException e) {
            // Bytes fetch failed — capability enforcement's own load
            // path already failed too, so this branch is unreachable in
            // practice. Log defensively.
            System.err.println("[wf-cap-ask] bytes unavailable for "
                    + wasmUrl + ": " + e.getMessage());
            return;
        }
        final java.util.Optional<byte[]> sectionPayload;
        try {
            sectionPayload = WasmCustomSectionReader.extractSection(
                    bytes, "stardog.capability-ask");
        } catch (MalformedWasmException mwe) {
            // Malformed bytes at this point means the wasm somehow
            // decoded far enough for the substrate to instantiate but
            // fails the hand-rolled section walk. Unlikely; log and
            // proceed with no ask.
            System.err.println("[wf-cap-ask] custom-section scan failed for "
                    + wasmUrl + ": " + mwe.getMessage());
            return;
        }
        if (sectionPayload.isEmpty()) {
            // No ask declared — legitimate case per memo §6 (extension
            // predates the feature). Info-level; nothing to record.
            System.err.println("[wf-cap-ask] no stardog.capability-ask section on "
                    + wasmUrl + " (proceeding; admin has no ask to review)");
            if (ctx != null) ctx.setAsk(CapabilityAsk.EMPTY);
            return;
        }
        final CapabilityAsk ask;
        try {
            ask = CapabilityAskParser.parse(sectionPayload.get(), wasmUrl);
        } catch (IOException | RuntimeException parseFailure) {
            System.err.println("[wf-cap-ask] unparseable capability ask on "
                    + wasmUrl + " — admin cannot review via SPARQL: "
                    + parseFailure.getMessage());
            return;
        }
        // Best-effort store insert. The store swallows write failures
        // internally per capability-ask.md §6; we still stamp on the
        // context so the runtime diagnostic works even when the store
        // write races or the DB is momentarily unavailable.
        CapabilityPolicyResolver.policyStore()
                .ifPresent(store -> store.recordAsk(wasmUrl, ask));
        if (ctx != null) ctx.setAsk(ask);
    }

    private static Engine sharedEngine() {
        Engine e = SHARED_ENGINE;
        if (e != null) return e;
        synchronized (SHARED_ENGINE_LOCK) {
            if (SHARED_ENGINE == null) {
                final ai.tegmentum.webassembly4j.api.WebAssemblyBuilder engineBuilder =
                        WebAssembly.builder()
                                .provider(WebFunctionConfig.engineProvider())
                                .config(WebFunctionConfig.fromSystemProperties());
                WebFunctionConfig.engineId().ifPresent(engineBuilder::engine);
                final Engine built = engineBuilder.build();
                if (!built.capabilities().supportsComponents()) {
                    built.close();
                    throw new IllegalStateException(
                            "webfunction plugin requires component-model support; engine '"
                                    + built.info().engineId() + "' does not support components");
                }
                SHARED_ENGINE = built;
            }
            return SHARED_ENGINE;
        }
    }

    private static Component cachedComponentFor(final URL wasmUrl) throws IOException, ExecutionException {
        Component cached = COMPONENT_CACHE.get(wasmUrl);
        if (cached != null) return cached;
        final Engine engine = sharedEngine();
        final byte[] bytes = loadingCache.get(wasmUrl);
        // computeIfAbsent — engine.loadComponent doesn't throw checked IOException
        // so the lambda stays clean.
        return COMPONENT_CACHE.computeIfAbsent(wasmUrl, u -> engine.loadComponent(bytes));
    }

    public void setMappingDictionary(final MappingDictionary mappingDictionary) {
        this.mappingDictionaryIsSet = true;
        this.mappingDictionaryRef.set(mappingDictionary);
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        // The shared engine + cached component are not held on this
        // instance (they live in the static cache), so nothing to
        // release beyond the per-call ComponentInstance.
        //
        // Drop any live aggregate resource FIRST — the underlying
        // store-side handle it owns lives inside `instance`, so
        // releasing after we null `instance` would leak the handle.
        if (aggregateResource != null) {
            try {
                aggregateResource.close();
            } catch (RuntimeException ignore) {
                // Best-effort — a failing close here shouldn't mask the real
                // close reason (this instance is going away anyway).
            }
            aggregateResource = null;
        }
        instance = null;
        closed = true;
    }

    public Cardinality getCardinality(final Cardinality inputCardinality, List<Value> args) {
        // `cardinality-estimate` lives on the stardog-extension overlay
        // world (planner interface), not the base sparql-extension world.
        // Test components targeting the base world only cannot answer;
        // return the input cardinality (pessimistic identity estimate)
        // so the planner sees a valid Cardinality object rather than a trap.
        return inputCardinality;
    }

    public SelectQueryResult evaluate(final Value... values) throws IOException {
        stampComponentInstanceOnCurrentContext();
        return componentExtensionCall(values);
    }

    public SelectQueryResult doc() throws IOException {
        // `doc` lives on the stardog-extension overlay world, not the base
        // sparql-extension world. Test components target the base world only,
        // so `doc()` has no export to dispatch into. Return an empty
        // binding-sets rather than trap.
        return new SelectQueryResultImpl(java.util.Collections.emptyList(), java.util.Collections.emptyList());
    }

    public SelectQueryResult compute(final Value[] values, long multiplicity) throws IOException {
        stampComponentInstanceOnCurrentContext();
        componentAggregateStep(values, multiplicity);
        // aggregate.step returns void; the materialized value is fetched
        // separately via aggregateGetValue() through resource.finish.
        return new SelectQueryResultImpl(java.util.Collections.emptyList(), java.util.Collections.emptyList());
    }

    public SelectQueryResult aggregateGetValue() {
        stampComponentInstanceOnCurrentContext();
        return componentAggregateFinish();
    }

    /**
     * Plumb the underlying {@link ComponentInstance} onto the current
     * {@link CallbackContext} so {@link CallbackContext#chargeToll(String)}
     * (and {@link CallbackContext#fuelConsumed()}) can debit / read the
     * real store fuel through the wasmtime4j 1.4.7 / webassembly4j 2.4.3
     * API. No-op when there is no bound CallbackContext (embedded/test
     * callers that dispatch without binding a callback context).
     */
    private void stampComponentInstanceOnCurrentContext() {
        final CallbackContext ctx = CallbackContext.current();
        if (ctx == null) return;
        ctx.setComponentInstance(instance);
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
        final WitValue result = (WitValue) instance.invokeWit(
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
                final WitValue descriptors = (WitValue) instance.invokeWit(EXT_REGISTER);
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
        if (cachedAggregateName == null) {
            final WitValue descriptors = (WitValue) instance.invokeWit(AGG_REGISTER_AGGREGATES);
            cachedAggregateName = firstDescriptorName(descriptors, "register-aggregates",
                    "component exports no aggregates; aggregate.register-aggregates returned []");
        }
        final WitValue newAggReturn = (WitValue) instance.invokeWit(
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
        aggregateResource = instance.asCallableResource(okResource);
    }

    /**
     * Aggregate finish: call {@code finish()} on the cached resource, drop
     * it, and wrap the returned term as a 1x1 SelectQueryResult with the
     * conventional {@code value_0} binding name.
     */
    private SelectQueryResult componentAggregateFinish() {
        if (aggregateResource == null) {
            // No step ever ran — return an empty result for the
            // aggregate-with-no-inputs case.
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
