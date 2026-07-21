package ai.tegmentum.stardog.kibble.webfunctions;

import org.apache.shiro.authz.Permission;
import org.apache.shiro.subject.Subject;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Capability wave Phase 4a — verifies the {@link CallbackContext#invokerSubject}
 * setter / getter roundtrip that {@link StardogWasmInstance} populates at
 * instantiation and {@link HostCallbacks} consults on every host-callback
 * dispatch when wrapping Stardog operations in
 * {@code ShiroUtils.executeAs(subject, ...)}.
 *
 * <p>Bypasses the wasm dispatch stack — the point of the invokerSubject
 * plumbing is a data-shape contract on CallbackContext, so this fixture
 * proves the shape without needing a live wasm engine.
 */
public class TestCallbackContext {

    @After
    public void tearDown() {
        // CallbackContext.bind uses a ThreadLocal — clear so cross-test
        // pollution can't leak into the next fixture.
        final CallbackContext ctx = CallbackContext.current();
        if (ctx != null) {
            CallbackContext.unbindIfOutermost(ctx);
        }
        System.clearProperty(WebFunctionConfig.PROP_WASM_CALLBACKS_MAX_NESTING_DEPTH);
    }

    @Test
    public void invokerSubjectDefaultsToEmpty() {
        final CallbackContext ctx = CallbackContext.bind();
        assertThat(ctx.invokerSubject()).isEmpty();
    }

    @Test
    public void invokerSubjectSetterRoundTrips() {
        final CallbackContext ctx = CallbackContext.bind();
        final Subject alice = stubSubject("alice");
        ctx.setInvokerSubject(alice);
        final Optional<Subject> back = ctx.invokerSubject();
        assertThat(back).isPresent();
        assertThat(back.get()).isSameAs(alice);
    }

    @Test
    public void invokerSubjectNullClears() {
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setInvokerSubject(stubSubject("bob"));
        assertThat(ctx.invokerSubject()).isPresent();
        ctx.setInvokerSubject(null);
        assertThat(ctx.invokerSubject()).isEmpty();
    }

    // ---- wasm-callbacks multi-level nesting (N1) --------------------

    /**
     * Happy path — a chain of {@code enterWasmCall} calls bumps depth
     * and pushes URLs into the chain; {@code exitWasmCall} pops them
     * back off. Verifies the field-level bookkeeping matches the
     * snapshot the audit ring later consumes.
     */
    @Test
    public void wasmCallChain_multiLevelHappyPath() {
        final CallbackContext ctx = CallbackContext.bind();
        assertThat(ctx.wasmCallDepth()).isEqualTo(0);
        assertThat(ctx.wasmCallChainSnapshot()).isEmpty();

        ctx.enterWasmCall("ipfs://A");
        assertThat(ctx.wasmCallDepth()).isEqualTo(1);
        assertThat(ctx.wasmCallChainSnapshot()).containsExactly("ipfs://A");

        ctx.enterWasmCall("ipfs://B");
        ctx.enterWasmCall("ipfs://C");
        assertThat(ctx.wasmCallDepth()).isEqualTo(3);
        assertThat(ctx.wasmCallChainSnapshot())
                .containsExactly("ipfs://A", "ipfs://B", "ipfs://C");

        ctx.exitWasmCall("ipfs://C");
        ctx.exitWasmCall("ipfs://B");
        ctx.exitWasmCall("ipfs://A");
        assertThat(ctx.wasmCallDepth()).isEqualTo(0);
        assertThat(ctx.wasmCallChainSnapshot()).isEmpty();
    }

    /**
     * The default depth cap ({@link WebFunctionConfig#DEFAULT_WASM_CALLBACKS_MAX_NESTING_DEPTH}
     * = 8) permits 8 nested entries; the 9th throws
     * {@link CallbackContext.WasmNestingException} with reason
     * {@code depth-exceeded}. Chain state is not mutated on rejection.
     */
    @Test
    public void wasmCallChain_defaultDepthCapRejectsNinth() {
        final CallbackContext ctx = CallbackContext.bind();
        for (int i = 0; i < WebFunctionConfig.DEFAULT_WASM_CALLBACKS_MAX_NESTING_DEPTH; i++) {
            ctx.enterWasmCall("ipfs://Q" + i);
        }
        assertThat(ctx.wasmCallDepth())
                .isEqualTo(WebFunctionConfig.DEFAULT_WASM_CALLBACKS_MAX_NESTING_DEPTH);

        final Throwable t = catchThrowable(() -> ctx.enterWasmCall("ipfs://Qlast"));
        assertThat(t).isInstanceOf(CallbackContext.WasmNestingException.class);
        assertThat(((CallbackContext.WasmNestingException) t).reason())
                .isEqualTo(CallbackContext.WasmNestingException.REASON_DEPTH_EXCEEDED);
        assertThat(t.getMessage()).contains("depth cap exceeded");
        // No side-effect on rejection: depth stays at cap and chain is unchanged.
        assertThat(ctx.wasmCallDepth())
                .isEqualTo(WebFunctionConfig.DEFAULT_WASM_CALLBACKS_MAX_NESTING_DEPTH);
        assertThat(ctx.wasmCallChainSnapshot())
                .hasSize(WebFunctionConfig.DEFAULT_WASM_CALLBACKS_MAX_NESTING_DEPTH);
    }

    /**
     * A configured cap of 1 preserves the pre-multi-level single-level
     * nesting behavior — one enter succeeds, the second is rejected.
     */
    @Test
    public void wasmCallChain_configuredDepthCapOfOne() {
        System.setProperty(WebFunctionConfig.PROP_WASM_CALLBACKS_MAX_NESTING_DEPTH, "1");
        final CallbackContext ctx = CallbackContext.bind();
        ctx.enterWasmCall("ipfs://A");
        final Throwable t = catchThrowable(() -> ctx.enterWasmCall("ipfs://B"));
        assertThat(t).isInstanceOf(CallbackContext.WasmNestingException.class);
        assertThat(((CallbackContext.WasmNestingException) t).reason())
                .isEqualTo(CallbackContext.WasmNestingException.REASON_DEPTH_EXCEEDED);
    }

    /**
     * A URL already on the chain re-appearing surfaces
     * {@code cycle-detected}. Ensures self-recursion (A → A) is
     * rejected before the callee even loads.
     */
    @Test
    public void wasmCallChain_cycleRejected() {
        final CallbackContext ctx = CallbackContext.bind();
        ctx.enterWasmCall("ipfs://A");
        ctx.enterWasmCall("ipfs://B");

        final Throwable t = catchThrowable(() -> ctx.enterWasmCall("ipfs://A"));
        assertThat(t).isInstanceOf(CallbackContext.WasmNestingException.class);
        final CallbackContext.WasmNestingException wne = (CallbackContext.WasmNestingException) t;
        assertThat(wne.reason())
                .isEqualTo(CallbackContext.WasmNestingException.REASON_CYCLE_DETECTED);
        assertThat(wne.calleeUrl()).isEqualTo("ipfs://A");
        assertThat(wne.chainSnapshot()).containsExactly("ipfs://A", "ipfs://B");
        assertThat(t.getMessage()).contains("cycle detected");
        assertThat(t.getMessage()).contains("ipfs://A");
        // Chain is unchanged on rejection.
        assertThat(ctx.wasmCallChainSnapshot()).containsExactly("ipfs://A", "ipfs://B");
    }

    /**
     * The empty-URL entry (produced by the legacy no-arg overload some
     * tests use to prime nesting state) does NOT participate in cycle
     * detection — otherwise every priming call would collide. Real
     * URL-carrying dispatches still get real cycle checks.
     */
    @Test
    public void wasmCallChain_emptyUrlNotCycleChecked() {
        final CallbackContext ctx = CallbackContext.bind();
        ctx.enterWasmCall();                 // legacy priming, pushes ""
        ctx.enterWasmCall();                 // legacy priming, pushes ""
        // Two empty entries coexist without cycle rejection.
        assertThat(ctx.wasmCallDepth()).isEqualTo(2);
        assertThat(ctx.wasmCallChainSnapshot()).containsExactly("", "");
    }

    /**
     * Java-Proxy Subject stub — the invokerSubject slot on CallbackContext
     * is just a reference holder, so the proxy only needs to answer
     * {@code getPrincipal} for equality-like assertions.
     */
    private static Subject stubSubject(final String principal) {
        return (Subject) Proxy.newProxyInstance(
                Subject.class.getClassLoader(),
                new Class[]{Subject.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getPrincipal":    return principal;
                        case "isAuthenticated": return true;
                        case "isRemembered":    return false;
                        case "checkPermission": return null;
                        case "isPermitted":
                            if (args != null && args.length == 1 && args[0] instanceof Permission) {
                                return Boolean.TRUE;
                            }
                            return Boolean.TRUE;
                        default:
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            if (method.getReturnType() == long.class) return 0L;
                            return null;
                    }
                });
    }
}
