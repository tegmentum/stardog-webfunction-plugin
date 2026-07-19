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
        enforcer.get().enforceHttpPath(grant, ctx.extensionUri(), method, url);
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
        enforcer.get().enforceWasmCallee(grant, ctx.extensionUri(), method, calleeUrl);
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
     * <p>MVP: returns {@code wasm-call-error::not-permitted} with a descriptive
     * message. Full sub-component composition on the JVM host is separate
     * future work — the WIT surface is wired so guests importing this
     * interface can link, but the actual dispatch table isn't populated yet.
     */
    public static WitHostFunction invokeWasmV1() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String wasmUri = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "wasm-callbacks", "invoke-wasm", wasmUri);
            enforceWasmCalleeCapability(ctx, "invoke-wasm", wasmUri);
            if (ctx != null) ctx.chargeToll("wasm-callbacks.invoke-wasm");
            return new Object[] { ComponentVal.err(wasmCallError("not-permitted",
                "invoke-wasm: not implemented on JVM host (MVP stub — full sub-component "
                + "dispatch is future work)")) };
        };
    }

    /**
     * Base-substrate {@code tegmentum:webfunction/wasm-callbacks@0.1.0#invoke-wasm-service:
     *  func(url: string, args: list<term>) -> result<list<binding>, wasm-call-error>}.
     *
     * <p>Property-function-shape counterpart to {@link #invokeWasmV1}. MVP is
     * a {@code not-permitted} stub for the same reason: full JVM-host
     * component composition is separate future work.
     */
    public static WitHostFunction invokeWasmService() {
        return args -> {
            final CallbackContext ctx = CallbackContext.current();
            final String wasmSvcUri = args.length > 0 ? ((ComponentVal) args[0]).asString() : "";
            enforceCapability(ctx, "wasm-callbacks", "invoke-wasm-service", wasmSvcUri);
            enforceWasmCalleeCapability(ctx, "invoke-wasm-service", wasmSvcUri);
            if (ctx != null) ctx.chargeToll("wasm-callbacks.invoke-wasm-service");
            return new Object[] { ComponentVal.err(wasmCallError("not-permitted",
                "invoke-wasm-service: not implemented on JVM host (MVP stub — full "
                + "sub-component dispatch is future work)")) };
        };
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
}
