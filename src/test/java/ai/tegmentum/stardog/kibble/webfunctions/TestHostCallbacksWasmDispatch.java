package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentResult;
import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.wasmtime4j.component.ComponentVariant;
import com.complexible.stardog.index.dictionary.MappingDictionary;
import com.stardog.stark.Value;
import com.stardog.stark.Values;
import com.stardog.stark.query.BindingSets;
import com.stardog.stark.query.SelectQueryResult;
import com.stardog.stark.query.impl.SelectQueryResultImpl;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * wasm-callbacks dispatch unit tests — Phase-N5 companion to the
 * {@link HostCallbacks#invokeWasmDispatch} MVP fill-in. Drives the
 * dispatch through the package-private {@link HostCallbacks.CalleeInvoker}
 * seam so tests exercise the full flow (capability + nesting + error
 * mapping + fuel-reflection) without wiring a real wasm engine or the
 * component-mode runtime.
 *
 * <p>The dispatch's real callee load (via {@link CalleeComponentLoader})
 * needs an actual wasm URL — instead of standing up a fake artifact
 * server, we inject a mock {@link HostCallbacks.CalleeInvoker} that
 * returns a synthetic {@link SelectQueryResult} (or throws) and verify
 * the dispatch's shape at the WIT boundary.
 */
public class TestHostCallbacksWasmDispatch {

    private MappingDictionary dictionary;

    @Before
    public void setUp() {
        CapabilityAttributionRing.INSTANCE.clear();
        CapabilityAttributionRing.INSTANCE.setEnabled(false);
        CapabilityEnforcer.install(null);
        CapabilityEnforcer.setEnabled(false);
        ThreadContext.unbindSubject();
        // The invoke-wasm* dispatch requires webfunctions.callback.enabled=true.
        // The default is true, but a leaky test may have flipped it — set it
        // explicitly to true and clear on teardown so we don't leak.
        System.clearProperty("webfunctions.callback.enabled");
        // Give the dispatch a MappingDictionary so it doesn't reject with
        // "no dictionary bound". The dispatch never dereferences it under
        // the mock invoker, so a bare Proxy is safe (identity is enough).
        dictionary = (MappingDictionary) java.lang.reflect.Proxy.newProxyInstance(
                MappingDictionary.class.getClassLoader(),
                new Class<?>[] { MappingDictionary.class },
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("mock dictionary");
                });
    }

    @After
    public void tearDown() {
        CapabilityEnforcer.install(null);
        CapabilityEnforcer.setEnabled(false);
        CapabilityAttributionRing.INSTANCE.clear();
        ThreadContext.unbindSubject();
        System.clearProperty("webfunctions.callback.enabled");
        final CallbackContext ctx = CallbackContext.current();
        if (ctx != null) CallbackContext.unbindIfOutermost(ctx);
    }

    // ---- happy path --------------------------------------------------

    @Test
    public void invokeWasmServiceHappyPath_returnsListOfBindingOk() {
        final CallbackContext ctx = CallbackContext.bind(null, dictionary);
        // Mock callee returns a 1-row 1-binding result with value_0 = "cornerstone".
        final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
            @Override
            public <R> R invoke(final String url,
                                final MappingDictionary dict,
                                final String functionName,
                                final Value[] args,
                                final java.util.function.Function<SelectQueryResult, R> body)
                    throws Exception {
                try (SelectQueryResult rs = singleBindingResult("value_0",
                        Values.literal("cornerstone"))) {
                    return body.apply(rs);
                }
            }
        };
        final Object[] out = HostCallbacks.invokeWasmDispatch(
                new Object[] { ComponentVal.string("ipfs://QmMock"),
                               ComponentVal.list(Collections.emptyList()) },
                "invoke-wasm-service",
                HostCallbacks.CalleeReturnShape.LIST_OF_BINDING,
                mock);
        // Ok arm with a list<binding> payload — one binding with variable = value_0
        // and value = literal("cornerstone").
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
        final ComponentVal list = result.getOk().orElseThrow();
        final List<ComponentVal> bindings = list.asList();
        assertThat(bindings).hasSize(1);
        final Map<String, ComponentVal> fields = bindings.get(0).asRecord();
        assertThat(fields.get("variable").asString()).isEqualTo("value_0");
        final ComponentVariant term = fields.get("value").asVariant();
        assertThat(term.getCaseName()).isEqualTo("literal");
    }

    @Test
    public void invokeWasmV1HappyPath_returnsSingleTermOk() {
        final CallbackContext ctx = CallbackContext.bind(null, dictionary);
        final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
            @Override
            public <R> R invoke(final String url,
                                final MappingDictionary dict,
                                final String functionName,
                                final Value[] args,
                                final java.util.function.Function<SelectQueryResult, R> body)
                    throws Exception {
                try (SelectQueryResult rs = singleBindingResult("value_0",
                        Values.iri("urn:test:foo"))) {
                    return body.apply(rs);
                }
            }
        };
        final Object[] out = HostCallbacks.invokeWasmDispatch(
                new Object[] { ComponentVal.string("ipfs://QmMock"),
                               ComponentVal.string("fn"),
                               ComponentVal.list(Collections.emptyList()) },
                "invoke-wasm",
                HostCallbacks.CalleeReturnShape.SINGLE_TERM,
                mock);
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
        final ComponentVariant term = result.getOk().orElseThrow().asVariant();
        assertThat(term.getCaseName()).isEqualTo("named-node");
        assertThat(term.getPayload().orElseThrow().asString()).isEqualTo("urn:test:foo");
    }

    // ---- function-name honoring -------------------------------------

    /**
     * The invoke-wasm dispatch must forward the caller's function-name
     * argument to the {@link HostCallbacks.CalleeInvoker} verbatim so a
     * multi-function callee (or any callee that wants to gate on the
     * name) can route by exact match. Prior to F3a the argument was
     * decoded off the wire but dropped on the floor.
     */
    @Test
    public void invokeWasmV1_functionNameThreadedToInvoker() {
        final CallbackContext ctx = CallbackContext.bind(null, dictionary);
        final AtomicReference<String> seenName = new AtomicReference<>();
        final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
            @Override
            public <R> R invoke(final String url,
                                final MappingDictionary dict,
                                final String functionName,
                                final Value[] args,
                                final java.util.function.Function<SelectQueryResult, R> body)
                    throws Exception {
                seenName.set(functionName);
                try (SelectQueryResult rs = singleBindingResult("value_0",
                        Values.iri("urn:test:foo"))) {
                    return body.apply(rs);
                }
            }
        };
        final Object[] out = HostCallbacks.invokeWasmDispatch(
                new Object[] { ComponentVal.string("ipfs://QmMock"),
                               ComponentVal.string("my-filter"),
                               ComponentVal.list(Collections.emptyList()) },
                "invoke-wasm",
                HostCallbacks.CalleeReturnShape.SINGLE_TERM,
                mock);
        assertThat(seenName.get()).isEqualTo("my-filter");
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
    }

    /**
     * A callee whose {@code extension.register()} export does not
     * advertise the requested function name surfaces
     * {@link StardogWasmInstance.NoSuchFilterFunctionException} out of
     * the invoker. F4 tightening: the dispatch maps that to the
     * dedicated {@code function-not-found} arm (was {@code not-found}
     * pre-F4) so a guest can distinguish "no such URL" from "URL
     * loaded but does not export that function".
     */
    @Test
    public void invokeWasmV1_nameMismatchMapsToFunctionNotFound() {
        final CallbackContext ctx = CallbackContext.bind(null, dictionary);
        final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
            @Override
            public <R> R invoke(final String url,
                                final MappingDictionary dict,
                                final String functionName,
                                final Value[] args,
                                final java.util.function.Function<SelectQueryResult, R> body)
                    throws Exception {
                throw new StardogWasmInstance.NoSuchFilterFunctionException(
                        "component does not export filter function '" + functionName + "'; "
                        + "available: other-fn");
            }
        };
        final Object[] out = HostCallbacks.invokeWasmDispatch(
                new Object[] { ComponentVal.string("ipfs://QmMock"),
                               ComponentVal.string("missing-fn"),
                               ComponentVal.list(Collections.emptyList()) },
                "invoke-wasm",
                HostCallbacks.CalleeReturnShape.SINGLE_TERM,
                mock);
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isErr()).isTrue();
        final ComponentVariant err = result.getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("function-not-found");
        assertThat(err.getPayload().orElseThrow().asString())
                .contains("missing-fn")
                .contains("does not export filter function");
    }

    /**
     * Same rule applies when the callee happens to export a single
     * filter function but the caller's name doesn't match it. The
     * legacy pick-first-descriptor auto-discovery must NOT silently
     * fall through — that's the bug we're fixing. F4 variant is
     * {@code function-not-found} (was {@code not-found} pre-F4).
     */
    @Test
    public void invokeWasmV1_nameMismatchOnSingleFunctionCallee_stillFunctionNotFound() {
        final CallbackContext ctx = CallbackContext.bind(null, dictionary);
        final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
            @Override
            public <R> R invoke(final String url,
                                final MappingDictionary dict,
                                final String functionName,
                                final Value[] args,
                                final java.util.function.Function<SelectQueryResult, R> body)
                    throws Exception {
                // Simulate a callee that exports exactly one function
                // whose name is not what the caller asked for.
                throw new StardogWasmInstance.NoSuchFilterFunctionException(
                        "component does not export filter function '" + functionName + "'; "
                        + "available: the-only-fn");
            }
        };
        final Object[] out = HostCallbacks.invokeWasmDispatch(
                new Object[] { ComponentVal.string("ipfs://QmMock"),
                               ComponentVal.string("wrong-name"),
                               ComponentVal.list(Collections.emptyList()) },
                "invoke-wasm",
                HostCallbacks.CalleeReturnShape.SINGLE_TERM,
                mock);
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isErr()).isTrue();
        final ComponentVariant err = result.getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("function-not-found");
    }

    /**
     * Legacy backward compatibility: when the caller leaves
     * {@code function-name} empty AND the callee exports exactly one
     * filter, the dispatch still resolves via auto-discovery. The
     * empty name lands at the invoker as-is (empty string), and the
     * PROD invoker will threading it through
     * {@link StardogWasmInstance#evaluate(String, Value...)} where the
     * single-registered-function branch fires.
     */
    @Test
    public void invokeWasmV1_emptyNameSingleFunctionCallee_stillDispatches() {
        final CallbackContext ctx = CallbackContext.bind(null, dictionary);
        final AtomicReference<String> seenName = new AtomicReference<>();
        final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
            @Override
            public <R> R invoke(final String url,
                                final MappingDictionary dict,
                                final String functionName,
                                final Value[] args,
                                final java.util.function.Function<SelectQueryResult, R> body)
                    throws Exception {
                seenName.set(functionName);
                // Single-function callees still auto-discover in the
                // strict evaluate path; mock simulates that success.
                try (SelectQueryResult rs = singleBindingResult("value_0",
                        Values.literal("auto"))) {
                    return body.apply(rs);
                }
            }
        };
        final Object[] out = HostCallbacks.invokeWasmDispatch(
                new Object[] { ComponentVal.string("ipfs://QmMock"),
                               ComponentVal.string(""),
                               ComponentVal.list(Collections.emptyList()) },
                "invoke-wasm",
                HostCallbacks.CalleeReturnShape.SINGLE_TERM,
                mock);
        // Empty function-name is preserved on the wire — the invoker
        // sees the empty string and defers to the callee's registered
        // surface for auto-discovery.
        assertThat(seenName.get()).isEqualTo("");
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
    }

    /**
     * When the caller leaves {@code function-name} empty but the
     * callee exports more than one filter, auto-discovery would silently
     * pick the first — which is exactly the bug the task description
     * flagged. The strict evaluate path surfaces
     * {@link StardogWasmInstance.AmbiguousFilterFunctionException}
     * which the dispatch maps to the dedicated
     * {@code ambiguous-function} arm added in F4 (was
     * {@code invocation-error} pre-F4). Guests can now respond by
     * re-issuing with an explicit name rather than treating the
     * failure as a callee trap.
     */
    @Test
    public void invokeWasmV1_emptyNameMultiFunctionCallee_ambiguousFunction() {
        final CallbackContext ctx = CallbackContext.bind(null, dictionary);
        final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
            @Override
            public <R> R invoke(final String url,
                                final MappingDictionary dict,
                                final String functionName,
                                final Value[] args,
                                final java.util.function.Function<SelectQueryResult, R> body)
                    throws Exception {
                // Simulate the strict evaluate path detecting a
                // multi-function callee with no name selector.
                throw new StardogWasmInstance.AmbiguousFilterFunctionException(
                        "component exports multiple filter functions (a, b); "
                        + "caller must specify function-name");
            }
        };
        final Object[] out = HostCallbacks.invokeWasmDispatch(
                new Object[] { ComponentVal.string("ipfs://QmMock"),
                               ComponentVal.string(""),
                               ComponentVal.list(Collections.emptyList()) },
                "invoke-wasm",
                HostCallbacks.CalleeReturnShape.SINGLE_TERM,
                mock);
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isErr()).isTrue();
        final ComponentVariant err = result.getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("ambiguous-function");
        assertThat(err.getPayload().orElseThrow().asString())
                .contains("multiple filter functions")
                .contains("caller must specify function-name");
    }

    /**
     * The property-function shape (invoke-wasm-service) has no
     * function-name argument on the wire, so the invoker must see a
     * null selector to preserve the "route to the single registered
     * property function" contract.
     */
    @Test
    public void invokeWasmService_functionNameIsNull() {
        final CallbackContext ctx = CallbackContext.bind(null, dictionary);
        final AtomicReference<String> seenName = new AtomicReference<>("SENTINEL");
        final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
            @Override
            public <R> R invoke(final String url,
                                final MappingDictionary dict,
                                final String functionName,
                                final Value[] args,
                                final java.util.function.Function<SelectQueryResult, R> body)
                    throws Exception {
                seenName.set(functionName);
                try (SelectQueryResult rs = singleBindingResult("value_0",
                        Values.literal("svc"))) {
                    return body.apply(rs);
                }
            }
        };
        HostCallbacks.invokeWasmDispatch(
                new Object[] { ComponentVal.string("ipfs://QmMock"),
                               ComponentVal.list(Collections.emptyList()) },
                "invoke-wasm-service",
                HostCallbacks.CalleeReturnShape.LIST_OF_BINDING,
                mock);
        assertThat(seenName.get()).isNull();
    }

    // ---- capability-denied path -------------------------------------

    @Test
    public void wasmCalleeAllowlistDeniesCall_returnsNotPermitted() {
        CapabilityEnforcer.install(CapabilityEnforcer.create());
        CapabilityEnforcer.setEnabled(true);
        final CallbackContext ctx = CallbackContext.bind(null, dictionary);
        // Grant WasmCallbacks interface but restrict callees to a single
        // allowed URL — the test tries a different URL so the fine-grained
        // check fails.
        final CapabilityGrant grant = new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("wasm-callbacks"),
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                HttpPathAllowlist.ALLOW_NONE,
                WasmCalleeAllowlist.of(java.util.List.of("ipfs://QmAllowed")),
                "alice",
                CapabilityModel.INVOKER_SUBJECT);
        ctx.setCapabilityGrant(grant);
        final AtomicBoolean invokerCalled = new AtomicBoolean(false);
        final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
            @Override
            public <R> R invoke(final String url,
                                final MappingDictionary dict,
                                final String functionName,
                                final Value[] args,
                                final java.util.function.Function<SelectQueryResult, R> body) {
                invokerCalled.set(true);
                throw new IllegalStateException("should not have been called");
            }
        };
        final Object[] out = HostCallbacks.invokeWasmDispatch(
                new Object[] { ComponentVal.string("ipfs://QmDenied"),
                               ComponentVal.list(Collections.emptyList()) },
                "invoke-wasm-service",
                HostCallbacks.CalleeReturnShape.LIST_OF_BINDING,
                mock);
        assertThat(invokerCalled.get()).isFalse();
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isErr()).isTrue();
        final ComponentVariant err = result.getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("not-permitted");
    }

    // ---- nesting-denied path ----------------------------------------

    /**
     * F4 tightening: MVP single-level nesting rule surfaces the
     * dedicated {@code nesting-not-permitted} arm (was
     * {@code not-permitted} pre-F4). Distinct from
     * {@code not-permitted} (which stays for capability denial) so a
     * guest can tell a substrate structural rule apart from a policy
     * call.
     */
    @Test
    public void nestedInvokeWasmService_deniedWithNestingNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind(null, dictionary);
        // Simulate being inside a callee's frame by bumping wasm-call depth
        // as if the outer wasm-callbacks dispatch had already entered.
        ctx.enterWasmCall();
        try {
            final AtomicBoolean invokerCalled = new AtomicBoolean(false);
            final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
                @Override
                public <R> R invoke(final String url,
                                    final MappingDictionary dict,
                                    final String functionName,
                                    final Value[] args,
                                    final java.util.function.Function<SelectQueryResult, R> body) {
                    invokerCalled.set(true);
                    throw new IllegalStateException("should not have been called");
                }
            };
            final Object[] out = HostCallbacks.invokeWasmDispatch(
                    new Object[] { ComponentVal.string("ipfs://QmMock"),
                                   ComponentVal.list(Collections.emptyList()) },
                    "invoke-wasm-service",
                    HostCallbacks.CalleeReturnShape.LIST_OF_BINDING,
                    mock);
            assertThat(invokerCalled.get()).isFalse();
            final ComponentResult result = ((ComponentVal) out[0]).asResult();
            assertThat(result.isErr()).isTrue();
            final ComponentVariant err = result.getErr().orElseThrow().asVariant();
            assertThat(err.getCaseName()).isEqualTo("nesting-not-permitted");
            assertThat(err.getPayload().orElseThrow().asString())
                    .contains("nested wasm-callbacks");
        } finally {
            ctx.exitWasmCall();
        }
    }

    // ---- callee-trap path -------------------------------------------

    @Test
    public void calleeThrowsIOException_mapsToInvocationError() {
        final CallbackContext ctx = CallbackContext.bind(null, dictionary);
        final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
            @Override
            public <R> R invoke(final String url,
                                final MappingDictionary dict,
                                final String functionName,
                                final Value[] args,
                                final java.util.function.Function<SelectQueryResult, R> body)
                    throws Exception {
                throw new java.io.IOException("guest trap: unreachable");
            }
        };
        final Object[] out = HostCallbacks.invokeWasmDispatch(
                new Object[] { ComponentVal.string("ipfs://QmMock"),
                               ComponentVal.list(Collections.emptyList()) },
                "invoke-wasm-service",
                HostCallbacks.CalleeReturnShape.LIST_OF_BINDING,
                mock);
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isErr()).isTrue();
        final ComponentVariant err = result.getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("invocation-error");
        assertThat(err.getPayload().orElseThrow().asString())
                .contains("callee trap");
    }

    @Test
    public void calleeThrowsMalformedUrl_mapsToNotFound() {
        final CallbackContext ctx = CallbackContext.bind(null, dictionary);
        final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
            @Override
            public <R> R invoke(final String url,
                                final MappingDictionary dict,
                                final String functionName,
                                final Value[] args,
                                final java.util.function.Function<SelectQueryResult, R> body)
                    throws Exception {
                throw new java.net.MalformedURLException("not a url");
            }
        };
        final Object[] out = HostCallbacks.invokeWasmDispatch(
                new Object[] { ComponentVal.string("!!!"),
                               ComponentVal.list(Collections.emptyList()) },
                "invoke-wasm-service",
                HostCallbacks.CalleeReturnShape.LIST_OF_BINDING,
                mock);
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isErr()).isTrue();
        final ComponentVariant err = result.getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("not-found");
    }

    // ---- fuel-exhaustion mid-dispatch -------------------------------

    /**
     * F4 tightening: fuel exhaustion in the reflect step (invoked
     * from the inner finally block of invokeWasmDispatchInner) maps
     * to the dedicated {@code fuel-exhausted} arm at the WIT boundary
     * (was propagated as {@link WfBudgetError.HostCallbackTollExhausted}
     * pre-F4, which the outer wf:call catch surface promoted to a
     * typed SPARQL error). A guest catching the callback now sees a
     * typed result naming fuel as the cause; the typed
     * {@code WfBudgetError} promotion still fires the next time the
     * caller charges an exhausted budget.
     */
    @Test
    public void fuelReflectionExhaustsCallerBudget_mapsToFuelExhausted() {
        final CallbackContext ctx = CallbackContext.bind(null, dictionary);
        // Stamp a caller ComponentInstance that exhausts on the first
        // consumeFuel call — the reflect path should catch WebAssemblyException
        // and throw HostCallbackTollExhausted, which the F4 outer catch
        // in invokeWasmDispatchInner maps to the fuel-exhausted arm.
        final AtomicBoolean consumeFuelCalled = new AtomicBoolean(false);
        final ai.tegmentum.webassembly4j.api.ComponentInstance callerInstance =
                new StubComponentInstance() {
                    @Override
                    public long fuelConsumed() { return 0L; }
                    @Override
                    public void consumeFuel(final long fuel) {
                        consumeFuelCalled.set(true);
                        throw new ai.tegmentum.webassembly4j.api.exception.WebAssemblyException(
                                "fuel exhausted");
                    }
                };
        ctx.setComponentInstance(callerInstance);
        // Also stamp a fuel budget so the reflection path fires.
        ctx.setFuelMeteringContext("file:///ext.wasm", 1000L, 10L);

        // Mock invoker that consumes fuel on the callee side — the reflect
        // path reads ctx.fuelConsumed() from the CALLEE'S ComponentInstance
        // stamped by evaluate. Simulate the stamp + fuel usage inline.
        final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
            @Override
            public <R> R invoke(final String url,
                                final MappingDictionary dict,
                                final String functionName,
                                final Value[] args,
                                final java.util.function.Function<SelectQueryResult, R> body)
                    throws Exception {
                // Simulate the callee stamping its ComponentInstance
                // with 500 fuel consumed — that's the value the reflect
                // path will try to charge against caller.
                final ai.tegmentum.webassembly4j.api.ComponentInstance calleeInstance =
                        new StubComponentInstance() {
                            @Override
                            public long fuelConsumed() { return 500L; }
                        };
                ctx.setComponentInstance(calleeInstance);
                try (SelectQueryResult rs = singleBindingResult("value_0",
                        Values.literal("ok"))) {
                    return body.apply(rs);
                }
            }
        };

        final Object[] out = HostCallbacks.invokeWasmDispatch(
                new Object[] { ComponentVal.string("ipfs://QmMock"),
                               ComponentVal.list(Collections.emptyList()) },
                "invoke-wasm-service",
                HostCallbacks.CalleeReturnShape.LIST_OF_BINDING,
                mock);
        // F4: the reflect-side fuel error is caught by the outer
        // try/catch in invokeWasmDispatchInner and mapped to the
        // fuel-exhausted arm rather than propagating out.
        assertThat(consumeFuelCalled.get()).isTrue();
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isErr()).isTrue();
        final ComponentVariant err = result.getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("fuel-exhausted");
        assertThat(err.getPayload().orElseThrow().asString())
                .contains("fuel budget");
        // Post-dispatch: caller's ComponentInstance is restored, wasm
        // nesting is decremented.
        assertThat(ctx.componentInstanceOrNull()).isSameAs(callerInstance);
        assertThat(ctx.wasmCallDepth()).isEqualTo(0);
    }

    /**
     * F4 tightening: fuel exhaustion on the entry-side toll charge
     * (before the callee is invoked) also maps to the dedicated
     * {@code fuel-exhausted} arm rather than propagating out as a
     * wasm trap. Complements
     * {@link #fuelReflectionExhaustsCallerBudget_mapsToFuelExhausted}
     * — that one exercises the reflect-side path, this one exercises
     * the toll-charge-side path.
     */
    @Test
    public void tollExhaustsCallerBudget_mapsToFuelExhausted() {
        final CallbackContext ctx = CallbackContext.bind(null, dictionary);
        // Stamp a fuel budget of 1 with a toll of 100 so the very first
        // wasm-callbacks.<method> toll charge exhausts the budget.
        ctx.setFuelMeteringContext("file:///ext.wasm", 1L, 100L);
        final AtomicBoolean invokerCalled = new AtomicBoolean(false);
        final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
            @Override
            public <R> R invoke(final String url,
                                final MappingDictionary dict,
                                final String functionName,
                                final Value[] args,
                                final java.util.function.Function<SelectQueryResult, R> body) {
                invokerCalled.set(true);
                throw new IllegalStateException("should not have been called");
            }
        };
        final Object[] out = HostCallbacks.invokeWasmDispatch(
                new Object[] { ComponentVal.string("ipfs://QmMock"),
                               ComponentVal.list(Collections.emptyList()) },
                "invoke-wasm-service",
                HostCallbacks.CalleeReturnShape.LIST_OF_BINDING,
                mock);
        // Toll exhaustion short-circuits before the invoker is reached.
        assertThat(invokerCalled.get()).isFalse();
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isErr()).isTrue();
        final ComponentVariant err = result.getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("fuel-exhausted");
        assertThat(err.getPayload().orElseThrow().asString())
                .contains("fuel budget");
    }

    @Test
    public void nestingCounterRestoredAfterCalleeException() {
        final CallbackContext ctx = CallbackContext.bind(null, dictionary);
        final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
            @Override
            public <R> R invoke(final String url,
                                final MappingDictionary dict,
                                final String functionName,
                                final Value[] args,
                                final java.util.function.Function<SelectQueryResult, R> body)
                    throws Exception {
                throw new RuntimeException("boom");
            }
        };
        HostCallbacks.invokeWasmDispatch(
                new Object[] { ComponentVal.string("ipfs://QmMock"),
                               ComponentVal.list(Collections.emptyList()) },
                "invoke-wasm-service",
                HostCallbacks.CalleeReturnShape.LIST_OF_BINDING,
                mock);
        // finally-block ran → depth restored to 0.
        assertThat(ctx.wasmCallDepth()).isEqualTo(0);
    }

    // ---- config + no-context guards ---------------------------------

    @Test
    public void callbackDisabled_returnsNotPermitted() {
        System.setProperty("webfunctions.callback.enabled", "false");
        try {
            final CallbackContext ctx = CallbackContext.bind(null, dictionary);
            final AtomicReference<String> reason = new AtomicReference<>();
            final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
                @Override
                public <R> R invoke(final String url,
                                    final MappingDictionary dict,
                                    final String functionName,
                                    final Value[] args,
                                    final java.util.function.Function<SelectQueryResult, R> body) {
                    reason.set("should not have been called");
                    throw new IllegalStateException("dispatch reached invoker despite disabled");
                }
            };
            final Object[] out = HostCallbacks.invokeWasmDispatch(
                    new Object[] { ComponentVal.string("ipfs://QmMock"),
                                   ComponentVal.list(Collections.emptyList()) },
                    "invoke-wasm-service",
                    HostCallbacks.CalleeReturnShape.LIST_OF_BINDING,
                    mock);
            assertThat(reason.get()).isNull();
            final ComponentResult result = ((ComponentVal) out[0]).asResult();
            assertThat(result.isErr()).isTrue();
            assertThat(result.getErr().orElseThrow().asVariant().getCaseName())
                    .isEqualTo("not-permitted");
        } finally {
            System.clearProperty("webfunctions.callback.enabled");
        }
    }

    @Test
    public void noDictionaryBound_returnsInvocationError() {
        // Bind without a dictionary — dispatch rejects because a callee
        // cannot resolve the caller's mapping without one.
        final CallbackContext ctx = CallbackContext.bind();
        final HostCallbacks.CalleeInvoker mock = new HostCallbacks.CalleeInvoker() {
            @Override
            public <R> R invoke(final String url,
                                final MappingDictionary dict,
                                final String functionName,
                                final Value[] args,
                                final java.util.function.Function<SelectQueryResult, R> body) {
                throw new IllegalStateException("should not reach invoker");
            }
        };
        final Object[] out = HostCallbacks.invokeWasmDispatch(
                new Object[] { ComponentVal.string("ipfs://QmMock"),
                               ComponentVal.list(Collections.emptyList()) },
                "invoke-wasm-service",
                HostCallbacks.CalleeReturnShape.LIST_OF_BINDING,
                mock);
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr().orElseThrow().asVariant().getCaseName())
                .isEqualTo("invocation-error");
    }

    // ---- helpers ----------------------------------------------------

    private static SelectQueryResult singleBindingResult(final String var, final Value v) {
        final BindingSets.Builder bsb = BindingSets.builder();
        bsb.add(var, v);
        return new SelectQueryResultImpl(
                Collections.singletonList(var),
                Collections.singletonList(bsb.build()));
    }

    /**
     * Bare stub of {@link ai.tegmentum.webassembly4j.api.ComponentInstance}
     * for fuel-reflection tests. Subclass and override only what the test
     * needs; every abstract method throws {@link UnsupportedOperationException}
     * so a test that accidentally reaches an un-overridden method blows up
     * loudly. {@code consumeFuel} + {@code fuelConsumed} are default in
     * the interface, so subclasses can override just those two.
     */
    private static abstract class StubComponentInstance
            implements ai.tegmentum.webassembly4j.api.ComponentInstance {

        @Override
        public Object invoke(final String exportName, final Object... args) {
            throw new UnsupportedOperationException("stub.invoke");
        }

        @Override
        public boolean hasFunction(final String name) { return false; }

        @Override
        public java.util.List<String> exportedFunctions() { return Collections.emptyList(); }

        @Override
        public java.util.List<String> exportedInterfaces() { return Collections.emptyList(); }

        @Override
        public boolean exportsInterface(final String name) { return false; }

        @Override
        public java.util.Optional<ai.tegmentum.webassembly4j.api.Function> function(final String name) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<ai.tegmentum.webassembly4j.api.Memory> memory(final String name) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<ai.tegmentum.webassembly4j.api.Table> table(final String name) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<ai.tegmentum.webassembly4j.api.Global> global(final String name) {
            return java.util.Optional.empty();
        }

        @Override
        public <T> java.util.Optional<T> unwrap(final Class<T> type) {
            return java.util.Optional.empty();
        }
    }

    @SuppressWarnings("unused")
    private static Optional<ComponentVal> okPayload(final ComponentResult r) {
        return r.getOk();
    }
}
