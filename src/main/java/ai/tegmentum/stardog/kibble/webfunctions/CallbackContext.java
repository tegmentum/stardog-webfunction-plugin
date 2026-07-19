package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.webassembly4j.api.ComponentInstance;
import ai.tegmentum.webassembly4j.api.exception.UnsupportedFeatureException;
import ai.tegmentum.webassembly4j.api.exception.WebAssemblyException;
import com.complexible.stardog.db.ConnectableConnection;
import com.complexible.stardog.plan.eval.ExecutionContext;
import com.complexible.stardog.plan.eval.ExecutionMonitor;
import com.complexible.stardog.query.DefaultQueryFactory;
import com.complexible.stardog.query.QueryFactory;
import com.complexible.stardog.query.ReadQuery;
import com.complexible.stardog.query.UpdateQuery;
import com.stardog.stark.Value;
import com.stardog.stark.query.SelectQueryResult;

import java.util.Map;

/**
 * Per-thread context for v0.3.0 host callback imports.
 *
 * <p>Two bind points:
 * <ul>
 *   <li>{@link #bind()} — no query connection. From {@link Call#evaluate}
 *       ({@code wf:call} in a BIND). The filter-function evaluator does
 *       not thread an {@link ExecutionContext} through to us, so the
 *       {@code execute-query} import is unreachable on this path.</li>
 *   <li>{@link #bind(ExecutionContext)} — full context. From
 *       {@link WebFunctionServiceOperator} ({@code SERVICE wf:call}).
 *       Sub-queries run against the outer query's connection via
 *       {@link DefaultQueryFactory#select}, inheriting its transaction,
 *       reasoning, and cancellation state.</li>
 * </ul>
 *
 * <p>Nested {@code wf:call} reuses the outer binding: {@link #bind}
 * / {@link #bind(ExecutionContext)} both no-op when a context is already
 * bound, and {@link #unbindIfOutermost} only clears the ThreadLocal on
 * the outermost frame.
 */
public final class CallbackContext {

    private static final ThreadLocal<CallbackContext> CURRENT = new ThreadLocal<>();

    private final ExecutionContext executionContext;
    /**
     * The MappingDictionary the outer query is running against. Needed by
     * the v0.4 {@code invoke-wasm} host import so a recursive wasm
     * invocation can instantiate its own {@link StardogWasmInstance}
     * bound to the same dictionary as the caller. Optional — filter-
     * function wf:call passes it in via {@link #bind(MappingDictionary)},
     * SERVICE wf:call via {@link #bind(ExecutionContext, MappingDictionary)}.
     */
    private final com.complexible.stardog.index.dictionary.MappingDictionary dictionary;
    private final int maxDepth;
    private final int maxRows;
    private int depth = 0;

    // Fuel metering Phase 1/1.x — per-invocation toll accounting.
    //
    // Populated when the outer wf:call frame runs setFuelMeteringContext
    // (from Call.evaluate / WebFunctionServiceOperator.computeNext, before
    // dispatch). As of webassembly4j 2.4.3 / wasmtime4j 1.4.7 the store's
    // {@link ComponentInstance#consumeFuel(long)} is available on the
    // wasmtime provider — chargeToll debits the real store fuel so a
    // host-callback toll exhausts the same budget wasm instructions burn
    // against. The Java-side {@code tollUsed} counter is retained as a
    // best-effort observation for attribution rows and as the fallback
    // path when the active provider throws {@link UnsupportedFeatureException}
    // (endive, chicory, wamr, graalwasm — anything non-wasmtime).
    private String            extensionUri;             // "" until set
    private long              tollBudget;               // 0 disables toll checks
    private long              tollUsed;                 // running total this invocation
    private String            tollExhaustedCallback;    // sticky flag; null when not tripped
    private long              tollHostCallbackToll;     // per-callback toll amount
    private ComponentInstance componentInstance;        // null when not yet stamped or non-component mode

    // v0.3.2 prepared-query handles. The Stardog QueryFactory produces a
    // ReadQuery bound to a specific connection + monitor; we re-parse per
    // call today, but Stardog's kernel-level plan cache short-circuits the
    // heavy lifting on repeat text. Storing the string, not the ReadQuery,
    // because ReadQuery.execute() is not repeatable — parameters+state carry.
    private final java.util.Map<Integer, String> prepared = new java.util.HashMap<>();
    private int nextHandle = 1;

    private CallbackContext(final ExecutionContext executionContext,
                            final com.complexible.stardog.index.dictionary.MappingDictionary dictionary,
                            final int maxDepth,
                            final int maxRows) {
        this.executionContext = executionContext;
        this.dictionary = dictionary;
        this.maxDepth = maxDepth;
        this.maxRows = maxRows;
        this.extensionUri = "";
        this.tollBudget = 0L;
        this.tollUsed = 0L;
        this.tollExhaustedCallback = null;
        this.tollHostCallbackToll = 0L;
        this.componentInstance = null;
    }

