package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.wasmtime4j.component.ComponentVariant;
import ai.tegmentum.webassembly4j.api.WitHostFunction;

import com.complexible.stardog.security.ShiroUtils;
import com.stardog.stark.BNode;
import com.stardog.stark.IRI;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.Values;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.SelectQueryResult;
import org.apache.shiro.subject.Subject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Host callbacks satisfying the v0.3.0 WIT world's {@code host} import
 * interface. Uses {@link ComponentVal} at the linker boundary — same pattern
 * as the RDF4J and Jena bindings, adapted to Stardog's {@link Value} hierarchy.
 */
public final class HostCallbacks {

    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private HostCallbacks() {}

    /**
     * Capability-policy Phase 1 — per-callback enforcement gate. Called
     * as the first thing on every host-callback dispatch (before the
     * fuel toll), so a denial unwinds through the wasm frame without
     * side effects. No-op when the master gate is off — {@link
     * CapabilityEnforcer#activePolicy} returns empty. Also no-op when
     * there is no bound {@link CallbackContext} (isolated unit-test or
     * embedded direct-instantiation paths) — those flows have never
     * been on the capability-enforced hot path.
     *
     * <p>On {@link WfCapabilityError.PerCallDenied} the enforcer throws;
     * the exception propagates out of the wit-host lambda, wasmtime4j
     * bubbles it into the outer {@link Call#evaluate} /
     * {@link WebFunctionServiceOperator#computeNext} catch surface where
     * it lands as a typed SPARQL error alongside {@link WfBudgetError}.
     */
    private static void enforceCapability(final CallbackContext ctx,
                                          final String interfaceName,
                                          final String method,
                                          final String argsSummary) {
        if (ctx == null) return;
        final java.util.Optional<CapabilityEnforcer> enforcer = CapabilityEnforcer.activePolicy();
        if (enforcer.isEmpty()) return;
        final CapabilityGrant grant = ctx.capabilityGrant().orElse(null);
        enforcer.get().perCallback(ctx, grant, interfaceName, method, argsSummary);
        // Grant permitted — capability-ask warn-on-undeclared diagnostic
        // ({@code capability-ask.md} §8). Fires ONLY when the extension
        // shipped an ask (ctx.ask() present); a missing ask is already
        // reported at load-time and doesn't merit per-callback audit
        // noise. When present, the invoked (interface, method) tuple
        // must appear in the ask or a GRANTED_UNDECLARED row lands.
        // Dispatch proceeds either way — this is diagnostic, not
        // authorization.
        warnIfUndeclared(ctx, grant, interfaceName, method, argsSummary);
    }

    /**
     * Capability-ask §8 warn-on-undeclared. Called after the grant check
     * has permitted the dispatch; skips when no ask is stamped
     * (extension shipped without one, capability disabled). Writes one
     * {@link CapabilityAuditRow.Outcome#GRANTED_UNDECLARED} row per
     * undeclared dispatch and returns — never throws, never denies.
     *
     * <p>Kept package-private so tests can drive the check in isolation
     * from the wasm dispatch stack (same pattern as
     * {@link #enforceHttpPathCapability} / {@link #enforceWasmCalleeCapability}).
     */
    static void warnIfUndeclared(final CallbackContext ctx,
                                 final CapabilityGrant grant,
                                 final String interfaceName,
                                 final String method,
                                 final String argsSummary) {
        final Optional<CapabilityAsk> askOpt = ctx.ask();
        if (askOpt.isEmpty()) return;
        final CapabilityAsk ask = askOpt.get();
        if (ask.declaresInterface(interfaceName)
                && ask.declaresMethod(interfaceName, method)) {
            return; // declared — nothing to record
        }
        final String invoker = grant == null ? "" : grant.invokerPrincipal();
        final String extensionUri = ctx.extensionUri();
        // Attach the current wasm-callbacks invocation chain snapshot
        // so an operator scanning the audit ring can see the full path
        // root → deepest callee that fired the undeclared dispatch.
        // Empty list for non-wasm-callbacks dispatches (matches
        // CallbackContext.wasmCallChainSnapshot's empty-at-outermost
        // semantics).
        CapabilityAttributionRing.recordGrantedUndeclared(
                invoker, extensionUri, interfaceName, method, argsSummary,
                ctx.wasmCallChainSnapshot());
    }

    /**
     * Capability-policy Phase 4 — invoker-subject wrap. Runs the given
     * Stardog-touching body under {@code ShiroUtils.executeAs(invokerSubject,
     * ...)} so Stardog's own permission checks (graph ACLs, database
     * ACLs, named-graph permissions) fire for the invoker's identity
     * rather than the plugin's ambient credential.
     *
     * <p>Gated behind {@link CapabilityEnforcer#activePolicy}: when
     * capability enforcement is off (default), the body runs directly
     * — pre-Phase-4 behavior preserved, no ShiroUtils.executeAs calls
     * fire.
     *
     * <p>When capability is on but no invoker subject was captured
     * (anonymous), consults {@link WebFunctionConfig#getAnonymousPolicy}:
     * <ul>
     *   <li>{@code deny} → throw
     *       {@link WfCapabilityError.PerCallDenied} with reason
     *       {@code permission-denied} before the body runs.</li>
     *   <li>{@code permit} / {@code inherit} → run the body under the
     *       plugin's ambient credential (current pre-Phase-4 behavior
     *       for anonymous under permissive policy).</li>
     * </ul>
     *
     * <p>Kept package-private so tests can drive the wrap in isolation
     * from the wasm dispatch stack.
     */
    static Object[] executeAsInvoker(final CallbackContext ctx,
                                     final String interfaceName,
                                     final String method,
                                     final String argsSummary,
                                     final Supplier<Object[]> body) {
        if (CapabilityEnforcer.activePolicy().isEmpty()) {
            // Capability disabled — fall through to the pre-Phase-4
            // ambient-credential path. No ShiroUtils.executeAs call fires.
            return body.get();
        }
        final Optional<Subject> subject = ctx == null
                ? Optional.empty()
                : ctx.invokerSubject();
        if (subject.isPresent()) {
            return ShiroUtils.executeAs(subject.get(), body::get);
        }
        // Capability enabled + anonymous invoker — consult the shared
        // anonymous-policy config key (reused from Phase 1b instead of
        // introducing a Phase-4-specific variant).
        final CapabilityPolicyResolver.AnonymousPolicy anon =
                WebFunctionConfig.getAnonymousPolicy();
        if (anon == CapabilityPolicyResolver.AnonymousPolicy.DENY) {
            final String extensionUri = ctx == null ? "" : ctx.extensionUri();
            throw new WfCapabilityError.PerCallDenied(
                    extensionUri,
                    interfaceName,
                    method,
                    "",
                    WfCapabilityError.PerCallDenied.REASON_PERMISSION_DENIED,
                    "anonymous invoker not permitted under current capability policy"
                            + (argsSummary == null || argsSummary.isEmpty()
                                    ? "" : " (args: " + argsSummary + ")"));
        }
        // permit / inherit — fall through to the pre-Phase-4 ambient
        // path, matching the CapabilityPolicyResolver anonymous-subject
        // semantics.
        return body.get();
    }

    /**
     * Extract the hostname (or full URL when parsing fails) for use as
     * {@code argsSummary} on {@code http-callbacks/*} dispatches; the
     * {@link HostAllowlist} matcher applied by
     * {@link CapabilityEnforcer#perCallback} compares against exactly
     * this value.
     */
    private static String hostnameFromUrl(final String url) {
        if (url == null) return "";
        try {
            final String host = java.net.URI.create(url).getHost();
            return host == null ? url : host;
        } catch (RuntimeException ignore) {
            return url;
        }
    }

    /**
     * Phase 5 — HTTP path prefix allowlist check. Called after
     * {@link #enforceCapability} has cleared the coarser interface +
     * method + host axes. No-op when the master gate is off, when no
     * grant is bound, or when the extension's grant carries an empty
     * {@link HttpPathAllowlist} (empty ⇒ no restriction).
     *
     * <p>Kept package-private so tests can drive the check in isolation
     * from the wasm dispatch stack.
     */
    static void enforceHttpPathCapability(final CallbackContext ctx,
                                          final String method,
                                          final String url) {
        if (ctx == null) return;
        final Optional<CapabilityEnforcer> enforcer = CapabilityEnforcer.activePolicy();
        if (enforcer.isEmpty()) return;
        final CapabilityGrant grant = ctx.capabilityGrant().orElse(null);
        enforcer.get().enforceHttpPath(grant, ctx.extensionUri(), method, url,
                ctx.wasmCallChainSnapshot());
    }

    /**
     * Phase 5 — wasm callee URL allowlist check. Called after
     * {@link #enforceCapability} has cleared the coarser interface +
     * method axes. No-op when the master gate is off, when no grant is
     * bound, or when the extension's grant carries an empty
     * {@link WasmCalleeAllowlist} (empty ⇒ no restriction).
     *
     * <p>URL-scheme-agnostic: callee URLs may be ipfs://, https://,
     * file://, or any URL scheme.
     *
     * <p>Kept package-private so tests can drive the check in isolation.
     */
    static void enforceWasmCalleeCapability(final CallbackContext ctx,
                                            final String method,
                                            final String calleeUrl) {
        if (ctx == null) return;
        final Optional<CapabilityEnforcer> enforcer = CapabilityEnforcer.activePolicy();
        if (enforcer.isEmpty()) return;
        final CapabilityGrant grant = ctx.capabilityGrant().orElse(null);
        enforcer.get().enforceWasmCallee(grant, ctx.extensionUri(), method, calleeUrl,
                ctx.wasmCallChainSnapshot());
    }

