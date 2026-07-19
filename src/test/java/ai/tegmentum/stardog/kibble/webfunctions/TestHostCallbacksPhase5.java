package ai.tegmentum.stardog.kibble.webfunctions;

import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Capability wave Phase 5d — verifies {@link HostCallbacks}'
 * {@code enforceHttpPathCapability} and
 * {@code enforceWasmCalleeCapability} package-private helpers wire the
 * enforcer's Phase 5 checks correctly.
 *
 * <p>Drives the enforcement in isolation from the full wasm dispatch
 * stack: the helpers are package-private specifically so tests can
 * exercise the branches (empty allowlist ⇒ no-op, non-empty +
 * mismatch ⇒ throw, non-empty + match ⇒ silent pass) without
 * bootstrapping wasmtime4j.
 *
 * <p>Confirms the master gate: with
 * {@code webfunctions.capability.enabled=false} (default), Phase 5
 * checks never run — pre-Phase-5 behavior preserved on every unconfigured
 * deployment.
 */
public class TestHostCallbacksPhase5 {

    @Before
    public void setUp() {
        CapabilityAttributionRing.INSTANCE.clear();
        CapabilityAttributionRing.INSTANCE.setEnabled(true);
        CapabilityAttributionRing.INSTANCE.setCapacity(
                CapabilityAttributionRing.DEFAULT_CAPACITY);
        CapabilityEnforcer.install(CapabilityEnforcer.create());
        CapabilityEnforcer.setEnabled(true);
        ThreadContext.unbindSubject();
    }

    @After
    public void tearDown() {
        CapabilityEnforcer.install(null);
        CapabilityEnforcer.setEnabled(false);
        CapabilityAttributionRing.INSTANCE.clear();
        ThreadContext.unbindSubject();
        final CallbackContext ctx = CallbackContext.current();
        if (ctx != null) CallbackContext.unbindIfOutermost(ctx);
    }

    @Test
    public void capabilityDisabledSkipsHttpPathCheckEntirely() {
        // Master gate off — enforceHttpPathCapability short-circuits at
        // the activePolicy() check, no throw regardless of allowlist
        // shape.
        CapabilityEnforcer.setEnabled(false);
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(grantWithPaths(
                new HttpPathAllowlist(List.of("api.acme.com/public/"))));
        HostCallbacks.enforceHttpPathCapability(
                ctx, "http-get", "https://api.evil.com/anything");
        // No audit row written (ring rows only appear when enforcer runs).
        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
    }

    @Test
    public void capabilityDisabledSkipsWasmCalleeCheckEntirely() {
        CapabilityEnforcer.setEnabled(false);
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(grantWithCallees(
                WasmCalleeAllowlist.of(List.of("ipfs://QmCallee"))));
        HostCallbacks.enforceWasmCalleeCapability(
                ctx, "invoke-wasm", "ipfs://QmEvil");
        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
    }

    @Test
    public void httpPathEmptyAllowlistImposesNoRestriction() {
        // Empty allowlist ⇒ no restriction beyond coarser interface +
        // method + host checks. Backward-compat guarantee for policies
        // that grant cap:allowInterface cap:HttpCallbacks without
        // cap:allowHttpPath triples.
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(grantWithPaths(HttpPathAllowlist.ALLOW_NONE));
        HostCallbacks.enforceHttpPathCapability(
                ctx, "http-get", "https://api.evil.com/anything");
        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
    }

    @Test
    public void httpPathNonEmptyAllowlistAcceptsMatchingPrefix() {
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(grantWithPaths(
                new HttpPathAllowlist(List.of("api.acme.com/public/"))));
        HostCallbacks.enforceHttpPathCapability(
                ctx, "http-get", "https://api.acme.com/public/orders");
        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
    }