    /**
     * Stamp the wasm-provider {@link ComponentInstance} that is about to be
     * invoked so {@link #chargeToll(String)} can debit real store fuel via
     * {@link ComponentInstance#consumeFuel(long)}. Called by
     * {@link StardogWasmInstance} right before the guest export dispatches;
     * cleared when the CallbackContext is unbound. Null is a valid state
     * (module-mode dispatch has no ComponentInstance to stamp; falls back to
     * the Java-side toll counter).
     */
    public void setComponentInstance(final ComponentInstance componentInstance) {
        this.componentInstance = componentInstance;
    }

    /**
     * Query the store's actual fuel consumption for the currently-stamped
     * {@link ComponentInstance}, or {@code -1} when unavailable (module mode,
     * no instance stamped, or provider does not support
     * {@link ComponentInstance#fuelConsumed()}). Used by {@link FuelTrapMapper}
     * and {@link UserFuelPolicy} to report real fuel usage in typed errors and
     * post-invocation accounting instead of the Java-side lower-bound toll.
     */
    public long fuelConsumed() {
        if (componentInstance == null) return -1L;
        try {
            return componentInstance.fuelConsumed();
        } catch (RuntimeException ignore) {
            // Provider-side fuel query threw; treat as unsupported. Sentinel
            // -1 signals "unknown" to callers; they fall back to tollUsed
            // or the per-invocation cap.
            return -1L;
        }
    }

    /**
     * Bind the fuel-metering state for the current wf:call frame. Called
     * from Call.evaluate / WebFunctionServiceOperator.computeNext after
     * the CallbackContext is bound and BEFORE the wasm instance is
     * invoked. No-op when {@link WebFunctionConfig#fuelEnabled()} is
     * false (Phase-1 defaults). Nested frames inherit the outer frame's
     * budget — same "reuse existing binding" invariant CallbackContext
     * already applies to the ExecutionContext.
     *
     * @param extensionUri the wasm URI being invoked (attribution field)
     * @param tollBudget total per-invocation toll budget (fuel units)
     * @param hostCallbackToll fixed toll amount per host-callback dispatch
     */
    public void setFuelMeteringContext(final String extensionUri,
                                        final long tollBudget,
                                        final long hostCallbackToll) {
        // Only stamp on the outermost frame (depth == 0). Nested frames
        // (invoke-wasm from within a host callback) inherit the outer
        // budget so a spammy chain still trips the same cap.
        if (this.tollBudget != 0L) return;
        this.extensionUri = extensionUri == null ? "" : extensionUri;
        this.tollBudget = tollBudget;
        this.tollUsed = 0L;
        this.tollExhaustedCallback = null;
        this.tollHostCallbackToll = hostCallbackToll;
    }

    /**
     * The extension URI stamped on this frame's fuel metering context,
     * used for attribution in {@link WfBudgetError} payloads. Empty
     * string when unset (fuel disabled, or wf:call frame never got
     * around to stamping).
     */
    public String extensionUri() {
        return extensionUri;
    }

    /**
     * Total toll fuel consumed across all host-callback dispatches in
     * this invocation. Useful in {@link WfBudgetError.PerInvocationTrap}
     * post-mortem to attribute how much of the per-invocation cap went
     * to host-callback toll vs. guest compute (the latter is not
     * distinguishable from Java-side without wasmtime4j surfacing
     * fuelConsumed to component-mode callers — Phase-2 work).
     */
    public long tollUsed() {
        return tollUsed;
    }

    /**
     * Non-null when this invocation tripped the toll cap in a host
     * callback; the value is the callback name. {@link Call#evaluate}
     * and {@link WebFunctionServiceOperator#computeNext} check this on
     * catch to promote the outer trap to a
     * {@link WfBudgetError.HostCallbackTollExhausted} instead of a
     * generic {@link WfBudgetError.PerInvocationTrap}.
     */
    public String tollExhaustedCallback() {
        return tollExhaustedCallback;
    }