    /** First-N-chars snippet for a SPARQL text argsSummary. */
    private static String snippet(final String s, final int maxChars) {
        if (s == null) return "";
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }

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
            final String sparqlPreview = args.length > 0
                    ? snippet(((ComponentVal) args[0]).asString(), 60) : "";
            enforceCapability(ctx, "graph-callbacks", "execute-query", sparqlPreview);
            // Phase 1 fuel-metering toll — throws WfBudgetError on
            // exhaustion, unwinds the wasm frame, gets promoted to a
            // typed WF_HOST_CALLBACK_TOLL_EXHAUSTED at the outer
            // Call.evaluate / WebFunctionServiceOperator catch site.
            ctx.chargeToll("host.execute-query");
            return executeAsInvoker(ctx, "graph-callbacks", "execute-query", sparqlPreview, () -> {
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
            });
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
            enforceCapability(ctx, "graph-callbacks", "follow-predicate", "");
            ctx.chargeToll("host.follow-predicate");
            return executeAsInvoker(ctx, "graph-callbacks", "follow-predicate", "", () -> {
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
            });
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
            final String prepPreview = args.length > 0
                    ? snippet(((ComponentVal) args[0]).asString(), 60) : "";
            enforceCapability(ctx, "graph-callbacks", "prepare-query", prepPreview);
            ctx.chargeToll("host.prepare-query");
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
            enforceCapability(ctx, "graph-callbacks", "run-prepared", "");
            ctx.chargeToll("host.run-prepared");
            return executeAsInvoker(ctx, "graph-callbacks", "run-prepared", "", () -> {
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
            });
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
            final String updPreview = args.length > 0
                    ? snippet(((ComponentVal) args[0]).asString(), 60) : "";
            enforceCapability(ctx, "graph-callbacks", "execute-update", updPreview);
            ctx.chargeToll("host.execute-update");
            return executeAsInvoker(ctx, "graph-callbacks", "execute-update", updPreview, () -> {
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
            });
        };
    }

    /**
     * v0.4 {@code invoke-wasm: func(url: string, args: list<value>)
     * -> result<binding-sets, string>}.
     *
     * <p>Recursively invokes another wasm component identified by {@code url},
     * with the caller-supplied positional {@code args}. The nested guest runs
     * in a fresh {@link StardogWasmInstance} bound to the same MappingDictionary
     * as the caller — {@link CallbackContext#dictionary()} is what makes that
     * possible, so a v0.4 world guest linked against invoke-wasm must be called
     * from a code path that binds the dictionary onto the callback context
     * (see {@link Call#evaluate}).
     *
     * <p>The depth counter is bumped around the invocation so the host's
     * recursion cap covers invoke-wasm chains, and a nested wf:call frame's
     * execute-query still sees the correct current depth on return.
     */
    public static WitHostFunction invokeWasm() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: invoke-wasm has no context bound — nested guest "
                    + "was reached from a code path that didn't bind CallbackContext")) };
            }
            if (ctx.dictionary() == null) {
                return new Object[] { ComponentVal.err(ComponentVal.string(
                    "wf callback: invoke-wasm needs the outer query's MappingDictionary "
                    + "on the CallbackContext — bind with `bind(dictionary)` or "
                    + "`bind(execCtx, dictionary)` at the top of the wf:call frame")) };
            }
            final String invokeUrl = args.length > 0
                    ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "wasm-callbacks", "invoke-wasm", invokeUrl);
            enforceWasmCalleeCapability(ctx, "invoke-wasm", invokeUrl);
            ctx.chargeToll("host.invoke-wasm");
            return executeAsInvoker(ctx, "wasm-callbacks", "invoke-wasm", invokeUrl, () -> {
                try {
                    final String url = ((ComponentVal) args[0]).asString();
                    final Value urlValue = Values.iri(url);

                    // Decode the WIT list<value> into Stardog Value[]. Same shape
                    // Call.java hands to StardogWasmInstance.evaluate — this is
                    // the identity path.
                    final ComponentVal argsList = (ComponentVal) args[1];
                    final List<ComponentVal> inner = argsList.asList();
                    final Value[] callArgs = new Value[inner.size()];
                    for (int i = 0; i < inner.size(); i++) {
                        callArgs[i] = decodeNode(inner.get(i));
                    }

                    ctx.enter();
                    try (StardogWasmInstance instance =
                            StardogWasmInstance.from(urlValue, ctx.dictionary())) {
                        try (SelectQueryResult rs = instance.evaluate(callArgs)) {
                            return new Object[] { ComponentVal.ok(
                                encodeBindingSets(rs, ctx.maxRows())) };
                        }
                    } finally {
                        ctx.exit();
                    }
                } catch (Exception e) {
                    return new Object[] { ComponentVal.err(ComponentVal.string(
                        "invoke-wasm: " + (e.getMessage() == null ? e.toString() : e.getMessage()))) };
                }
            });
        };
    }

    /** {@code callback-depth: func() -> u32}. */
    public static WitHostFunction callbackDepth() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            return new Object[] { ComponentVal.u32(ctx == null ? 0L : (long) ctx.depth()) };
        };
    }

    // ---- tegmentum:webfunction/graph-callbacks@0.1.0 ------------------------

    /**
     * Base-substrate {@code tegmentum:webfunction/graph-callbacks@0.1.0#execute-query:
     *  func(sparql: string) -> result<query-result, graph-call-error>}.
     *
     * <p>Bridges the base {@code tegmentum:webfunction} substrate WIT onto
     * the existing {@link CallbackContext#executeSelect} executor. Distinct
     * from {@link #executeQuery} — which speaks the legacy Stardog
     * {@code stardog:webfunction/host} shape (three args, {@code binding-sets}
     * record return) — this one accepts a single query string and returns
     * the base {@code query-result} variant (bindings / quads / boolean arms).
     *
     * <p>MVP: unifies onto the {@code bindings} arm. SELECT results come
     * through as a flat {@code list<binding>}; the base WIT's separate
     * {@code quads} and {@code boolean} arms are a follow-up shape refinement.
     *
     * <p>Error discrimination:
     * <ul>
     *   <li>Callback disabled → {@code not-permitted}.</li>
     *   <li>Parse failure ({@link com.stardog.stark.query.MalformedQuery},
     *       possibly wrapped in a RuntimeException by
     *       {@link CallbackContext}) → {@code syntax-error}.</li>
     *   <li>{@link SecurityException} or Shiro {@code AuthorizationException}
     *       / {@code AuthenticationException} → {@code not-permitted}.</li>
     *   <li>Everything else → {@code backend-error}.</li>
     * </ul>
     */
    public static WitHostFunction graphExecuteQuery() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(
                    graphCallError("not-permitted",
                        "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(
                    graphCallError("backend-error",
                        "wf callback: no context bound")) };
            }
            final String graphQPreview = args.length > 0
                    ? snippet(((ComponentVal) args[0]).asString(), 60) : "";
            enforceCapability(ctx, "graph-callbacks", "execute-query", graphQPreview);
            ctx.chargeToll("graph-callbacks.execute-query");
            return executeAsInvoker(ctx, "graph-callbacks", "execute-query", graphQPreview, () -> {
                try {
                    final String sparql = ((ComponentVal) args[0]).asString();
                    ctx.enter();
                    try (SelectQueryResult rs = ctx.executeSelect(sparql, new LinkedHashMap<>())) {
                        final List<String> vars = rs.variables();
                        final List<ComponentVal> bindings = new ArrayList<>();
                        while (rs.hasNext()) {
                            final BindingSet bs = rs.next();
                            for (String var : vars) {
                                final Value v = bs.get(var);
                                if (v == null) continue;
                                final Map<String, ComponentVal> bindingFields = new LinkedHashMap<>();
                                bindingFields.put("variable", ComponentVal.string(var));
                                bindingFields.put("value", encodeTermV1(v));
                                bindings.add(ComponentVal.record(bindingFields));
                            }
                        }
                        final ComponentVal queryResult = ComponentVal.variant(
                            "bindings", ComponentVal.list(bindings));
                        return new Object[] { ComponentVal.ok(queryResult) };
                    } finally {
                        ctx.exit();
                    }
                } catch (RuntimeException e) {
                    return new Object[] { ComponentVal.err(discriminateGraphError(e)) };
                }
            });
        };
    }

    /**
     * Base-substrate {@code tegmentum:webfunction/graph-callbacks@0.1.0#execute-update:
     *  func(sparql: string) -> result<_, graph-call-error>}.
     *
     * <p>Bridges to {@link CallbackContext#executeUpdate} with the same error
     * discrimination as {@link #graphExecuteQuery} — see its javadoc for the
     * exception → variant mapping.
     */
    public static WitHostFunction graphExecuteUpdate() {
        return args -> {
            if (!WebFunctionConfig.callbackEnabled()) {
                return new Object[] { ComponentVal.err(
                    graphCallError("not-permitted",
                        "wf callback disabled by webfunctions.callback.enabled=false")) };
            }
            final CallbackContext ctx = CallbackContext.current();
            if (ctx == null) {
                return new Object[] { ComponentVal.err(
                    graphCallError("backend-error",
                        "wf callback: no context bound")) };
            }
            final String graphUPreview = args.length > 0
                    ? snippet(((ComponentVal) args[0]).asString(), 60) : "";
            enforceCapability(ctx, "graph-callbacks", "execute-update", graphUPreview);
            ctx.chargeToll("graph-callbacks.execute-update");
            return executeAsInvoker(ctx, "graph-callbacks", "execute-update", graphUPreview, () -> {
                try {
                    final String sparql = ((ComponentVal) args[0]).asString();
                    ctx.enter();
                    try {
                        ctx.executeUpdate(sparql, new LinkedHashMap<>());
                        return new Object[] { ComponentVal.ok(null) };
                    } finally {
                        ctx.exit();
                    }
                } catch (RuntimeException e) {
                    return new Object[] { ComponentVal.err(discriminateGraphError(e)) };
                }
            });
        };
    }

    /** Build a {@code graph-call-error} variant value with the given arm and message. */
    private static ComponentVal graphCallError(final String armName, final String message) {
        return ComponentVal.variant(armName, ComponentVal.string(message));
    }

    /**
     * Map a caught {@link RuntimeException} to the appropriate
     * {@code graph-call-error} variant. Parse-time exceptions
     * ({@link com.stardog.stark.query.MalformedQuery}, wrapped or not) map to
     * {@code syntax-error}; security failures map to {@code not-permitted};
     * every other runtime failure lands on {@code backend-error} (the
     * preserved MVP default).
     *
     * <p>{@link CallbackContext} wraps MalformedQuery in a RuntimeException
     * before it escapes, so we walk the cause chain to catch it. Shiro's
     * {@code AuthorizationException} isn't a Java {@link SecurityException},
     * so we match by class-name suffix rather than take a compile-time dep
     * on shiro-core just for this discrimination.
     */
    private static ComponentVal discriminateGraphError(final RuntimeException e) {
        final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        Throwable cur = e;
        int hops = 0;
        while (cur != null && hops < 8) {
            if (cur instanceof com.stardog.stark.query.MalformedQuery) {
                final String parseMsg = cur.getMessage() == null ? cur.toString() : cur.getMessage();
                return graphCallError("syntax-error", parseMsg);
            }
            if (cur instanceof SecurityException) {
                final String secMsg = cur.getMessage() == null ? cur.toString() : cur.getMessage();
                return graphCallError("not-permitted", secMsg);
            }
            final String cn = cur.getClass().getName();
            if (cn.endsWith("AuthorizationException") || cn.endsWith("AuthenticationException")) {
                final String secMsg = cur.getMessage() == null ? cur.toString() : cur.getMessage();
                return graphCallError("not-permitted", secMsg);
            }
            cur = cur.getCause();
            hops++;
        }
        return graphCallError("backend-error", msg);
    }

    /**
     * Encode a Stardog {@link Value} as the base {@code tegmentum:webfunction/types.term}
     * variant (4 arms: named-node / blank-node / literal / triple). Distinct
     * from the legacy {@link #encodeNode} — which produces the 3-arm
     * {@code stardog:webfunction/host} {@code value} variant with the legacy
     * literal record shape ({@code label} / {@code datatype: string} /
     * {@code lang}).
     *
     * <p>Base literal record uses {@code value: string}, {@code datatype:
     * option<iri>} (absent means xsd:string), and {@code language:
     * option<string>}. RDF-star quoted triples raise — the executor path
     * does not surface them today.
     */
    private static ComponentVal encodeTermV1(final Value v) {
        if (v instanceof IRI) {
            return ComponentVal.variant("named-node", ComponentVal.string(v.toString()));
        }
        if (v instanceof BNode) {
            return ComponentVal.variant("blank-node", ComponentVal.string(((BNode) v).id()));
        }
        if (v instanceof Literal) {
            final Literal lit = (Literal) v;
            final String label = lit.label();
            final String datatypeUri = lit.datatypeIRI() == null ? null : lit.datatypeIRI().toString();
            final Optional<String> lang = lit.lang();
            final Map<String, ComponentVal> fields = new LinkedHashMap<>();
            fields.put("value", ComponentVal.string(label));
            if (datatypeUri == null || datatypeUri.isEmpty()
                    || XSD_STRING.equals(datatypeUri)) {
                fields.put("datatype", ComponentVal.none());
            } else {
                fields.put("datatype", ComponentVal.some(ComponentVal.string(datatypeUri)));
            }
            fields.put("language", lang.isPresent()
                    ? ComponentVal.some(ComponentVal.string(lang.get()))
                    : ComponentVal.none());
            return ComponentVal.variant("literal", ComponentVal.record(fields));
        }
        throw new IllegalArgumentException(
            "wf graph-callbacks: unsupported Value kind for base-WIT term: " + v);
    }

    // ---- tegmentum:webfunction/http-callbacks@0.1.0 -------------------------

    /**
     * Base-substrate {@code tegmentum:webfunction/http-callbacks@0.1.0#http-get:
     *  func(url: string, headers: list<http-header>) -> result<http-response, http-error>}.
     *
     * <p>Impl uses JDK-native {@code java.net.http.HttpClient} — no external
     * HTTP dep needed. Header casing on the way in is preserved as-sent;
     * response headers come back in the casing HttpClient returns (JDK
     * canonicalises Http/1.1 headers to lowercase per RFC 7230 §3.2).
     *
     * <p>Error surface:
     * <ul>
     *   <li>Malformed URL / invalid header shape → {@code invalid-request}.</li>
     *   <li>Non-2xx response → {@code status(u16)} — naked status code.</li>
     *   <li>IOException / transport failure / interrupt → {@code network}.</li>
     * </ul>
     * A 2xx response returns {@code Ok(http-response)}.
     */
    public static WitHostFunction httpGet() {
        return args -> {
            // Fuel toll — HTTP callbacks pay the toll same as graph callbacks.
            // Skips the charge when no context is bound (isolated
            // unit-test / direct-instantiation flows without
            // Call.evaluate/WebFunctionServiceOperator wrapping), so
            // outside-the-invocation-hot-path calls stay unmetered.
            final CallbackContext ctx = CallbackContext.current();
            final String getUrl = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "http-callbacks", "http-get", hostnameFromUrl(getUrl));
            enforceHttpPathCapability(ctx, "http-get", getUrl);
            if (ctx != null) ctx.chargeToll("http-callbacks.http-get");
            try {
                final String url = ((ComponentVal) args[0]).asString();
                final List<ComponentVal> headers = ((ComponentVal) args[1]).asList();
                return new Object[] { httpSend("GET", url, headers, null) };
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(httpError("invalid-request",
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    /**
     * Base-substrate {@code tegmentum:webfunction/http-callbacks@0.1.0#http-post-json:
     *  func(url: string, body: string, headers: list<http-header>)
     *   -> result<http-response, http-error>}.
     *
     * <p>Adds a default {@code Content-Type: application/json} header when the
     * caller does not supply one. Same error surface as {@link #httpGet}.
     * Distinct method name ({@code httpPostJsonV1}) so it doesn't collide
     * with any legacy {@code httpPostJson} that would return a
     * {@code result<string, string>} shape.
     */
    public static WitHostFunction httpPostJsonV1() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String postUrl = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "http-callbacks", "http-post-json", hostnameFromUrl(postUrl));
            enforceHttpPathCapability(ctx, "http-post-json", postUrl);
            if (ctx != null) ctx.chargeToll("http-callbacks.http-post-json");
            try {
                final String url = ((ComponentVal) args[0]).asString();
                final String body = ((ComponentVal) args[1]).asString();
                final List<ComponentVal> headers = ((ComponentVal) args[2]).asList();
                return new Object[] { httpSend("POST", url, headers, body) };
            } catch (RuntimeException e) {
                return new Object[] { ComponentVal.err(httpError("invalid-request",
                    e.getMessage() == null ? e.toString() : e.getMessage())) };
            }
        };
    }

    private static ComponentVal httpSend(
            final String method,
            final String url,
            final List<ComponentVal> headerRecords,
            final String bodyOrNull) {
        final java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        } catch (IllegalArgumentException iae) {
            return ComponentVal.err(httpError("invalid-request",
                "url did not parse: " + iae.getMessage()));
        }
        final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        final java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(30));

        boolean sawContentType = false;
        for (ComponentVal header : headerRecords) {
            final Map<String, ComponentVal> fields = header.asRecord();
            final String name = fields.get("name").asString();
            final String value = fields.get("value").asString();
            try {
                builder.header(name, value);
            } catch (IllegalArgumentException iae) {
                return ComponentVal.err(httpError("invalid-request",
                    "header rejected: " + iae.getMessage()));
            }
            if ("content-type".equalsIgnoreCase(name)) sawContentType = true;
        }
        if ("POST".equals(method)) {
            if (!sawContentType) {
                builder.header("Content-Type", "application/json");
            }
            builder.POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                bodyOrNull == null ? "" : bodyOrNull,
                java.nio.charset.StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        try {
            final java.net.http.HttpResponse<String> response = client.send(
                builder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            final int status = response.statusCode();
            if (status < 200 || status >= 300) {
                return ComponentVal.err(ComponentVal.variant("status", ComponentVal.u16(status)));
            }
            final List<ComponentVal> respHeaders = new ArrayList<>();
            response.headers().map().forEach((k, vs) -> {
                for (String v : vs) {
                    final Map<String, ComponentVal> hf = new LinkedHashMap<>();
                    hf.put("name", ComponentVal.string(k));
                    hf.put("value", ComponentVal.string(v));
                    respHeaders.add(ComponentVal.record(hf));
                }
            });
            final Map<String, ComponentVal> respFields = new LinkedHashMap<>();
            respFields.put("status", ComponentVal.u16(status));
            respFields.put("headers", ComponentVal.list(respHeaders));
            respFields.put("body", ComponentVal.string(response.body()));
            return ComponentVal.ok(ComponentVal.record(respFields));
        } catch (java.io.IOException ioe) {
            return ComponentVal.err(httpError("network",
                ioe.getMessage() == null ? ioe.toString() : ioe.getMessage()));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ComponentVal.err(httpError("network", "interrupted"));
        }
    }

    private static ComponentVal httpError(final String armName, final String message) {
        return ComponentVal.variant(armName, ComponentVal.string(message));
    }

    // ---- tegmentum:webfunction/wasm-callbacks@0.1.0 -------------------------

    /**
     * Base-substrate {@code tegmentum:webfunction/wasm-callbacks@0.1.0#invoke-wasm:
     *  func(component-uri: string, function-name: string, args: list<term>)
     *   -> result<term, wasm-call-error>}.
     *
     * <p>Loads the callee wasm component identified by
     * {@code component-uri} through the shared plugin caches
     * ({@link CalleeComponentLoader}), marshals the caller's
     * {@code list<term>} args into Stardog {@link Value}s, dispatches
     * into the callee's default {@code extension.call} export via
     * {@link StardogWasmInstance#evaluate}, and encodes the resulting
     * single-row {@code SelectQueryResult} as a single {@code term}.
     *
     * <p>The {@code function-name} argument is threaded through to
     * {@link StardogWasmInstance#evaluate(String, Value...)}: an exact
     * match against the callee's {@code extension.register()} surface
     * dispatches; a mismatch surfaces a {@code not-found} arm; an
     * empty argument against a multi-function callee surfaces
     * {@code invocation-error} because the choice cannot be inferred
     * (single-function callees still auto-discover for legacy callers
     * that leave the argument blank).
     *
     * <p>Multi-level nesting rule: rejects a call when the current
     * frame would exceed
     * {@link WebFunctionConfig#wasmCallbacksMaxNestingDepth} (default 8)
     * or when the callee URL already appears in the invocation chain
     * (cycle). See {@link #invokeWasmService} for the full error-mapping
     * table.
     */
    public static WitHostFunction invokeWasmV1() {
        return args -> invokeWasmDispatch(
                args,
                /* interfaceMethod */ "invoke-wasm",
                /* returnShape    */ CalleeReturnShape.SINGLE_TERM,
                /* invoker        */ PROD_CALLEE_INVOKER);
    }

    /**
     * Base-substrate {@code tegmentum:webfunction/wasm-callbacks@0.1.0#invoke-wasm-service:
     *  func(url: string, args: list<term>) -> result<list<binding>, wasm-call-error>}.
     *
     * <p>Property-function-shape counterpart to {@link #invokeWasmV1}. Loads
     * the callee wasm component identified by {@code url} through the
     * shared plugin caches ({@link CalleeComponentLoader}), marshals the
     * caller's {@code list<term>} args into Stardog {@link Value}s,
     * dispatches into the callee's default {@code extension.call}
     * export via {@link StardogWasmInstance#evaluate}, and encodes the
     * resulting single-row {@code SelectQueryResult} as a
     * {@code list<binding>} return.
     *
     * <p>Multi-level nesting rule: rejects a call when the current
     * frame would exceed
     * {@link WebFunctionConfig#wasmCallbacksMaxNestingDepth} (default 8)
     * OR when the callee URL already appears in the current invocation
     * chain (cycle). Both cases surface the F4 {@code nesting-not-permitted}
     * arm — distinct from {@code not-permitted} (capability denial) so
     * guests can tell a substrate structural rule apart from a policy
     * call. The reason discriminator ({@code depth-exceeded} vs
     * {@code cycle-detected}) is folded into the payload string.
     */
    public static WitHostFunction invokeWasmService() {
        return args -> invokeWasmDispatch(
                args,
                /* interfaceMethod */ "invoke-wasm-service",
                /* returnShape    */ CalleeReturnShape.LIST_OF_BINDING,
                /* invoker        */ PROD_CALLEE_INVOKER);
    }

    /**
     * Callee-return shape selector for the shared
     * {@link #invokeWasmDispatch} helper. {@code invoke-wasm} returns
     * {@code result<term, wasm-call-error>}; {@code invoke-wasm-service}
     * returns {@code result<list<binding>, wasm-call-error>}. Both wrap
     * the same underlying dispatch — the difference is only in how the
     * callee's {@link com.stardog.stark.query.SelectQueryResult} is
     * encoded on the way out.
     */
    enum CalleeReturnShape {
        /** invoke-wasm — single {@code term}. */
        SINGLE_TERM,
        /** invoke-wasm-service — {@code list<binding>}. */
        LIST_OF_BINDING
    }

    /**
     * Callee load + invoke strategy — package-private and injectable so
     * unit tests can drive the dispatch (nesting, capability, error
     * mapping, fuel-reflection) without wiring a real wasm engine.
     *
     * <p>The body-callback shape keeps the {@link StardogWasmInstance}
     * and its {@link com.stardog.stark.query.SelectQueryResult} alive
     * for the encoding pass (both are Closeable), then closes them on
     * exit. Production impl composes {@link CalleeComponentLoader#load}
     * with {@link StardogWasmInstance#evaluate}; tests supply a mock
     * that skips the wasm engine entirely and hands a synthetic
     * {@link com.stardog.stark.query.SelectQueryResult} to the body.
     */
    @FunctionalInterface
    interface CalleeInvoker {
        /**
         * Load the callee at {@code url}, dispatch the named function
         * with {@code args}, and hand the resulting
         * {@link com.stardog.stark.query.SelectQueryResult} to
         * {@code body}. The optional {@code functionName} routes the
         * dispatch by exact name; a null or empty value defers to the
         * callee's registered surface (single-function callees resolve
         * automatically; multi-function callees must be named
         * explicitly, per the base-substrate
         * {@code wasm-callbacks/invoke-wasm} semantics).
         */
        <R> R invoke(String url,
                     com.complexible.stardog.index.dictionary.MappingDictionary dict,
                     String functionName,
                     Value[] args,
                     java.util.function.Function<
                             com.stardog.stark.query.SelectQueryResult, R> body)
                throws Exception;
    }

    /**
     * Production {@link CalleeInvoker} — delegates load to
     * {@link CalleeComponentLoader}, dispatch to
     * {@link StardogWasmInstance#evaluate(String, Value...)} so the
     * caller's {@code function-name} argument routes by exact name.
     * Wrapped in try-with-resources so the instance + result close on
     * body exit even under exceptions.
     */
    static final CalleeInvoker PROD_CALLEE_INVOKER = new CalleeInvoker() {
        @Override
        public <R> R invoke(final String url,
                            final com.complexible.stardog.index.dictionary.MappingDictionary dict,
                            final String functionName,
                            final Value[] args,
                            final java.util.function.Function<
                                    com.stardog.stark.query.SelectQueryResult, R> body)
                throws Exception {
            try (StardogWasmInstance instance = CalleeComponentLoader.load(url, dict);
                 com.stardog.stark.query.SelectQueryResult rs = instance.evaluate(functionName, args)) {
                return body.apply(rs);
            }
        }
    };

    /**
     * Shared dispatch body for {@link #invokeWasmService} and
     * {@link #invokeWasmV1}. Package-private for test injection of the
     * {@link CalleeInvoker} strategy.
     *
     * <p>Flow:
     * <ol>
     *   <li>Config gate + context binding checks — no-context / no-dict
     *       branches return typed wasm-call-errors so a guest can distinguish
     *       "wasm-callbacks not usable in this frame" from "callee failed".</li>
     *   <li>Capability enforcement — declarative check + fine-grained callee
     *       allowlist. These duplicate what the host function factories run
     *       for the real dispatch path; the tests drive this method directly
     *       and rely on the checks here to fire.</li>
     *   <li>Nesting-depth + cycle guard — enforced inside
     *       {@link CallbackContext#enterWasmCall(String)}. Rejects when the
     *       depth cap ({@link WebFunctionConfig#wasmCallbacksMaxNestingDepth},
     *       default 8) is exceeded or the callee URL already appears in
     *       the invocation chain. Both cases surface the F4
     *       {@code nesting-not-permitted} arm (distinct reason
     *       discriminators folded into the payload string).</li>
     *   <li>Load callee + marshal args → invoke {@code evaluate} →
     *       encode result. Errors are mapped to the closest existing
     *       wasm-call-error arm.</li>
     *   <li>Fuel reflection (Phase-N4) — callee's fuelConsumed is debited
     *       against caller's ComponentInstance after the callee returns.</li>
     * </ol>
     */
    static Object[] invokeWasmDispatch(final Object[] args,
                                       final String method,
                                       final CalleeReturnShape returnShape,
                                       final CalleeInvoker invoker) {
        if (!WebFunctionConfig.callbackEnabled()) {
            return new Object[] { ComponentVal.err(wasmCallError("not-permitted",
                "wf callback disabled by webfunctions.callback.enabled=false")) };
        }
        final CallbackContext ctx = CallbackContext.current();
        // Decode string args first so capability enforcement + audit-summary
        // see the actual URL. Arg indices differ between the two host
        // functions — invoke-wasm has (uri, function-name, args); invoke-wasm-
        // service has (uri, args). Detect by return-shape.
        final String url = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
        final ComponentVal argsListVal;
        final String functionNameOrNull;
        if (returnShape == CalleeReturnShape.SINGLE_TERM) {
            // invoke-wasm shape: (uri, function-name, args)
            functionNameOrNull = args.length > 1 ? ((ComponentVal) args[1]).asString() : "";
            argsListVal = args.length > 2 ? (ComponentVal) args[2] : null;
        } else {
            // invoke-wasm-service shape: (uri, args)
            functionNameOrNull = null;
            argsListVal = args.length > 1 ? (ComponentVal) args[1] : null;
        }
        // Capability enforcement + toll — the WitHostFunction factories
        // wire the same three checks before delegating here, but the
        // package-private overload takes them itself so tests exercise the
        // full gate without going through the factory shim.
        try {
            enforceCapability(ctx, "wasm-callbacks", method, url);
            enforceWasmCalleeCapability(ctx, method, url);
        } catch (WfCapabilityError.PerCallDenied denied) {
            // Capability wave already promotes this to a typed SPARQL
            // error at the outer catch surface — but at the WIT boundary
            // we translate to the closest wasm-call-error variant so a
            // guest that catches the callback receives a typed result
            // rather than a wasm trap.
            return new Object[] { ComponentVal.err(wasmCallError(
                "not-permitted",
                method + ": " + denied.reason()
                    + (denied.getMessage() == null ? "" : " (" + denied.getMessage() + ")"))) };
        }
        if (ctx != null) {
            try {
                ctx.chargeToll("wasm-callbacks." + method);
            } catch (WfBudgetError.HostCallbackTollExhausted exhausted) {
                // F4 tightening: fuel exhaustion on the entry-side toll
                // charge → dedicated `fuel-exhausted` arm rather than
                // propagating out as a wasm trap (pre-F4 behavior) or
                // shoehorning into `invocation-error`. A guest catching
                // the callback now sees a typed result naming the fuel
                // budget as the cause; the wf:call-level typed
                // WfBudgetError promotion still fires the next time the
                // caller charges an exhausted budget.
                return new Object[] { ComponentVal.err(wasmCallError("fuel-exhausted",
                    method + ": host-callback toll exceeded caller's fuel budget: "
                    + (exhausted.getMessage() == null
                            ? exhausted.toString() : exhausted.getMessage()))) };
            }
        }
        if (ctx == null) {
            return new Object[] { ComponentVal.err(wasmCallError("invocation-error",
                method + ": no callback context bound — invoke-wasm needs the wf:call "
                + "frame to bind one so the callee can resolve the caller's dictionary")) };
        }
        if (ctx.dictionary() == null) {
            return new Object[] { ComponentVal.err(wasmCallError("invocation-error",
                method + ": needs the outer query's MappingDictionary on the "
                + "CallbackContext — bind with bind(dictionary) at the top of the "
                + "wf:call frame")) };
        }
        // Nesting-depth + cycle guard — multi-level extension (Task 279+).
        // Both checks live inside CallbackContext.enterWasmCall(url),
        // called from invokeWasmDispatchInner in a try/catch that maps
        // WasmNestingException to the F4 `nesting-not-permitted` arm.
        // Pre-multi-level the check was a bare `wasmCallDepth() >= 1`
        // here; now depth capping is bounded by
        // WebFunctionConfig.wasmCallbacksMaxNestingDepth (default 8) and
        // cycle detection surfaces the same arm with a distinct reason.
        return executeAsInvoker(ctx, "wasm-callbacks", method, url, () ->
            invokeWasmDispatchInner(ctx, method, url, functionNameOrNull, argsListVal, returnShape, invoker));
    }

    private static Object[] invokeWasmDispatchInner(final CallbackContext ctx,
                                                    final String method,
                                                    final String url,
                                                    final String functionNameOrNull,
                                                    final ComponentVal argsListVal,
                                                    final CalleeReturnShape returnShape,
                                                    final CalleeInvoker invoker) {
        // Marshal args from WIT term list → Value[] BEFORE loading the
        // callee so a bad arg doesn't burn the load cost. Any decode
        // failure lands as invocation-error (closest existing variant —
        // an argument-error variant would need a WIT change).
        final Value[] callArgs;
        try {
            callArgs = decodeTermV1List(argsListVal);
        } catch (RuntimeException e) {
            return new Object[] { ComponentVal.err(wasmCallError("invocation-error",
                "invoke-wasm: argument decode failed: "
                + (e.getMessage() == null ? e.toString() : e.getMessage()))) };
        }
        // Snapshot caller's fuel context before the callee stamps its own
        // ComponentInstance in stampComponentInstanceOnCurrentContext.
        // Deferred fuel reflection lives in the finally block below.
        final ai.tegmentum.webassembly4j.api.ComponentInstance callerInstance =
                ctx.componentInstanceOrNull();
        // Nesting-depth + cycle guard — CallbackContext.enterWasmCall(url)
        // validates the depth cap and cycle rules before appending the
        // callee to the chain. WasmNestingException carries a reason
        // discriminator (depth-exceeded / cycle-detected) that we fold
        // into the message so a guest catching the callback can tell
        // one structural rule from the other. Rejection is a pre-entry
        // failure — no fuel reflection or exitWasmCall pairing needed.
        try {
            ctx.enterWasmCall(url);
        } catch (CallbackContext.WasmNestingException nesting) {
            return new Object[] { ComponentVal.err(wasmCallError("nesting-not-permitted",
                method + ": " + nesting.getMessage())) };
        }
        // Outer try/catch pairs with the reflect-fuel path — the finally
        // block calls reflectCalleeFuelAndRestoreCaller, which may throw
        // WfBudgetError.HostCallbackTollExhausted when the reflected
        // callee fuel usage overruns the caller's budget. That throw
        // supersedes the try/catch below, so the F4 map to the
        // `fuel-exhausted` arm has to happen here.
        try {
        try {
            // function-name is threaded through to the callee dispatch:
            //   * non-null / non-empty → strict lookup against the
            //     callee's registered surface (NoSuchFilterFunctionException
            //     → function-not-found).
            //   * null / empty         → single-function callees still
            //     auto-discover; multi-function callees surface
            //     AmbiguousFilterFunctionException → ambiguous-function
            //     because the choice cannot be inferred.
            return invoker.invoke(url, ctx.dictionary(), functionNameOrNull, callArgs, rs -> {
                if (returnShape == CalleeReturnShape.SINGLE_TERM) {
                    return new Object[] { ComponentVal.ok(
                        encodeSingleTermFromResult(rs)) };
                } else {
                    return new Object[] { ComponentVal.ok(
                        encodeListOfBindingFromResult(rs, ctx.maxRows())) };
                }
            });
        } catch (StardogWasmInstance.NoSuchFilterFunctionException nsfe) {
            // F4 tightening: explicit function-name did not match any
            // registered filter → dedicated `function-not-found` arm
            // (was `not-found` pre-F4). `not-found` stays reserved for
            // callee URL that did not resolve to a component; the split
            // lets guests tell "no such URL" apart from "URL loaded but
            // does not export that function".
            return new Object[] { ComponentVal.err(wasmCallError("function-not-found",
                "invoke-wasm: " + nsfe.getMessage())) };
        } catch (StardogWasmInstance.AmbiguousFilterFunctionException ambig) {
            // F4 tightening: caller omitted function-name but callee
            // exports more than one filter function → dedicated
            // `ambiguous-function` arm (was `invocation-error` pre-F4).
            // Guests can now respond by re-issuing with an explicit
            // name rather than treating the failure as a callee trap.
            return new Object[] { ComponentVal.err(wasmCallError("ambiguous-function",
                "invoke-wasm: " + ambig.getMessage())) };
        } catch (java.net.MalformedURLException mue) {
            return new Object[] { ComponentVal.err(wasmCallError("not-found",
                "invoke-wasm: malformed callee url '" + url + "': "
                + (mue.getMessage() == null ? mue.toString() : mue.getMessage()))) };
        } catch (java.util.concurrent.ExecutionException ee) {
            final Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            return new Object[] { ComponentVal.err(wasmCallError("not-found",
                "invoke-wasm: failed to load callee '" + url + "': "
                + (cause.getMessage() == null ? cause.toString() : cause.getMessage()))) };
        } catch (SecurityException se) {
            return new Object[] { ComponentVal.err(wasmCallError("not-permitted",
                "invoke-wasm: load denied for '" + url + "': "
                + (se.getMessage() == null ? se.toString() : se.getMessage()))) };
        } catch (WfBudgetError.HostCallbackTollExhausted fuelExhausted) {
            // F4 tightening: reflection fuel-exhaustion (from
            // reflectCalleeFuel...) → dedicated `fuel-exhausted` arm.
            // Pre-F4 this rethrew so the outer wf:call catch surface
            // promoted to a typed WfBudgetError; F4 maps at the WIT
            // boundary so a guest catching the callback receives a
            // typed result naming fuel as the cause. finally still runs
            // to restore caller's ComponentInstance. The typed
            // WfBudgetError promotion still fires the next time the
            // caller charges an exhausted budget.
            return new Object[] { ComponentVal.err(wasmCallError("fuel-exhausted",
                "invoke-wasm: callee dispatch exhausted caller's fuel budget: "
                + (fuelExhausted.getMessage() == null
                        ? fuelExhausted.toString() : fuelExhausted.getMessage()))) };
        } catch (java.io.IOException ioe) {
            return new Object[] { ComponentVal.err(wasmCallError("invocation-error",
                "invoke-wasm: callee trap: "
                + (ioe.getMessage() == null ? ioe.toString() : ioe.getMessage()))) };
        } catch (Exception e) {
            // Shiro auth exceptions are RuntimeExceptions; discriminate
            // by class-name to avoid a compile-time shiro-core dep.
            final String cn = e.getClass().getName();
            if (cn.endsWith("AuthorizationException") || cn.endsWith("AuthenticationException")) {
                return new Object[] { ComponentVal.err(wasmCallError("not-permitted",
                    "invoke-wasm: load denied for '" + url + "': "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()))) };
            }
            return new Object[] { ComponentVal.err(wasmCallError("invocation-error",
                "invoke-wasm: callee trap: "
                + (e.getMessage() == null ? e.toString() : e.getMessage()))) };
        } finally {
            // Restore caller's ComponentInstance and reflect callee's
            // fuel consumption back into the caller's budget (Phase-N4).
            reflectCalleeFuelAndRestoreCaller(ctx, callerInstance);
            ctx.exitWasmCall(url);
        }
        } catch (WfBudgetError.HostCallbackTollExhausted fuelExhausted) {
            // F4 tightening: the reflect step (invoked from the inner
            // finally block) threw HostCallbackTollExhausted because
            // the callee's fuel usage overran the caller's budget.
            // Pre-F4 this propagated out and the outer wf:call catch
            // surface promoted it to a typed WfBudgetError; F4 maps at
            // the WIT boundary so a guest catching the callback
            // receives a typed result naming fuel as the cause. The
            // typed WfBudgetError promotion still fires the next time
            // the caller charges an exhausted budget.
            return new Object[] { ComponentVal.err(wasmCallError("fuel-exhausted",
                "invoke-wasm: callee dispatch exhausted caller's fuel budget: "
                + (fuelExhausted.getMessage() == null
                        ? fuelExhausted.toString() : fuelExhausted.getMessage()))) };
        }
    }

    /**
     * Restore the caller's {@link
     * ai.tegmentum.webassembly4j.api.ComponentInstance} on the callback
     * context (the callee overwrote it in
     * {@link StardogWasmInstance#stampComponentInstanceOnCurrentContext})
     * and reflect the callee's fuelConsumed into the caller's budget so
     * "callee fuel counts against caller" — Phase-1 fuel semantics
     * extended one level.
     *
     * <p>Best-effort: providers that don't support
     * {@code consumeFuel(long)} (endive / chicory / wamr / graalwasm)
     * throw {@link ai.tegmentum.webassembly4j.api.exception.UnsupportedFeatureException}
     * which is swallowed — the reflection is a wasmtime-only pathway
     * today; the Java-side toll counter still tracks host-callback tolls
     * for cross-provider accounting.
     */
    private static void reflectCalleeFuelAndRestoreCaller(
            final CallbackContext ctx,
            final ai.tegmentum.webassembly4j.api.ComponentInstance callerInstance) {
        long calleeFuelUsed = -1L;
        try {
            calleeFuelUsed = ctx.fuelConsumed();
        } catch (RuntimeException ignore) {
            // fuelConsumed already null-guards internally; this catch is
            // defensive against provider surprise.
        }
        // Restore caller's ComponentInstance (may be null on unit-test
        // paths that never stamped one).
        ctx.setComponentInstance(callerInstance);
        if (callerInstance == null || calleeFuelUsed <= 0L) return;
        try {
            callerInstance.consumeFuel(calleeFuelUsed);
        } catch (ai.tegmentum.webassembly4j.api.exception.UnsupportedFeatureException unsupported) {
            // Non-wasmtime provider — reflection is best-effort; the
            // Java-side toll counter still tracks host-callback tolls.
        } catch (ai.tegmentum.webassembly4j.api.exception.WebAssemblyException
                | IllegalStateException exhausted) {
            // Caller's budget went negative reflecting the callee's
            // usage — treat as fuel exhaustion in the caller frame. The
            // outer wf:call catch surface promotes this to a typed
            // WfBudgetError via chargeToll's usual promotion path, so
            // we stamp the exhaustion sentinel and throw the same
            // HostCallbackTollExhausted the toll path uses.
            throw new WfBudgetError.HostCallbackTollExhausted(
                    ctx.extensionUri(),
                    "wasm-callbacks.callee-fuel-reflect",
                    calleeFuelUsed,
                    calleeFuelUsed, /* budget: unknown at this frame */
                    calleeFuelUsed);
        }
    }

    /** Decode a WIT {@code list<term>} argument into Stardog {@link Value}s. */
    private static Value[] decodeTermV1List(final ComponentVal listVal) {
        if (listVal == null) return new Value[0];
        final List<ComponentVal> inner = listVal.asList();
        final Value[] out = new Value[inner.size()];
        for (int i = 0; i < inner.size(); i++) {
            out[i] = decodeTermV1(inner.get(i));
        }
        return out;
    }

    /**
     * Decode a base-substrate {@code tegmentum:webfunction/types.term}
     * variant (4 arms — named-node / blank-node / literal / triple)
     * into a Stardog {@link Value}. Mirrors {@link #encodeTermV1}.
     * Rejects the {@code triple} arm the same way
     * {@link WitValueMarshaller#valueFromWit} does — Stardog's planner
     * surface has no notion of quoted triples.
     */
    private static Value decodeTermV1(final ComponentVal variant) {
        final ComponentVariant cv = variant.asVariant();
        final String caseName = cv.getCaseName();
        final ComponentVal payload = cv.getPayload().orElse(null);
        switch (caseName) {
            case "named-node":
                return Values.iri(payload == null ? "" : payload.asString());
            case "blank-node":
                return Values.bnode(payload == null ? "" : payload.asString());
            case "literal": {
                if (payload == null) {
                    throw new IllegalStateException("wf: literal variant has no payload");
                }
                final Map<String, ComponentVal> fields = payload.asRecord();
                final String value = fields.get("value").asString();
                final Optional<ComponentVal> dtOpt = fields.get("datatype").asSome();
                final Optional<ComponentVal> langOpt = fields.get("language").asSome();
                if (langOpt.isPresent()) {
                    return Values.literal(value, langOpt.get().asString());
                }
                if (dtOpt.isPresent()) {
                    return Values.literal(value, Values.iri(dtOpt.get().asString()));
                }
                // RDF 1.1: absent datatype + absent language ≡ xsd:string.
                return Values.literal(value);
            }
            case "triple":
                throw new IllegalArgumentException(
                    "wasm-callbacks: term variant 'triple' (RDF-star quoted triple) "
                    + "is not supported at the Stardog boundary");
            default:
                throw new IllegalArgumentException(
                    "wasm-callbacks: unknown term case: " + caseName);
        }
    }

    /**
     * Encode the callee's single-row {@link
     * com.stardog.stark.query.SelectQueryResult} (from
     * {@link StardogWasmInstance#evaluate}) as a single {@code term}.
     * The callee's evaluate wraps its scalar return as a 1-row 1-binding
     * SelectQueryResult with the well-known {@code value_0} name (see
     * {@link WitValueMarshaller#singleTermToSelectQueryResult}); we
     * unwrap that here and re-encode as a v1 term.
     */
    private static ComponentVal encodeSingleTermFromResult(
            final com.stardog.stark.query.SelectQueryResult rs) {
        if (!rs.hasNext()) {
            // Callee produced no rows — no term to return. Encode an
            // empty-string named-node as a placeholder; a future WIT
            // revision could add an "empty" arm.
            return ComponentVal.variant("named-node", ComponentVal.string(""));
        }
        final BindingSet bs = rs.next();
        final Value v = bs.get("value_0");
        if (v == null) {
            return ComponentVal.variant("named-node", ComponentVal.string(""));
        }
        return encodeTermV1(v);
    }

    /**
     * Encode the callee's {@link com.stardog.stark.query.SelectQueryResult}
     * as a WIT {@code list<binding>}. Flatten all rows into a single
     * list keyed by variable name — same shape
     * {@link #graphExecuteQuery} produces for the bindings arm of its
     * query-result variant.
     */
    private static ComponentVal encodeListOfBindingFromResult(
            final com.stardog.stark.query.SelectQueryResult rs,
            final int rowCap) {
        final List<String> vars = rs.variables();
        final List<ComponentVal> bindings = new ArrayList<>();
        int rowsSeen = 0;
        while (rs.hasNext() && rowsSeen < rowCap) {
            final BindingSet bs = rs.next();
            for (String var : vars) {
                final Value v = bs.get(var);
                if (v == null) continue;
                final Map<String, ComponentVal> bindingFields = new LinkedHashMap<>();
                bindingFields.put("variable", ComponentVal.string(var));
                bindingFields.put("value", encodeTermV1(v));
                bindings.add(ComponentVal.record(bindingFields));
            }
            rowsSeen++;
        }
        return ComponentVal.list(bindings);
    }

    private static ComponentVal wasmCallError(final String armName, final String message) {
        return ComponentVal.variant(armName, ComponentVal.string(message));
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

    // ---- tegmentum:webfunction/sink-callbacks@0.1.0 -------------------------
    //
    // Sink interfaces (sink-callbacks / sink-query-callbacks /
    // document-sink-callbacks) are Tegmentum-substrate constructs for
    // polyglot demotion (typed RDF quads demoted into SQLite / DuckDB /
    // SirixDB / vector-index rows). Wave A wires an in-memory
    // {@link SinkRegistry} — sinks are registered at plugin startup
    // from the {@code webfunctions.sink.names} config key and every
    // handler routes through the registry. Unknown sink name surfaces
    // as the interface's {@code no-such-sink} arm; a registered sink
    // accepts quads / documents into an in-memory {@link SinkEntry}.
    //
    // Capability enforcement (perCallback) fires FIRST, before the
    // handler dispatches — so a policy that denies the interface
    // surfaces {@link WfCapabilityError.PerCallDenied} rather than any
    // WIT-boundary error variant. Both paths result in a typed denial
    // the guest can distinguish.
    //
    // Handlers do NOT wrap in {@link #executeAsInvoker} because sinks
    // touch no Stardog state — the Phase 4 Shiro invoker-subject wrap
    // is meaningful only for graph-callbacks that read/write Stardog's
    // real store. A production sink backend that DID hit Stardog (a
    // future "Stardog-graph sink adapter") would add the wrap at that
    // point.
    //
    // Tracker-sink-callbacks + fulltext-callbacks remain as stubs
    // (Waves B / C).

    /** {@code list-sinks: func() -> list<sink-descriptor>}. Returns one
     *  descriptor per name registered in the {@link SinkRegistry}. The
     *  {@code graph-pattern} field is intentionally empty at Wave A —
     *  the reference in-memory sink accepts any quad without shape
     *  validation, matching the memo §4 note that {@code graph-pattern}
     *  stays a human-readable string until the shape ADT lands. */
    public static WitHostFunction sinkListSinks() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            enforceCapability(ctx, "sink-callbacks", "list-sinks", "");
            if (ctx != null) ctx.chargeToll("sink-callbacks.list-sinks");
            final List<String> names = SinkRegistry.INSTANCE.sinkNames();
            final List<ComponentVal> descriptors = new ArrayList<>(names.size());
            for (final String name : names) {
                final Map<String, ComponentVal> fields = new LinkedHashMap<>();
                fields.put("name", ComponentVal.string(name));
                fields.put("graph-pattern", ComponentVal.string(""));
                descriptors.add(ComponentVal.record(fields));
            }
            return new Object[] { ComponentVal.list(descriptors) };
        };
    }

    /** {@code emit-quad: func(sink-name: string, q: quad) -> result<_, sink-error>}. */
    public static WitHostFunction sinkEmitQuad() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String sinkName = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "sink-callbacks", "emit-quad", sinkName);
            if (ctx != null) ctx.chargeToll("sink-callbacks.emit-quad");
            final Optional<SinkEntry> entryOpt = SinkRegistry.INSTANCE.sink(sinkName);
            if (entryOpt.isEmpty()) {
                return new Object[] { ComponentVal.err(sinkError("no-such-sink",
                    "sink-callbacks: no sink registered under name '" + sinkName + "' — "
                    + "declare it in " + WebFunctionConfig.PROP_SINK_NAMES + " at boot.")) };
            }
            if (args.length < 2 || args[1] == null) {
                return new Object[] { ComponentVal.err(sinkError("backend-error",
                    "sink-callbacks: emit-quad missing quad argument for sink '"
                    + sinkName + "'.")) };
            }
            entryOpt.get().addQuad((ComponentVal) args[1]);
            return new Object[] { ComponentVal.ok() };
        };
    }

    /** {@code emit-quads: func(sink-name: string, quads: list<quad>) -> result<u32, sink-error>}. */
    public static WitHostFunction sinkEmitQuads() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String sinkName = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "sink-callbacks", "emit-quads", sinkName);
            if (ctx != null) ctx.chargeToll("sink-callbacks.emit-quads");
            final Optional<SinkEntry> entryOpt = SinkRegistry.INSTANCE.sink(sinkName);
            if (entryOpt.isEmpty()) {
                return new Object[] { ComponentVal.err(sinkError("no-such-sink",
                    "sink-callbacks: no sink registered under name '" + sinkName + "' — "
                    + "declare it in " + WebFunctionConfig.PROP_SINK_NAMES + " at boot.")) };
            }
            final List<ComponentVal> batch = args.length > 1 && args[1] != null
                    ? ((ComponentVal) args[1]).asList()
                    : java.util.Collections.emptyList();
            final int added = entryOpt.get().addQuads(batch);
            return new Object[] { ComponentVal.ok(ComponentVal.u32((long) added)) };
        };
    }

    private static ComponentVal sinkError(final String armName, final String message) {
        return ComponentVal.variant(armName, ComponentVal.string(message));
    }

    // ---- tegmentum:webfunction/sink-query-callbacks@0.1.0 ------------------

    /**
     * {@code execute-sink-select: func(sink-name: string, sparql: string)
     *  -> result<list<binding>, sink-query-error>}.
     *
     * <p>Wave D — real SPARQL SELECT evaluator over the sink's
     * accumulated quads. Routes through {@link SinkSparqlEngine} which
     * spins a fresh {@link org.eclipse.rdf4j.sail.memory.MemoryStore}
     * per invocation, loads the sink's WIT quads via
     * {@link SinkStatementMarshaller#loadInto}, evaluates against the
     * store, marshals solutions into WIT {@code binding} records via
     * {@link SinkBindingMarshaller#toWitBindings}, and tears the store
     * back down. See the engine class doc for the full lifecycle.
     *
     * <p>Error mapping (WIT {@code sink-query-error} variant):
     * <ul>
     *   <li>Unknown sink -&gt; {@code no-such-sink} (before eval).</li>
     *   <li>{@link SinkSparqlEngine.SyntaxError} (parse failure or
     *       non-SELECT shape) -&gt; {@code syntax-error}.</li>
     *   <li>{@link SinkSparqlEngine.BackendError} (Sail startup, sail
     *       eval, malformed quad in deque) -&gt; {@code backend-error}.</li>
     *   <li>{@link WfCapabilityError.PerCallDenied} propagates out of
     *       the handler (throw, not variant) — the master gate turned
     *       the check ON and denied; matches the write-path convention.</li>
     * </ul>
     *
     * <p>Capability enforcement fires BEFORE the engine call — a
     * denial short-circuits without spinning up a MemoryStore.
     */
    public static WitHostFunction sinkQueryExecuteSelect() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String sinkName = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "sink-query-callbacks", "execute-sink-select", sinkName);
            if (ctx != null) ctx.chargeToll("sink-query-callbacks.execute-sink-select");
            final Optional<SinkEntry> entryOpt = SinkRegistry.INSTANCE.sink(sinkName);
            if (entryOpt.isEmpty()) {
                return new Object[] { ComponentVal.err(sinkQueryError("no-such-sink",
                    "sink-query-callbacks: no sink registered under name '" + sinkName + "'.")) };
            }
            final String sparql = args.length > 1 && args[1] != null
                    ? ((ComponentVal) args[1]).asString()
                    : "";
            try {
                final List<ComponentVal> bindings =
                        SinkSparqlEngine.INSTANCE.evaluate(entryOpt.get(), sparql);
                return new Object[] { ComponentVal.ok(ComponentVal.list(bindings)) };
            } catch (SinkSparqlEngine.SyntaxError e) {
                return new Object[] { ComponentVal.err(sinkQueryError("syntax-error",
                    "sink-query-callbacks: SPARQL parse failed for sink '"
                    + sinkName + "': " + e.getMessage())) };
            } catch (SinkSparqlEngine.BackendError e) {
                return new Object[] { ComponentVal.err(sinkQueryError("backend-error",
                    "sink-query-callbacks: evaluation failed for sink '"
                    + sinkName + "': " + e.getMessage())) };
            }
        };
    }

    /**
     * {@code scan-sink-quads: func(sink-name: string, option<term>, option<term>, option<term>)
     *  -> result<list<quad>, sink-query-error>}.
     *
     * <p>Linear filter over the sink's accumulated quads. Each of the
     * three position args is optional — {@code none} matches anything;
     * {@code some(term)} requires exact term equality (see
     * {@link #termsEqualComponent}). The graph position is not filterable
     * at MVP; the memo defers named-graph scans until a production
     * consumer needs one.
     */
    public static WitHostFunction sinkQueryScanQuads() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String sinkName = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "sink-query-callbacks", "scan-sink-quads", sinkName);
            if (ctx != null) ctx.chargeToll("sink-query-callbacks.scan-sink-quads");
            final Optional<SinkEntry> entryOpt = SinkRegistry.INSTANCE.sink(sinkName);
            if (entryOpt.isEmpty()) {
                return new Object[] { ComponentVal.err(sinkQueryError("no-such-sink",
                    "sink-query-callbacks: no sink registered under name '" + sinkName + "'.")) };
            }
            final ComponentVal subjectFilter = args.length > 1
                    ? ((ComponentVal) args[1]).asSome().orElse(null) : null;
            final ComponentVal predicateFilter = args.length > 2
                    ? ((ComponentVal) args[2]).asSome().orElse(null) : null;
            final ComponentVal objectFilter = args.length > 3
                    ? ((ComponentVal) args[3]).asSome().orElse(null) : null;

            final List<ComponentVal> matches = new ArrayList<>();
            for (final java.util.Iterator<ComponentVal> it = entryOpt.get().iterateQuads();
                 it.hasNext(); ) {
                final ComponentVal quad = it.next();
                final Map<String, ComponentVal> fields = quad.asRecord();
                if (subjectFilter != null
                        && !termsEqualComponent(subjectFilter, fields.get("subject"))) continue;
                if (predicateFilter != null
                        && !termsEqualComponent(predicateFilter, fields.get("predicate"))) continue;
                if (objectFilter != null
                        && !termsEqualComponent(objectFilter, fields.get("object"))) continue;
                matches.add(quad);
            }
            return new Object[] { ComponentVal.ok(ComponentVal.list(matches)) };
        };
    }

    private static ComponentVal sinkQueryError(final String armName, final String message) {
        return ComponentVal.variant(armName, ComponentVal.string(message));
    }

    /**
     * Structural equality for two WIT {@code term} variants ({@code
     * named-node} / {@code blank-node} / {@code literal} / {@code triple}).
     * Deliberate rather than relying on {@link Object#equals} on
     * {@link ComponentVal} — the ComponentVal contract does not guarantee
     * structural equals, and mirroring the Oxigraph reference's
     * {@code terms_equal} keeps the sink-scan semantics identical
     * across engines.
     *
     * <p>The {@code triple} arm (RDF-star quoted triple) always returns
     * false — sink-scan BGP filtering over quoted triples is out of
     * MVP scope; the Oxigraph reference impl makes the same call.
     */
    private static boolean termsEqualComponent(final ComponentVal a, final ComponentVal b) {
        if (a == null || b == null) return false;
        final ComponentVariant av = a.asVariant();
        final ComponentVariant bv = b.asVariant();
        if (!av.getCaseName().equals(bv.getCaseName())) return false;
        switch (av.getCaseName()) {
            case "named-node":
            case "blank-node":
                return av.getPayload().orElseThrow().asString()
                        .equals(bv.getPayload().orElseThrow().asString());
            case "literal": {
                final Map<String, ComponentVal> af = av.getPayload().orElseThrow().asRecord();
                final Map<String, ComponentVal> bf = bv.getPayload().orElseThrow().asRecord();
                if (!af.get("value").asString().equals(bf.get("value").asString())) return false;
                final Optional<ComponentVal> aDt = af.get("datatype").asSome();
                final Optional<ComponentVal> bDt = bf.get("datatype").asSome();
                if (aDt.isPresent() != bDt.isPresent()) return false;
                if (aDt.isPresent() && !aDt.get().asString().equals(bDt.get().asString())) return false;
                final Optional<ComponentVal> aLg = af.get("language").asSome();
                final Optional<ComponentVal> bLg = bf.get("language").asSome();
                if (aLg.isPresent() != bLg.isPresent()) return false;
                if (aLg.isPresent() && !aLg.get().asString().equals(bLg.get().asString())) return false;
                return true;
            }
            case "triple":
                // RDF-star quoted triples in BGP position are out of MVP
                // scope. Matches the Oxigraph reference impl's
                // `terms_equal`, which treats any two Triple terms as
                // unequal to keep the filter loop total.
                return false;
            default:
                return false;
        }
    }

    // ---- tegmentum:webfunction/document-sink-callbacks@0.1.0 ---------------

    /**
     * {@code put-document: func(sink-name: string, doc: document)
     *  -> result<_, document-sink-error>}.
     *
     * <p>Upsert semantics — an existing document under the same rendered
     * key is replaced (matches the WIT contract's "insert-or-update
     * uniform surface" note in the interface doc). Key rendering is
     * canonical (see {@link #renderTermKey}).
     */
    public static WitHostFunction documentSinkPutDocument() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String sinkName = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "document-sink-callbacks", "put-document", sinkName);
            if (ctx != null) ctx.chargeToll("document-sink-callbacks.put-document");
            final Optional<SinkEntry> entryOpt = SinkRegistry.INSTANCE.sink(sinkName);
            if (entryOpt.isEmpty()) {
                return new Object[] { ComponentVal.err(documentSinkError("no-such-sink",
                    "document-sink-callbacks: no sink registered under name '"
                    + sinkName + "'.")) };
            }
            if (args.length < 2 || args[1] == null) {
                return new Object[] { ComponentVal.err(documentSinkError("backend-error",
                    "document-sink-callbacks: put-document missing document argument for sink '"
                    + sinkName + "'.")) };
            }
            final Map<String, ComponentVal> docFields = ((ComponentVal) args[1]).asRecord();
            final ComponentVal keyTerm = docFields.get("key");
            final String content = docFields.get("content").asString();
            entryOpt.get().putDocument(renderTermKey(keyTerm), content);
            return new Object[] { ComponentVal.ok() };
        };
    }

    /**
     * {@code get-document: func(sink-name: string, key: term)
     *  -> result<document, document-sink-error>}.
     *
     * <p>Missing key surfaces as {@code no-such-document} rather than
     * an empty-string content — a document may legitimately contain
     * the empty string. Returns the full {@code document} record with
     * the original key echoed back.
     */
    public static WitHostFunction documentSinkGetDocument() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String sinkName = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "document-sink-callbacks", "get-document", sinkName);
            if (ctx != null) ctx.chargeToll("document-sink-callbacks.get-document");
            final Optional<SinkEntry> entryOpt = SinkRegistry.INSTANCE.sink(sinkName);
            if (entryOpt.isEmpty()) {
                return new Object[] { ComponentVal.err(documentSinkError("no-such-sink",
                    "document-sink-callbacks: no sink registered under name '"
                    + sinkName + "'.")) };
            }
            if (args.length < 2 || args[1] == null) {
                return new Object[] { ComponentVal.err(documentSinkError("backend-error",
                    "document-sink-callbacks: get-document missing key argument for sink '"
                    + sinkName + "'.")) };
            }
            final ComponentVal keyTerm = (ComponentVal) args[1];
            final String rendered = renderTermKey(keyTerm);
            final String content = entryOpt.get().getDocument(rendered);
            if (content == null) {
                return new Object[] { ComponentVal.err(documentSinkError("no-such-document",
                    "document-sink-callbacks: no document under key '" + rendered
                    + "' in sink '" + sinkName + "'.")) };
            }
            final Map<String, ComponentVal> docFields = new LinkedHashMap<>();
            docFields.put("key", keyTerm);
            docFields.put("content", ComponentVal.string(content));
            return new Object[] { ComponentVal.ok(ComponentVal.record(docFields)) };
        };
    }

    /**
     * {@code delete-document: func(sink-name: string, key: term)
     *  -> result<_, document-sink-error>}.
     *
     * <p>Idempotent — missing key is NOT an error, matching the WIT
     * doc's "wasi-blobstore delete-object convention" note. Returns
     * {@code ok} whether or not a document was actually removed.
     * Unknown sink still returns {@code no-such-sink} (that is a
     * misconfig, not an idempotent-delete miss).
     */
    public static WitHostFunction documentSinkDeleteDocument() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String sinkName = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "document-sink-callbacks", "delete-document", sinkName);
            if (ctx != null) ctx.chargeToll("document-sink-callbacks.delete-document");
            final Optional<SinkEntry> entryOpt = SinkRegistry.INSTANCE.sink(sinkName);
            if (entryOpt.isEmpty()) {
                return new Object[] { ComponentVal.err(documentSinkError("no-such-sink",
                    "document-sink-callbacks: no sink registered under name '"
                    + sinkName + "'.")) };
            }
            if (args.length < 2 || args[1] == null) {
                return new Object[] { ComponentVal.err(documentSinkError("backend-error",
                    "document-sink-callbacks: delete-document missing key argument for sink '"
                    + sinkName + "'.")) };
            }
            entryOpt.get().removeDocument(renderTermKey((ComponentVal) args[1]));
            return new Object[] { ComponentVal.ok() };
        };
    }

    private static ComponentVal documentSinkError(final String armName, final String message) {
        return ComponentVal.variant(armName, ComponentVal.string(message));
    }

    /**
     * Render an RDF term to the canonical string key form used by the
     * in-memory document store. Mirrors the Oxigraph reference impl's
     * {@code render_term_key}:
     *
     * <ul>
     *   <li>{@code named-node} → raw IRI string (no {@code <...>}
     *       wrapping — the sink key space is not RDF text).</li>
     *   <li>{@code blank-node} → {@code _:} prefix + id.</li>
     *   <li>{@code literal} → value only. Datatype and language are
     *       intentionally dropped from the key: literals as document
     *       keys are a rare "hash-content-as-key" pattern where the
     *       lexical form IS the identity.</li>
     *   <li>{@code triple} (RDF-star) → a fixed sentinel. Quoted-triple
     *       document keys are out of MVP scope; matches Oxigraph.</li>
     * </ul>
     */
    private static String renderTermKey(final ComponentVal term) {
        final ComponentVariant variant = term.asVariant();
        final String caseName = variant.getCaseName();
        switch (caseName) {
            case "named-node":
                return variant.getPayload().orElseThrow().asString();
            case "blank-node":
                return "_:" + variant.getPayload().orElseThrow().asString();
            case "literal": {
                final Map<String, ComponentVal> fields =
                        variant.getPayload().orElseThrow().asRecord();
                return fields.get("value").asString();
            }
            case "triple":
                return "<<rdf-star key placeholder>>";
            default:
                return "<<unknown term case: " + caseName + ">>";
        }
    }

    // ---- tegmentum:webfunction/tracker-sink-callbacks@0.1.0 ----------------
    //
    // Wave B — real implementations backed by the singleton
    // {@link SqliteTrackerBackend}. Mirrors Oxigraph's
    // SqliteTrackerSinkImpl reference shape (see
    // ~/git/oxigraph-webfunction-plugin/crates/host-callbacks-impl/src/tracker_sink.rs).
    //
    // Sink names are declared at plugin startup via
    // webfunctions.tracker.sqlite.sinks; the WIT contract has no
    // startup-time register-sink method by design. An unknown sink
    // surfaces the interface's no-such-sink arm. Tables inside a sink
    // are declared at guest runtime via register-tracker-tables.
    //
    // Capability enforcement (perCallback) fires FIRST, before the
    // handler dispatches — same discipline as Wave A / Wave C.
    // executeAsInvoker is deliberately NOT wrapped because tracker-sink
    // touches no Stardog state (SQLite file is host-owned scratch
    // storage). Fuel toll fires after the capability gate, before the
    // SqliteTrackerBackend call.

    /** {@code register-tracker-tables: func(sink-name: string,
     *  tables: list<tracker-table-schema>) -> result<_, tracker-error>}. */
    public static WitHostFunction trackerRegisterTables() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String sinkName = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "tracker-sink-callbacks", "register-tracker-tables", sinkName);
            if (ctx != null) ctx.chargeToll("tracker-sink-callbacks.register-tracker-tables");
            try {
                final List<ComponentVal> tableList = args.length > 1 && args[1] != null
                        ? ((ComponentVal) args[1]).asList()
                        : java.util.Collections.emptyList();
                final List<TrackerSchema> schemas = new ArrayList<>(tableList.size());
                for (final ComponentVal tableVal : tableList) {
                    schemas.add(decodeTableSchema(tableVal));
                }
                SqliteTrackerBackend.INSTANCE.registerTables(sinkName, schemas);
                return new Object[] { ComponentVal.ok() };
            } catch (SqliteTrackerBackend.TrackerError te) {
                return new Object[] { ComponentVal.err(trackerError(te.armName(), te.getMessage())) };
            }
        };
    }

    /** {@code tracker-insert: func(sink-name: string, table-name: string,
     *  row: tracker-row) -> result<_, tracker-error>}. */
    public static WitHostFunction trackerInsert() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String sinkName = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "tracker-sink-callbacks", "tracker-insert", sinkName);
            if (ctx != null) ctx.chargeToll("tracker-sink-callbacks.tracker-insert");
            try {
                final String tableName = args.length > 1 ? ((ComponentVal) args[1]).asString() : "";
                final List<ComponentVal> row = decodeRowValues((ComponentVal) args[2]);
                SqliteTrackerBackend.INSTANCE.insertRow(sinkName, tableName, row);
                return new Object[] { ComponentVal.ok() };
            } catch (SqliteTrackerBackend.TrackerError te) {
                return new Object[] { ComponentVal.err(trackerError(te.armName(), te.getMessage())) };
            }
        };
    }

    /** {@code tracker-upsert: func(sink-name: string, table-name: string,
     *  row: tracker-row) -> result<_, tracker-error>}. */
    public static WitHostFunction trackerUpsert() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String sinkName = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "tracker-sink-callbacks", "tracker-upsert", sinkName);
            if (ctx != null) ctx.chargeToll("tracker-sink-callbacks.tracker-upsert");
            try {
                final String tableName = args.length > 1 ? ((ComponentVal) args[1]).asString() : "";
                final List<ComponentVal> row = decodeRowValues((ComponentVal) args[2]);
                SqliteTrackerBackend.INSTANCE.upsertRow(sinkName, tableName, row);
                return new Object[] { ComponentVal.ok() };
            } catch (SqliteTrackerBackend.TrackerError te) {
                return new Object[] { ComponentVal.err(trackerError(te.armName(), te.getMessage())) };
            }
        };
    }

    /** {@code tracker-select: func(sink-name: string, table-name: string,
     *  where-clauses: list<tracker-where>, columns: list<string>)
     *  -> result<list<tracker-row>, tracker-error>}. */
    public static WitHostFunction trackerSelect() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String sinkName = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "tracker-sink-callbacks", "tracker-select", sinkName);
            if (ctx != null) ctx.chargeToll("tracker-sink-callbacks.tracker-select");
            try {
                final String tableName = args.length > 1 ? ((ComponentVal) args[1]).asString() : "";
                final List<TrackerWhere.Clause> where = decodeWhereList(
                        args.length > 2 ? (ComponentVal) args[2] : null);
                final List<String> projection = decodeStringList(
                        args.length > 3 ? (ComponentVal) args[3] : null);
                final List<List<ComponentVal>> rows =
                        SqliteTrackerBackend.INSTANCE.selectRows(
                                sinkName, tableName, where, projection);
                final List<ComponentVal> encoded = new ArrayList<>(rows.size());
                for (final List<ComponentVal> row : rows) {
                    final Map<String, ComponentVal> rec = new LinkedHashMap<>();
                    rec.put("values", ComponentVal.list(row));
                    encoded.add(ComponentVal.record(rec));
                }
                return new Object[] { ComponentVal.ok(ComponentVal.list(encoded)) };
            } catch (SqliteTrackerBackend.TrackerError te) {
                return new Object[] { ComponentVal.err(trackerError(te.armName(), te.getMessage())) };
            }
        };
    }

    /** {@code tracker-delete: func(sink-name: string, table-name: string,
     *  where-clauses: list<tracker-where>) -> result<u32, tracker-error>}. */
    public static WitHostFunction trackerDelete() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String sinkName = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "tracker-sink-callbacks", "tracker-delete", sinkName);
            if (ctx != null) ctx.chargeToll("tracker-sink-callbacks.tracker-delete");
            try {
                final String tableName = args.length > 1 ? ((ComponentVal) args[1]).asString() : "";
                final List<TrackerWhere.Clause> where = decodeWhereList(
                        args.length > 2 ? (ComponentVal) args[2] : null);
                final long removed =
                        SqliteTrackerBackend.INSTANCE.deleteRows(sinkName, tableName, where);
                // WIT returns u32 — clamp defensively.
                final long clamped = Math.min(Math.max(0L, removed), 0xFFFFFFFFL);
                return new Object[] { ComponentVal.ok(ComponentVal.u32(clamped)) };
            } catch (SqliteTrackerBackend.TrackerError te) {
                return new Object[] { ComponentVal.err(trackerError(te.armName(), te.getMessage())) };
            }
        };
    }

    /** {@code tracker-count: func(sink-name: string, table-name: string,
     *  where-clauses: list<tracker-where>) -> result<u64, tracker-error>}. */
    public static WitHostFunction trackerCount() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String sinkName = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "tracker-sink-callbacks", "tracker-count", sinkName);
            if (ctx != null) ctx.chargeToll("tracker-sink-callbacks.tracker-count");
            try {
                final String tableName = args.length > 1 ? ((ComponentVal) args[1]).asString() : "";
                final List<TrackerWhere.Clause> where = decodeWhereList(
                        args.length > 2 ? (ComponentVal) args[2] : null);
                final long n =
                        SqliteTrackerBackend.INSTANCE.countRows(sinkName, tableName, where);
                return new Object[] { ComponentVal.ok(ComponentVal.u64(Math.max(0L, n))) };
            } catch (SqliteTrackerBackend.TrackerError te) {
                return new Object[] { ComponentVal.err(trackerError(te.armName(), te.getMessage())) };
            }
        };
    }

    private static ComponentVal trackerError(final String armName, final String message) {
        return ComponentVal.variant(armName, ComponentVal.string(message));
    }

    // ---- tracker-sink decoders --------------------------------------

    /** Decode a WIT {@code tracker-table-schema} record into
     *  {@link TrackerSchema}. */
    private static TrackerSchema decodeTableSchema(final ComponentVal tableVal) {
        final Map<String, ComponentVal> fields = tableVal.asRecord();
        final String name = fields.get("name").asString();
        final List<TrackerSchema.ColumnDef> columns = new ArrayList<>();
        for (final ComponentVal colVal : fields.get("columns").asList()) {
            final Map<String, ComponentVal> colFields = colVal.asRecord();
            columns.add(new TrackerSchema.ColumnDef(
                    colFields.get("name").asString(),
                    decodeColumnType(colFields.get("column-type")),
                    colFields.get("primary-key").asBool(),
                    colFields.get("nullable").asBool()));
        }
        final List<TrackerSchema.IndexDef> indexes = new ArrayList<>();
        // The WIT contract has indexes on tracker-table-schema; guests
        // that omit them (Rust wit-bindgen still emits an empty list)
        // land here as an empty list.
        final ComponentVal idxListVal = fields.get("indexes");
        if (idxListVal != null) {
            for (final ComponentVal idxVal : idxListVal.asList()) {
                final Map<String, ComponentVal> idxFields = idxVal.asRecord();
                final List<String> idxCols = new ArrayList<>();
                for (final ComponentVal col : idxFields.get("columns").asList()) {
                    idxCols.add(col.asString());
                }
                indexes.add(new TrackerSchema.IndexDef(
                        idxFields.get("name").asString(),
                        idxCols,
                        idxFields.get("unique").asBool()));
            }
        }
        return new TrackerSchema(name, columns, indexes);
    }

    /** Decode a WIT {@code column-type} enum-like variant to the Java
     *  enum. */
    private static TrackerSchema.ColumnType decodeColumnType(final ComponentVal v) {
        // wit-bindgen emits enum-shaped variants as either
        // ComponentVal.variant(caseName) (no payload) OR
        // ComponentVal.enum_(caseName) depending on the exact WIT
        // shape. Both surface a String case name — read via asVariant()
        // first, fall back to asEnum() if not a variant.
        String caseName;
        try {
            caseName = v.asVariant().getCaseName();
        } catch (RuntimeException notVariant) {
            caseName = v.asEnum();
        }
        switch (caseName) {
            case "text":    return TrackerSchema.ColumnType.TEXT;
            case "integer": return TrackerSchema.ColumnType.INTEGER;
            case "blob":    return TrackerSchema.ColumnType.BLOB;
            case "real":    return TrackerSchema.ColumnType.REAL;
            default:
                throw new SqliteTrackerBackend.TrackerError.SchemaViolation(
                        "unknown column-type arm: '" + caseName + "'");
        }
    }

    /** Decode a WIT {@code tracker-row} record into the ordered value
     *  list the backend consumes. */
    private static List<ComponentVal> decodeRowValues(final ComponentVal rowVal) {
        if (rowVal == null) return java.util.Collections.emptyList();
        // A tracker-row is a record with a single `values` field of
        // list<tracker-value>; guests that ship the list bare (skipping
        // the record wrapper) also work as a defensive fallback.
        try {
            final Map<String, ComponentVal> fields = rowVal.asRecord();
            final ComponentVal valuesVal = fields.get("values");
            if (valuesVal != null) return valuesVal.asList();
        } catch (RuntimeException notRecord) {
            // fall through to list-shaped fallback
        }
        return rowVal.asList();
    }

    /** Decode a WIT {@code list<tracker-where>} into the
     *  {@link TrackerWhere.Clause} list the backend composes. */
    private static List<TrackerWhere.Clause> decodeWhereList(final ComponentVal listVal) {
        if (listVal == null) return java.util.Collections.emptyList();
        final List<ComponentVal> items = listVal.asList();
        final List<TrackerWhere.Clause> out = new ArrayList<>(items.size());
        for (final ComponentVal c : items) {
            out.add(TrackerWhere.Clause.fromWit(c));
        }
        return out;
    }

    /** Decode a WIT {@code list<string>} into a Java list. Nulls and
     *  empty lists are safe. */
    private static List<String> decodeStringList(final ComponentVal listVal) {
        if (listVal == null) return java.util.Collections.emptyList();
        final List<ComponentVal> items = listVal.asList();
        final List<String> out = new ArrayList<>(items.size());
        for (final ComponentVal s : items) out.add(s.asString());
        return out;
    }

    // ---- tegmentum:webfunction/fulltext-callbacks@0.1.0 --------------------
    //
    // Wave C — real implementations backed by the in-memory
    // {@link InMemoryFulltextRegistry}. Mirrors Oxigraph's
    // InMemoryFulltextImpl reference shape (see
    // ~/git/oxigraph-webfunction-plugin/crates/host-callbacks-impl/src/lib.rs).
    //
    // Index names are declared at plugin startup via
    // webfunctions.fulltext.indexes; the WIT contract has no runtime
    // register-index method by design. An unknown index surfaces the
    // interface's no-such-index arm.
    //
    // BITES bridge is deferred — Stardog does ship a BITES full-text
    // adapter, but its admin surface is not reachable from a
    // web-function-plugin thread today. Production impls would layer
    // BITES / Manticore / OpenSearch behind the same three lambdas with
    // no guest-side change.

    /** {@code insert-documents: func(index: string, docs: list<fulltext-document>)
     *  -> result<u32, fulltext-error>}. Batch insert-with-replace into
     *  the named index; returns the count accepted. */
    public static WitHostFunction fulltextInsertDocuments() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String indexName = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "fulltext-callbacks", "insert-documents", indexName);
            if (ctx != null) ctx.chargeToll("fulltext-callbacks.insert-documents");
            final Optional<FulltextIndex> indexOpt =
                    InMemoryFulltextRegistry.INSTANCE.index(indexName);
            if (indexOpt.isEmpty()) {
                return new Object[] { ComponentVal.err(fulltextError("no-such-index",
                    "fulltext-callbacks: no index registered under name '" + indexName
                    + "' — declare it in " + WebFunctionConfig.PROP_FULLTEXT_INDEXES
                    + " at boot.")) };
            }
            final List<ComponentVal> batch = args.length > 1 && args[1] != null
                    ? ((ComponentVal) args[1]).asList()
                    : java.util.Collections.emptyList();
            final FulltextIndex index = indexOpt.get();
            int accepted = 0;
            for (final ComponentVal docVal : batch) {
                final Map<String, ComponentVal> fields = docVal.asRecord();
                final String id = fields.get("id").asString();
                final List<FulltextIndex.FieldPair> fieldPairs =
                        decodeFulltextFields(fields.get("fields"));
                final String lang = fields.get("lang").asSome()
                        .map(ComponentVal::asString).orElse(null);
                index.insertDocument(id, fieldPairs, lang);
                accepted++;
            }
            return new Object[] { ComponentVal.ok(ComponentVal.u32((long) accepted)) };
        };
    }

    /** {@code delete-documents: func(index: string, ids: list<string>)
     *  -> result<u32, fulltext-error>}. Batch remove by id; returns the
     *  count actually removed (missing ids do not count). */
    public static WitHostFunction fulltextDeleteDocuments() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String indexName = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "fulltext-callbacks", "delete-documents", indexName);
            if (ctx != null) ctx.chargeToll("fulltext-callbacks.delete-documents");
            final Optional<FulltextIndex> indexOpt =
                    InMemoryFulltextRegistry.INSTANCE.index(indexName);
            if (indexOpt.isEmpty()) {
                return new Object[] { ComponentVal.err(fulltextError("no-such-index",
                    "fulltext-callbacks: no index registered under name '" + indexName
                    + "' — declare it in " + WebFunctionConfig.PROP_FULLTEXT_INDEXES
                    + " at boot.")) };
            }
            final List<ComponentVal> ids = args.length > 1 && args[1] != null
                    ? ((ComponentVal) args[1]).asList()
                    : java.util.Collections.emptyList();
            final FulltextIndex index = indexOpt.get();
            int removed = 0;
            for (final ComponentVal idVal : ids) {
                if (index.deleteDocument(idVal.asString())) {
                    removed++;
                }
            }
            return new Object[] { ComponentVal.ok(ComponentVal.u32((long) removed)) };
        };
    }

    /** {@code search-index: func(index: string, query: string, limit: option<u32>)
     *  -> result<list<fulltext-hit>, fulltext-error>}. Naive case-
     *  insensitive substring match — see {@link FulltextIndex#search}
     *  for the algorithm. */
    public static WitHostFunction fulltextSearchIndex() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String indexName = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "fulltext-callbacks", "search-index", indexName);
            if (ctx != null) ctx.chargeToll("fulltext-callbacks.search-index");
            final Optional<FulltextIndex> indexOpt =
                    InMemoryFulltextRegistry.INSTANCE.index(indexName);
            if (indexOpt.isEmpty()) {
                return new Object[] { ComponentVal.err(fulltextError("no-such-index",
                    "fulltext-callbacks: no index registered under name '" + indexName
                    + "' — declare it in " + WebFunctionConfig.PROP_FULLTEXT_INDEXES
                    + " at boot.")) };
            }
            final String query = args.length > 1 && args[1] != null
                    ? ((ComponentVal) args[1]).asString() : "";
            final Integer limit = args.length > 2 && args[2] != null
                    ? decodeOptionalU32((ComponentVal) args[2]).orElse(null) : null;
            final List<FulltextIndex.Hit> hits = indexOpt.get().search(query, limit);
            final List<ComponentVal> encoded = new ArrayList<>(hits.size());
            for (final FulltextIndex.Hit hit : hits) {
                final Map<String, ComponentVal> hitFields = new LinkedHashMap<>();
                // WIT `fulltext-hit.subject` is a `term`. Encode the
                // document id as a named-node — mirrors the Oxigraph
                // reference impl exactly.
                hitFields.put("subject",
                        ComponentVal.variant("named-node", ComponentVal.string(hit.id())));
                hitFields.put("score", ComponentVal.f64(hit.score()));
                // Reference impl leaves snippets unset — production
                // backends (Manticore highlighting, OpenSearch's
                // highlight blocks) fill this in.
                hitFields.put("snippet", ComponentVal.none());
                encoded.add(ComponentVal.record(hitFields));
            }
            return new Object[] { ComponentVal.ok(ComponentVal.list(encoded)) };
        };
    }

    /** Decode a WIT {@code list<tuple<string, string>>} field list into
     *  the {@link FulltextIndex.FieldPair} carriers the registry
     *  stores. */
    private static List<FulltextIndex.FieldPair> decodeFulltextFields(final ComponentVal listVal) {
        if (listVal == null) return java.util.Collections.emptyList();
        final List<ComponentVal> pairs = listVal.asList();
        final List<FulltextIndex.FieldPair> out = new ArrayList<>(pairs.size());
        for (final ComponentVal pair : pairs) {
            final List<ComponentVal> tuple = pair.asTuple();
            final String predicate = tuple.get(0).asString();
            final String value = tuple.get(1).asString();
            out.add(new FulltextIndex.FieldPair(predicate, value));
        }
        return out;
    }

    private static ComponentVal fulltextError(final String armName, final String message) {
        return ComponentVal.variant(armName, ComponentVal.string(message));
    }
}