    @Test
    public void httpPathNonEmptyAllowlistDeniesOnPathMismatch() {
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(grantWithPaths(
                new HttpPathAllowlist(List.of("api.acme.com/public/"))));
        final Throwable thrown = catchThrowable(() ->
                HostCallbacks.enforceHttpPathCapability(
                        ctx, "http-get", "https://api.acme.com/private/secrets"));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        assertThat(((WfCapabilityError.PerCallDenied) thrown).reason())
                .isEqualTo(WfCapabilityError.PerCallDenied.REASON_HTTP_PATH_DENIED);
    }

    @Test
    public void wasmCalleeEmptyAllowlistImposesNoRestriction() {
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(grantWithCallees(WasmCalleeAllowlist.ALLOW_NONE));
        HostCallbacks.enforceWasmCalleeCapability(
                ctx, "invoke-wasm", "ipfs://QmAny");
        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
    }

    @Test
    public void wasmCalleeNonEmptyAllowlistAcceptsExactMatch() {
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(grantWithCallees(
                WasmCalleeAllowlist.of(List.of(
                        "ipfs://QmCallee",
                        "https://reg.example.org/plugin.wasm"))));
        HostCallbacks.enforceWasmCalleeCapability(
                ctx, "invoke-wasm", "ipfs://QmCallee");
        HostCallbacks.enforceWasmCalleeCapability(
                ctx, "invoke-wasm-service", "https://reg.example.org/plugin.wasm");
        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
    }

    @Test
    public void wasmCalleeNonEmptyAllowlistDeniesOnMismatch() {
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(grantWithCallees(
                WasmCalleeAllowlist.of(List.of("ipfs://QmCallee"))));
        final Throwable thrown = catchThrowable(() ->
                HostCallbacks.enforceWasmCalleeCapability(
                        ctx, "invoke-wasm-service", "ipfs://QmEvil"));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        final WfCapabilityError.PerCallDenied err =
                (WfCapabilityError.PerCallDenied) thrown;
        assertThat(err.reason()).isEqualTo(
                WfCapabilityError.PerCallDenied.REASON_WASM_CALLEE_DENIED);
        assertThat(err.interfaceName()).isEqualTo("wasm-callbacks");
        assertThat(err.method()).isEqualTo("invoke-wasm-service");
        assertThat(err.argumentsSummary()).isEqualTo("ipfs://QmEvil");
    }

    @Test
    public void nullCtxIsNoOp() {
        // Defensive path — null ctx (isolated unit-test / direct-
        // instantiation flow) short-circuits before touching the enforcer.
        HostCallbacks.enforceHttpPathCapability(
                null, "http-get", "https://any/path");
        HostCallbacks.enforceWasmCalleeCapability(
                null, "invoke-wasm", "ipfs://QmAny");
        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
    }

    @Test
    public void ctxWithoutGrantIsNoOp() {
        // Ctx bound but no grant stamped — the enforcer's null-grant
        // guard on enforceHttpPath / enforceWasmCallee prevents an NPE
        // and returns silently.
        final CallbackContext ctx = CallbackContext.bind();
        assertThat(ctx.capabilityGrant()).isEmpty();
        HostCallbacks.enforceHttpPathCapability(
                ctx, "http-get", "https://any/path");
        HostCallbacks.enforceWasmCalleeCapability(
                ctx, "invoke-wasm", "ipfs://QmAny");
        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
    }

    /** Grant with only the httpPath allowlist configured to the given value. */
    private static CapabilityGrant grantWithPaths(final HttpPathAllowlist paths) {
        return new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("http-callbacks"),
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                paths,
                WasmCalleeAllowlist.ALLOW_NONE,
                "alice",
                CapabilityModel.INVOKER_SUBJECT);
    }

    /** Grant with only the wasmCallee allowlist configured to the given value. */
    private static CapabilityGrant grantWithCallees(final WasmCalleeAllowlist callees) {
        return new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("wasm-callbacks"),
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                HttpPathAllowlist.ALLOW_NONE,
                callees,
                "alice",
                CapabilityModel.INVOKER_SUBJECT);
    }
}