    /**
     * Charge the configured host-callback toll for a named callback
     * before it dispatches. No-op when the toll budget is zero (fuel
     * disabled). Debits the store's real fuel through
     * {@link ComponentInstance#consumeFuel(long)} when the active provider
     * supports it (wasmtime as of webassembly4j 2.4.3), so the toll
     * exhausts the same budget wasm instructions burn against. Providers
     * that throw {@link UnsupportedFeatureException} (or the module-mode
     * dispatch path, which has no ComponentInstance to charge against)
     * fall through to the Java-side {@link #tollUsed} counter.
     *
     * <p>If the toll cannot be paid — real-store exhaustion, Java-side
     * overflow, or provider deduction failure — stamps
     * {@link #tollExhaustedCallback()} and throws
     * {@link WfBudgetError.HostCallbackTollExhausted} so the wasm frame
     * unwinds; the outer try/catch in {@link Call#evaluate} /
     * {@link WebFunctionServiceOperator#computeNext} promotes the caught
     * error into the typed SPARQL surface. Callers pass the WIT-path
     * callback name (e.g. {@code "graph-callbacks.execute-query"}) that
     * shows up in the JSON payload.
     */
    public void chargeToll(final String callbackName) {
        if (tollBudget <= 0L) return;                    // fuel metering off
        if (tollHostCallbackToll <= 0L) return;          // toll disabled
        // Real-store deduction path (wasmtime provider on component mode).
        // On any failure — WebAssemblyException (deduction overrun, provider
        // fault) or the deduction-exceeded ExecutionException surface — treat
        // it as toll exhaustion and promote to the typed SPARQL error.
        // UnsupportedFeatureException is caught separately below so unsupported
        // providers fall back to the Java-side toll counter without failing
        // the invocation.
        if (componentInstance != null) {
            try {
                componentInstance.consumeFuel(tollHostCallbackToll);
                tollUsed += tollHostCallbackToll;
                return;
            } catch (UnsupportedFeatureException unsupported) {
                // Fall through to the Java-side path below.
            } catch (WebAssemblyException | IllegalStateException exhausted) {
                tollExhaustedCallback = callbackName;
                final long consumed = fuelConsumed();
                throw new WfBudgetError.HostCallbackTollExhausted(
                        extensionUri,
                        callbackName,
                        consumed >= 0L ? consumed : tollUsed,
                        tollBudget,
                        tollHostCallbackToll);
            }
        }
        // Java-side fallback (module-mode, or provider without consumeFuel).
        if (tollUsed + tollHostCallbackToll > tollBudget) {
            tollExhaustedCallback = callbackName;
            throw new WfBudgetError.HostCallbackTollExhausted(
                    extensionUri,
                    callbackName,
                    tollUsed,
                    tollBudget,
                    tollHostCallbackToll);
        }
        tollUsed += tollHostCallbackToll;
    }

    /** Bind without a query context — sub-queries via execute-query unavailable. */
    public static CallbackContext bind() {
        return bind((com.complexible.stardog.index.dictionary.MappingDictionary) null);
    }

    /**
     * Bind with only the query's MappingDictionary — no ExecutionContext,
     * so execute-query is unavailable but invoke-wasm can still resolve
     * IRIs against the caller's dictionary. Used by filter-function
     * {@link Call} which has access to the dictionary through
     * ValueSolution but not to an ExecutionContext.
     */
    public static CallbackContext bind(
            final com.complexible.stardog.index.dictionary.MappingDictionary dictionary) {
        final CallbackContext existing = CURRENT.get();
        // Preserve nested (mid-callback) contexts so depth stays consistent;
        // always replace outermost so a stale-from-prior-query context
        // doesn't misroute sub-queries.
        if (existing != null && existing.depth > 0) return existing;
        final CallbackContext ctx = new CallbackContext(
                null,
                dictionary,
                WebFunctionConfig.callbackMaxDepth(),
                WebFunctionConfig.callbackMaxRows());
        CURRENT.set(ctx);
        return ctx;
    }

    /** Bind with the outer query's {@link ExecutionContext}. */
    public static CallbackContext bind(final ExecutionContext executionContext) {
        return bind(executionContext, executionContext == null ? null : executionContext.getMappings());
    }

    /**
     * Bind with both an {@link ExecutionContext} and an explicit
     * MappingDictionary. Used by the SERVICE path where the operator
     * already has both handles in scope.
     */
    public static CallbackContext bind(
            final ExecutionContext executionContext,
            final com.complexible.stardog.index.dictionary.MappingDictionary dictionary) {
        final CallbackContext existing = CURRENT.get();
        // Preserve nested (mid-callback) contexts so depth stays consistent;
        // always replace outermost so a stale-from-prior-query context
        // doesn't misroute sub-queries.
        if (existing != null && existing.depth > 0) return existing;
        final CallbackContext ctx = new CallbackContext(
                executionContext,
                dictionary,
                WebFunctionConfig.callbackMaxDepth(),
                WebFunctionConfig.callbackMaxRows());
        CURRENT.set(ctx);
        return ctx;
    }

    /**
     * The MappingDictionary the outer query is running against, if any.
     * v0.4 {@code invoke-wasm} needs this to construct a nested
     * {@link StardogWasmInstance}; older host imports do not.
     */
    public com.complexible.stardog.index.dictionary.MappingDictionary dictionary() {
        return dictionary;
    }

