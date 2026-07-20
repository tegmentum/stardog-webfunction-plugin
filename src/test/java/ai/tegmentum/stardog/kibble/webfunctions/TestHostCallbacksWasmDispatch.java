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
import static org.assertj.core.api.Assertions.catchThrowable;

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

    @Test
    public void nestedInvokeWasmService_deniedWithNotPermitted() {
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
            assertThat(err.getCaseName()).isEqualTo("not-permitted");
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

    @Test
    public void fuelReflectionExhaustsCallerBudget_throwsHostCallbackTollExhausted() {
        final CallbackContext ctx = CallbackContext.bind(null, dictionary);
        // Stamp a caller ComponentInstance that exhausts on the first
        // consumeFuel call — the reflect path should catch WebAssemblyException
        // and throw HostCallbackTollExhausted.
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

        final Throwable thrown = catchThrowable(() -> HostCallbacks.invokeWasmDispatch(
                new Object[] { ComponentVal.string("ipfs://QmMock"),
                               ComponentVal.list(Collections.emptyList()) },
                "invoke-wasm-service",
                HostCallbacks.CalleeReturnShape.LIST_OF_BINDING,
                mock));
        // Fuel exhaustion in the reflect step surfaces as
        // HostCallbackTollExhausted — same typed error path the toll
        // exhaustion uses, so the outer wf:call catch surface promotes
        // uniformly.
        assertThat(thrown).isInstanceOf(WfBudgetError.HostCallbackTollExhausted.class);
        assertThat(consumeFuelCalled.get()).isTrue();
        // Post-dispatch: caller's ComponentInstance is restored, wasm
        // nesting is decremented.
        assertThat(ctx.componentInstanceOrNull()).isSameAs(callerInstance);
        assertThat(ctx.wasmCallDepth()).isEqualTo(0);
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
