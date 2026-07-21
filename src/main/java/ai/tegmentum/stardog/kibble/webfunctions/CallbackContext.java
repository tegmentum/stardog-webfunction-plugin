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
import java.util.Optional;

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

    // Capability-policy Phase 1 — effective grant for this invocation.
    // Stamped by StardogWasmInstance at construction time (after the
    // enforcer resolves it) so HostCallbacks can consult it on every
    // dispatch via {@link #capabilityGrant()}. Null when capability
    // enforcement is disabled (webfunctions.capability.enabled=false) or
    // when the invocation reaches this context through a code path that
    // never binds one (isolated unit-test dispatch).
    private CapabilityGrant   capabilityGrant;

    // Capability-policy Phase 4 — invoker's Shiro subject, captured at
    // extension instantiation time and used by {@link HostCallbacks} to
    // wrap Stardog operations in {@code ShiroUtils.executeAs(subject, ...)}
    // so Stardog's own permission checks (graph ACLs, database ACLs,
    // named-graph permissions) fire for the invoker's identity rather
    // than the plugin's ambient credential. Explicit capture is more
    // defensive than an implicit ThreadLocal read: it protects against
    // subject rebinding between instantiation and dispatch, and follows
    // the invoker across any child threads a Stardog operation may
    // spawn. Null when capability enforcement is disabled or when the
    // invocation reached this context anonymously (Shiro reports no
    // authenticated subject); the Phase 4 wrap in {@link HostCallbacks}
    // consults {@code webfunctions.capability.anonymous-policy} to
    // decide the fallback.
    private org.apache.shiro.subject.Subject invokerSubject;

    // Capability-ask wave — the extension-declared ask extracted at
    // instantiation time from the wasm's `stardog.capability-ask` custom
    // section (or empty when the extension shipped without one). Stamped
    // by {@link StardogWasmInstance} right after the ask is inserted
    // into the policy store, so {@link HostCallbacks} can consult it on
    // every host-callback dispatch to fire the warn-on-undeclared
    // diagnostic ({@code capability-ask.md} §8). Null when capability
    // enforcement is disabled or when the extension had no ask.
    private CapabilityAsk capabilityAsk;

    // wasm-callbacks multi-level nesting state. Task-279 MVP tracked
    // only a bounded depth counter (single-level nesting cap); the
    // multi-level extension records the full invocation chain so
    // {@link #enterWasmCall(String)} can enforce both a configurable
    // depth cap ({@link WebFunctionConfig#wasmCallbacksMaxNestingDepth})
    // and cycle detection (same URL appearing twice in the chain).
    // Attribution rows carry a snapshot of this chain so operators can
    // trace the full path root → depth-N callee. The counter is derived
    // ({@link #wasmCallDepth} == {@link #callChain}.size()) — kept as
    // an explicit field for pre-existing callers that read depth().
    private int wasmCallDepth = 0;
    private final java.util.ArrayList<String> callChain = new java.util.ArrayList<>();

    // Deadline propagation — captured at bind time so nested host-callback
    // dispatches (invoke-wasm chains at any depth) inherit the outer
    // deadline through the shared ThreadLocal context. Two independent
    // sources feed the effective trip:
    //   * config side: WebFunctionConfig.execMaxMillis() -- resolved once
    //     at bind time into an absolute nanoTime() deadline (deadlineNanos).
    //     Zero means "no plugin-side deadline; substrate ceilings only".
    //   * monitor side: executionContext.getMonitor().isCancelled() checked
    //     live at each dispatch. Detects the outer query's Stardog-side
    //     cancellation (query timeout, admin kill, shutdown) at the plugin
    //     boundary so nested host callbacks unwind promptly instead of
    //     churning inside a doomed invocation.
    // Both checks run at every host-callback boundary via checkDeadline();
    // whichever trips first surfaces as WfBudgetError.DeadlineExceeded and
    // rides the same promotion path as the fuel toll traps.
    private final long deadlineNanos;    // 0 = no config-side deadline
    private final long deadlineMillis;   // config value at bind time (for error payload); 0 when unset
    private final long boundAtNanos;     // wall-clock start reference for elapsed accounting

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
        this.capabilityGrant = null;
        this.invokerSubject = null;
        this.capabilityAsk = null;

        // Deadline plumbing — snapshot the plugin-side cap (if configured)
        // as an absolute nanoTime() so subsequent checkDeadline() calls
        // avoid recomputing. Zero cap => no deadline; skip the arithmetic
        // and use 0L as sentinel throughout checkDeadline().
        this.boundAtNanos = System.nanoTime();
        final long capMs = WebFunctionConfig.execMaxMillis().orElse(0L);
        if (capMs > 0L) {
            this.deadlineMillis = capMs;
            this.deadlineNanos = boundAtNanos + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(capMs);
        } else {
            this.deadlineMillis = 0L;
            this.deadlineNanos = 0L;
        }
    }

    /**
     * Stamp the extension's declared capability ask onto this context.
     * Called by {@link StardogWasmInstance} after extraction + parse
     * (and after {@code recordAsk} lands into the policy store). Null
     * is a valid state — signals that the extension shipped without a
     * {@code stardog.capability-ask} custom section, or that capability
     * enforcement is disabled entirely. Consumed by
     * {@link HostCallbacks}' warn-on-undeclared diagnostic (§8 of the
     * capability-ask memo).
     */
    public void setAsk(final CapabilityAsk ask) {
        this.capabilityAsk = ask;
    }

    /**
     * The ask stamped on this context, or empty when none was
     * extracted (extension shipped without one, capability enforcement
     * off, or parse failed and the caller decided to proceed without
     * one per capability-ask memo §6). Consumed by {@link HostCallbacks}
     * to fire the warn-on-undeclared diagnostic.
     */
    public Optional<CapabilityAsk> ask() {
        return Optional.ofNullable(capabilityAsk);
    }

    /**
     * Stamp the wasm-provider {@link ComponentInstance} that is about to be
     * invoked so {@link #chargeToll(String)} can debit real store fuel via
     * {@link ComponentInstance#consumeFuel(long)}. Called by
     * {@link StardogWasmInstance} right before the guest export dispatches;
     * cleared when the CallbackContext is unbound. Null is a valid state
     * (isolated unit-test / embedded direct-instantiation flows that never
     * stamp a ComponentInstance; the toll then falls back to the Java-side
     * counter).
     */
    public void setComponentInstance(final ComponentInstance componentInstance) {
        this.componentInstance = componentInstance;
    }

    /**
     * Stamp the capability {@link CapabilityGrant} resolved by
     * {@link CapabilityEnforcer#preInvocation} onto this context so
     * {@link HostCallbacks} can enforce per-callback checks against it.
     * Called by {@link StardogWasmInstance} right after instantiation-time
     * grant resolution; null is a valid state (capability enforcement is
     * disabled).
     */
    public void setCapabilityGrant(final CapabilityGrant grant) {
        this.capabilityGrant = grant;
    }

    /**
     * The effective grant for this invocation, or empty when capability
     * enforcement is disabled or the context reached the host callback
     * through a code path that never stamped one. Consumed by
     * {@link CapabilityEnforcer#perCallback} via {@link HostCallbacks}.
     */
    public Optional<CapabilityGrant> capabilityGrant() {
        return Optional.ofNullable(capabilityGrant);
    }

    /**
     * Stamp the invoker's Shiro subject onto this context. Called by
     * {@link StardogWasmInstance} at instantiation time — right after
     * the {@code ShiroUtils.require} instantiation gate — so
     * {@link HostCallbacks} can later wrap every Stardog operation in
     * {@code ShiroUtils.executeAs(subject, () -> ...)}. Null is a
     * valid state (capability enforcement disabled, or the invoker is
     * anonymous — Shiro reports no authenticated subject).
     */
    public void setInvokerSubject(final org.apache.shiro.subject.Subject subject) {
        this.invokerSubject = subject;
    }

    /**
     * The invoker's Shiro subject captured at instantiation, or empty
     * when capability enforcement is disabled or the invoker was
     * anonymous. {@link HostCallbacks} passes the value into
     * {@code ShiroUtils.executeAs(...)} so Stardog's per-user permission
     * checks (graph ACLs, database ACLs, named-graph permissions) fire
     * for the invoker's identity rather than the plugin's ambient
     * credential.
     */
    public Optional<org.apache.shiro.subject.Subject> invokerSubject() {
        return Optional.ofNullable(invokerSubject);
    }

    /**
     * Query the store's actual fuel consumption for the currently-stamped
     * {@link ComponentInstance}, or {@code -1} when unavailable (no instance
     * stamped, or provider does not support
     * {@link ComponentInstance#fuelConsumed()} — non-wasmtime providers like
     * endive / chicory / wamr / graalwasm). Used by {@link FuelTrapMapper}
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
     * that throw {@link UnsupportedFeatureException} (endive, chicory,
     * wamr, graalwasm — anything non-wasmtime), or callers that never
     * stamped a ComponentInstance (isolated unit-test paths), fall
     * through to the Java-side {@link #tollUsed} counter.
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
        // Java-side fallback (no ComponentInstance stamped, or provider without consumeFuel).
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

    /**
     * Check the invocation's deadline at a host-callback boundary. No-op
     * when neither source is configured (config cap unset AND no bound
     * ExecutionContext / monitor). Called from
     * {@link HostCallbacks#enforceCapability} at the start of every host
     * callback dispatch so a nested chain (invoke-wasm at any depth,
     * host-callback fan-out at any depth) unwinds promptly at the next
     * boundary once either source trips.
     *
     * <p>Two independent trips, checked in order (cheapest first):
     * <ol>
     *   <li>Config-side — {@link System#nanoTime()} vs the deadline
     *       stamp captured at construction from
     *       {@link WebFunctionConfig#execMaxMillis()}.</li>
     *   <li>Monitor-side — {@code executionContext.getMonitor().isCancelled()}.
     *       Detects Stardog query-level cancellation (query timeout, admin
     *       kill, shutdown) at the plugin boundary.</li>
     * </ol>
     *
     * <p>On trip, throws {@link WfBudgetError.DeadlineExceeded} carrying
     * the callback name so the outer catch site in {@link Call#evaluate} /
     * {@link WebFunctionServiceOperator#computeNext} can pass it through
     * the {@link WfBudgetError} promotion path as a typed SPARQL error.
     * Monitor-side probe failure (RuntimeException from a torn-down
     * monitor) is swallowed — a broken monitor must not synthesize a
     * false deadline trip.
     */
    public void checkDeadline(final String callbackName) {
        // Config-side trip — cheap absolute comparison; nothing when no cap set.
        if (deadlineNanos != 0L && System.nanoTime() >= deadlineNanos) {
            final long elapsed = elapsedMillisSinceBind();
            throw new WfBudgetError.DeadlineExceeded(
                    extensionUri,
                    callbackName == null ? "" : callbackName,
                    elapsed,
                    deadlineMillis,
                    WfBudgetError.DeadlineExceeded.SOURCE_CONFIG);
        }
        // Monitor-side trip — Stardog's ExecutionMonitor reports cancellation
        // when the outer query hits its timeout or is admin-killed. Only the
        // SERVICE bind path carries a monitor; filter-function wf:call does
        // not, so this branch is a no-op there.
        if (executionContext != null) {
            try {
                final ExecutionMonitor monitor = executionContext.getMonitor();
                if (monitor != null && monitor.isCancelled()) {
                    final long elapsed = elapsedMillisSinceBind();
                    throw new WfBudgetError.DeadlineExceeded(
                            extensionUri,
                            callbackName == null ? "" : callbackName,
                            elapsed,
                            // deadline_millis: 0 signals "monitor drove
                            // this; the numeric cap did not"
                            0L,
                            WfBudgetError.DeadlineExceeded.SOURCE_MONITOR);
                }
            } catch (WfBudgetError propagate) {
                throw propagate;
            } catch (RuntimeException ignore) {
                // Torn-down or otherwise broken monitor — do not synthesize
                // a false deadline trip. Config-side check above still
                // guards a true wall-clock overrun.
            }
        }
    }

    /** Wall-clock elapsed millis since this context was constructed. */
    public long elapsedMillisSinceBind() {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - boundAtNanos);
    }

    /**
     * Effective plugin-side deadline in milliseconds captured at bind time,
     * or {@link java.util.OptionalLong#empty()} when no config cap was set.
     * Test / diagnostic accessor — the hot path uses the nanoTime stamp
     * directly through {@link #checkDeadline(String)}.
     */
    public java.util.OptionalLong deadlineMillisIfConfigured() {
        return deadlineMillis > 0L
                ? java.util.OptionalLong.of(deadlineMillis)
                : java.util.OptionalLong.empty();
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

    /**
     * Current wasm-callbacks nesting depth. Zero at the outermost frame;
     * bumped on entry to each {@code invoke-wasm} /
     * {@code invoke-wasm-service} dispatch, decremented on exit. Post
     * multi-level extension the depth is bounded by
     * {@link WebFunctionConfig#wasmCallbacksMaxNestingDepth} (default 8)
     * rather than the pre-existing single-level cap of 1.
     */
    public int wasmCallDepth() {
        return wasmCallDepth;
    }

    /**
     * Immutable snapshot of the current wasm-callbacks invocation
     * chain, ordered root → deepest. Used by {@link HostCallbacks} when
     * writing audit rows so operators can trace the full path a nested
     * dispatch took. Empty at the outermost frame.
     */
    public java.util.List<String> wasmCallChainSnapshot() {
        return java.util.List.copyOf(callChain);
    }

    /**
     * Enter a nested wasm-callbacks dispatch — bumps the depth counter
     * and appends {@code calleeUrl} to the {@link #callChain}. Called
     * by HostCallbacks right before dispatching into a callee's
     * evaluate. Must be paired with {@link #exitWasmCall(String)} in a
     * try/finally so a callee-side trap does not leave the counter or
     * chain stuck.
     *
     * <p>Two structural rules are enforced here (both surface
     * {@link WasmNestingException}, which the WIT boundary maps to the
     * {@code nesting-not-permitted} arm):
     * <ul>
     *   <li><b>Depth cap</b> — reaching depth {@code max + 1} is
     *       rejected. Max is
     *       {@link WebFunctionConfig#wasmCallbacksMaxNestingDepth}
     *       (default 8; configurable via
     *       {@code webfunctions.wasm-callbacks.max-nesting-depth}).</li>
     *   <li><b>Cycle detection</b> — if {@code calleeUrl} already
     *       appears anywhere in the current chain, the dispatch is a
     *       cycle and is rejected before entering. Comparison is
     *       string-equality (URLs on the wire are strings; no
     *       normalization).</li>
     * </ul>
     */
    public int enterWasmCall(final String calleeUrl) {
        final int max = WebFunctionConfig.wasmCallbacksMaxNestingDepth();
        if (wasmCallDepth >= max) {
            throw new WasmNestingException(
                    WasmNestingException.REASON_DEPTH_EXCEEDED,
                    "wasm-callbacks: nesting depth cap exceeded (attempted depth "
                            + (wasmCallDepth + 1) + " > max " + max + "); config: "
                            + WebFunctionConfig.PROP_WASM_CALLBACKS_MAX_NESTING_DEPTH,
                    calleeUrl,
                    callChain);
        }
        if (calleeUrl != null && !calleeUrl.isEmpty() && callChain.contains(calleeUrl)) {
            throw new WasmNestingException(
                    WasmNestingException.REASON_CYCLE_DETECTED,
                    "wasm-callbacks: cycle detected — callee '" + calleeUrl
                            + "' already appears in the invocation chain " + callChain,
                    calleeUrl,
                    callChain);
        }
        callChain.add(calleeUrl == null ? "" : calleeUrl);
        return ++wasmCallDepth;
    }

    /**
     * Legacy no-arg overload for pre-multi-level call sites (tests
     * that prime nesting state without a URL). Delegates with an
     * empty-string callee so the chain still bumps by one entry —
     * cycle detection ignores empty entries so priming with this
     * overload does not clash with a real URL-carrying dispatch.
     */
    public int enterWasmCall() {
        return enterWasmCall("");
    }

    /**
     * Exit a nested wasm-callbacks dispatch — pops the last entry off
     * the {@link #callChain} and decrements the counter. Callers pass
     * the same {@code calleeUrl} they entered with; the argument is
     * currently a documentation aid (the pop is by position, not by
     * value), but future defensive assertions may use it to catch
     * mismatched enter/exit pairs.
     */
    public int exitWasmCall(final String calleeUrl) {
        if (!callChain.isEmpty()) {
            callChain.remove(callChain.size() - 1);
        }
        return --wasmCallDepth;
    }

    /**
     * Legacy no-arg overload — pops the last chain entry regardless
     * of URL. Kept for pre-multi-level test call sites.
     */
    public int exitWasmCall() {
        return exitWasmCall(null);
    }

    /**
     * Typed exception thrown by {@link #enterWasmCall(String)} when
     * either the depth cap is exceeded or a cycle is detected in the
     * invocation chain. The WIT boundary catches this and maps it to
     * the {@code nesting-not-permitted} arm of the {@code wasm-call-error}
     * variant (the F4 tightening added the arm; this exception now
     * covers both structural failure modes uniformly).
     *
     * <p>The {@link #reason()} tag lets the boundary layer include the
     * discriminator ({@code depth-exceeded} vs {@code cycle-detected})
     * in the payload string without depending on the exception message
     * shape.
     */
    public static final class WasmNestingException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Discriminator tag — depth cap was hit. */
        public static final String REASON_DEPTH_EXCEEDED = "depth-exceeded";
        /** Discriminator tag — same URL already appears in the chain. */
        public static final String REASON_CYCLE_DETECTED = "cycle-detected";

        private final String reason;
        private final String calleeUrl;
        private final java.util.List<String> chainSnapshot;

        public WasmNestingException(final String reason,
                                    final String message,
                                    final String calleeUrl,
                                    final java.util.List<String> chain) {
            super(message);
            this.reason = reason;
            this.calleeUrl = calleeUrl == null ? "" : calleeUrl;
            this.chainSnapshot = chain == null
                    ? java.util.Collections.emptyList()
                    : java.util.List.copyOf(chain);
        }

        public String reason() { return reason; }
        public String calleeUrl() { return calleeUrl; }
        public java.util.List<String> chainSnapshot() { return chainSnapshot; }
    }

    /**
     * The wasm-provider {@link ComponentInstance} currently stamped on
     * this context (the callee's during a nested invoke-wasm dispatch,
     * the caller's otherwise), or {@code null} when nothing is
     * stamped. Package-private — HostCallbacks needs to snapshot the
     * caller's instance before the callee overwrites it so it can
     * restore + reflect the callee's fuel consumption after return.
     */
    ComponentInstance componentInstanceOrNull() {
        return componentInstance;
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