    public static void unbindIfOutermost(final CallbackContext ctx) {
        if (ctx.depth == 0 && CURRENT.get() == ctx) {
            CURRENT.remove();
        }
    }

    public static CallbackContext current() {
        return CURRENT.get();
    }

    public int enter() {
        if (depth >= maxDepth) {
            throw new RuntimeException(
                "wf callback depth limit exceeded: " + maxDepth
                + " (config: webfunctions.callback.max.depth)");
        }
        return ++depth;
    }

    public int exit() {
        return --depth;
    }

    public int depth() {
        return depth;
    }

    public int maxRows() {
        return maxRows;
    }

    /**
     * Run a SPARQL SELECT against the outer query's connection with the given
     * initial bindings pre-substituted. Uses {@link DefaultQueryFactory} so
     * the sub-query shares the connection's transaction, reasoning, and
     * cancellation manager with the outer query — the same "reuse the strategy"
     * semantics the RDF4J plugin gets from {@code strategy.precompile}.
     */
    public SelectQueryResult executeSelect(final String sparql,
                                           final Map<String, Value> initial) {
        if (executionContext == null) {
            throw new IllegalStateException(
                "wf callback: execute-query needs a bound ExecutionContext — "
                + "only SERVICE wf:call binds one. Filter-function wf:call "
                + "(BIND) cannot access the outer connection through Stardog's "
                + "filter-function API.");
        }
        final ConnectableConnection conn = executionContext.getConnection();
        final ExecutionMonitor monitor = executionContext.getMonitor();
        final QueryFactory qf = new DefaultQueryFactory(conn, () -> monitor);
        final ReadQuery<SelectQueryResult> q;
        try {
            q = qf.select("", sparql, null);
        } catch (com.stardog.stark.query.MalformedQuery mq) {
            throw new RuntimeException(mq);
        }
        for (Map.Entry<String, Value> e : initial.entrySet()) {
            q.parameter(e.getKey(), e.getValue());
        }
        return q.execute();
    }

    /** v0.3.2 prepare-query — store SPARQL text; Stardog's plan cache
     *  short-circuits recompilation on repeat text. */
    public int prepare(final String sparql) {
        final int h = nextHandle++;
        prepared.put(h, sparql);
        return h;
    }

    /**
     * v0.3.3 follow-predicate — {@code SELECT ?o WHERE { $s $p ?o }} with
     * the subject and predicate substituted. Stardog's kernel plan cache
     * short-circuits recompilation on repeated invocations of this text.
     * Not as fast as direct triple-source access (RDF4J + Jena go there),
     * but this preserves transaction semantics via the outer connection.
     */
    public java.util.List<Value> followPredicate(final Value subject, final Value predicate) {
        final Map<String, Value> bindings = new java.util.LinkedHashMap<>();
        bindings.put("s", subject);
        bindings.put("p", predicate);
        final java.util.List<Value> out = new java.util.ArrayList<>();
        try (SelectQueryResult rs = executeSelect(
                "SELECT ?o WHERE { ?s ?p ?o }", bindings)) {
            while (rs.hasNext()) {
                final com.stardog.stark.query.BindingSet bs = rs.next();
                final Value v = bs.get("o");
                if (v != null) out.add(v);
            }
        }
        return out;
    }

    /** v0.3.2 run-prepared — evaluate a prepared handle with fresh bindings. */
    public SelectQueryResult runPrepared(final int handle, final Map<String, Value> initial) {
        final String sparql = prepared.get(handle);
        if (sparql == null) {
            throw new RuntimeException("wf callback: unknown prepared handle " + handle);
        }
        return executeSelect(sparql, initial);
    }

    /**
     * v0.3.1 execute-update — SPARQL 1.1 UPDATE against the outer query's
     * connection. Runs through the same {@link DefaultQueryFactory}, so
     * initial-binding substitution and the outer transaction apply exactly
     * as with {@link #executeSelect}.
     */
    public void executeUpdate(final String sparql, final Map<String, Value> initial) {
        if (executionContext == null) {
            throw new IllegalStateException(
                "wf callback: execute-update needs a bound ExecutionContext");
        }
        final ConnectableConnection conn = executionContext.getConnection();
        final ExecutionMonitor monitor = executionContext.getMonitor();
        final QueryFactory qf = new DefaultQueryFactory(conn, () -> monitor);
        final UpdateQuery upd;
        try {
            upd = qf.update("", sparql, null);
        } catch (com.stardog.stark.query.MalformedQuery mq) {
            throw new RuntimeException(mq);
        }
        for (Map.Entry<String, Value> e : initial.entrySet()) {
            upd.parameter(e.getKey(), e.getValue());
        }
        upd.execute();
    }
}
