package ai.tegmentum.stardog.kibble.webfunctions;

import com.stardog.stark.Value;
import com.stardog.stark.Values;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.Assume.assumeTrue;

/**
 * Fuel-metering Phase 1 tests — defensive-only layer.
 *
 * <p>Covers §8 Phase 1 of {@code fuel-implementation.md}:
 * per-invocation fuel cap → {@code WF_PER_INVOCATION_TRAP}, host-callback
 * toll → {@code WF_HOST_CALLBACK_TOLL_EXHAUSTED}, feature-flag off
 * → back-compat with pre-Phase-1 behavior. Phase-2+ tests
 * (TestUserQuotaAccumulation, TestOrgRollup, TestUserRateLimitBurst, etc.)
 * land alongside those phases.
 *
 * <p>Uses the direct-instantiation pattern from {@link TestComponentMode}
 * (no Stardog boot required) plus a unit test on
 * {@link CallbackContext#chargeToll} for toll exhaustion, since the
 * component-under-test needs an active wf:call frame to test end-to-end
 * and we do not ship a callback-spam wasm fixture at Phase 1.
 */
public class TestFuelMetering {

    /** Same locator convention as {@link TestComponentMode}. */
    private static final String COMPONENT_PATH = resolveWasmPath();

    private static String resolveWasmPath() {
        final String env = System.getenv("EXAMPLE_UPPERCASE_WASM");
        if (env != null && !env.isEmpty()) return env;
        return System.getProperty("user.home")
                + "/git/webfunctions/target/wasm32-wasip2/release/example_uppercase_extension.wasm";
    }

    @After
    public void resetSystemProperties() {
        System.clearProperty(WebFunctionConfig.PROP_FUEL_ENABLED);
        System.clearProperty(WebFunctionConfig.PROP_FUEL_PER_INVOCATION_MAX);
        System.clearProperty(WebFunctionConfig.PROP_FUEL_HOST_CALLBACK_TOLL);
    }

    /**
     * WF_PER_INVOCATION_TRAP surfaces when a component invocation exceeds
     * its per-invocation fuel cap. Uses a deliberately tiny cap (100 fuel
     * units) to force even a well-behaved uppercase extension to trap on
     * its first few instructions — the "runaway loop" case reduces to the
     * same code path (the guest hits the cap; wasmtime traps OUT_OF_FUEL;
     * FuelTrapMapper promotes to WfBudgetError.PerInvocationTrap).
     */
    @Test
    public void infiniteLoopExtensionTrapsWithPerInvocationError() throws Exception {
        final File wasm = new File(COMPONENT_PATH);
        assumeBuilt(wasm);
        System.setProperty(WebFunctionConfig.PROP_FUEL_ENABLED, "true");
        // Tiny cap — even the small uppercase extension's initializer +
        // exports run over 100 fuel units, so this drives the same
        // OUT_OF_FUEL trap the strategy memo's "runaway loop" case would.
        // Trap surfaces at instantiate (component's start section runs
        // pre-first-export) — same code path the outer Call.evaluate
        // `catch (RuntimeException ex)` block promotes.
        System.setProperty(WebFunctionConfig.PROP_FUEL_PER_INVOCATION_MAX, "100");

        final URL url = wasm.toURI().toURL();
        // Simulate what Call.evaluate would do — stamp the CallbackContext
        // with fuel metering state before invoking. Direct-instantiation
        // tests don't go through Call.evaluate, so exercise the
        // FuelTrapMapper by threading its inputs manually.
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setFuelMeteringContext(
                url.toString(),
                WebFunctionConfig.fuelPerInvocationMax(),
                WebFunctionConfig.fuelHostCallbackToll());
        try {
            final Throwable thrown = catchThrowable(() -> {
                try (StardogWasmInstance instance = new StardogWasmInstance(url)) {
                    try (SelectQueryResult ignored = instance.evaluate(Values.literal("stardog"))) {
                        // Fallthrough: the test-oracle is that the invocation traps.
                    }
                }
            });
            assertThat(thrown)
                    .as("expected wasmtime to trap on fuel exhaustion")
                    .isNotNull();
            final WfBudgetError typed = FuelTrapMapper.mapOrNull(thrown, ctx);
            assertThat(typed)
                    .as("expected OUT_OF_FUEL trap to promote to WF_PER_INVOCATION_TRAP; got: "
                            + thrown)
                    .isNotNull();
            assertThat(typed).isInstanceOf(WfBudgetError.PerInvocationTrap.class);
            final WfBudgetError.PerInvocationTrap trap = (WfBudgetError.PerInvocationTrap) typed;
            assertThat(trap.errorCode()).isEqualTo("WF_PER_INVOCATION_TRAP");
            assertThat(trap.extensionUri()).isEqualTo(url.toString());
            assertThat(trap.perInvocationMax()).isEqualTo(100L);
            assertThat(trap.jsonPayload()).contains("\"error_code\":\"WF_PER_INVOCATION_TRAP\"");
            assertThat(trap.jsonPayload()).contains("\"per_invocation_max\":100");
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /**
     * Extension that runs well within the per-invocation budget completes
     * successfully — fuel metering does not corrupt normal flow.
     */
    @Test
    public void extensionUnderBudgetSucceeds() throws Exception {
        final File wasm = new File(COMPONENT_PATH);
        assumeBuilt(wasm);
        System.setProperty(WebFunctionConfig.PROP_FUEL_ENABLED, "true");
        System.setProperty(WebFunctionConfig.PROP_FUEL_PER_INVOCATION_MAX, "10000000");

        final URL url = wasm.toURI().toURL();
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setFuelMeteringContext(
                url.toString(),
                WebFunctionConfig.fuelPerInvocationMax(),
                WebFunctionConfig.fuelHostCallbackToll());
        try (StardogWasmInstance instance = new StardogWasmInstance(url)) {
            final Value input = Values.literal("stardog");
            try (SelectQueryResult rs = instance.evaluate(input)) {
                assertThat(rs.hasNext()).isTrue();
                final Value val = rs.next().value("value_0").orElseThrow();
                assertThat(val.toString()).contains("STARDOG");
            }
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /**
     * Toll exhaustion trip — verifies the {@link CallbackContext#chargeToll}
     * accounting throws {@link WfBudgetError.HostCallbackTollExhausted}
     * naming the specific callback name that ran out, with correct
     * attribution + JSON payload shape. Unit test — doesn't need a wasm
     * fixture since the toll deduction happens on the Java side.
     */
    @Test
    public void tollExhaustionNamesCallback() {
        System.setProperty(WebFunctionConfig.PROP_FUEL_ENABLED, "true");
        System.setProperty(WebFunctionConfig.PROP_FUEL_PER_INVOCATION_MAX, "3500");
        System.setProperty(WebFunctionConfig.PROP_FUEL_HOST_CALLBACK_TOLL, "1000");

        final CallbackContext ctx = CallbackContext.bind();
        ctx.setFuelMeteringContext(
                "file:///fake/callback-spam.wasm",
                WebFunctionConfig.fuelPerInvocationMax(),
                WebFunctionConfig.fuelHostCallbackToll());
        try {
            // Three tolls fit inside 3500 (1000+1000+1000 = 3000).
            ctx.chargeToll("graph-callbacks.execute-query");
            ctx.chargeToll("graph-callbacks.execute-query");
            ctx.chargeToll("graph-callbacks.execute-query");
            assertThat(ctx.tollUsed()).isEqualTo(3000L);

            // Fourth toll pushes to 4000, over the 3500 cap — the specific
            // callback name that runs out is on the typed error.
            final Throwable thrown = catchThrowable(() ->
                    ctx.chargeToll("graph-callbacks.execute-update"));
            assertThat(thrown).isInstanceOf(WfBudgetError.HostCallbackTollExhausted.class);
            final WfBudgetError.HostCallbackTollExhausted e =
                    (WfBudgetError.HostCallbackTollExhausted) thrown;
            assertThat(e.errorCode()).isEqualTo("WF_HOST_CALLBACK_TOLL_EXHAUSTED");
            assertThat(e.callbackName()).isEqualTo("graph-callbacks.execute-update");
            assertThat(e.extensionUri()).isEqualTo("file:///fake/callback-spam.wasm");
            assertThat(e.perInvocationMax()).isEqualTo(3500L);
            assertThat(e.hostCallbackToll()).isEqualTo(1000L);
            assertThat(e.jsonPayload())
                    .contains("\"error_code\":\"WF_HOST_CALLBACK_TOLL_EXHAUSTED\"")
                    .contains("\"callback_name\":\"graph-callbacks.execute-update\"")
                    .contains("\"per_invocation_max\":3500")
                    .contains("\"host_callback_toll\":1000");
            assertThat(ctx.tollExhaustedCallback())
                    .isEqualTo("graph-callbacks.execute-update");

            // Verify the FuelTrapMapper agrees on the promotion — if a
            // subsequent OUT_OF_FUEL trap arrives from the guest side after
            // the toll trip, the mapper still promotes to the toll variant
            // (sticky flag semantics).
            final WfBudgetError mapped = FuelTrapMapper.mapOrNull(
                    new RuntimeException("[OUT_OF_FUEL] wasm trap"), ctx);
            assertThat(mapped).isInstanceOf(WfBudgetError.HostCallbackTollExhausted.class);
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /**
     * With fuel.enabled=false (Phase-1 default), the plugin behaves as it
     * did pre-Phase-1: no per-invocation cap forced on the ComponentConfig
     * beyond whatever legacy {@code webfunctions.fuel.limit} says, no
     * typed WF_* mapping. The uppercase extension runs to completion, and
     * the mapper reports nothing to promote.
     */
    @Test
    public void fuelDisabledPreservesLegacyBehavior() throws Exception {
        final File wasm = new File(COMPONENT_PATH);
        assumeBuilt(wasm);
        // Explicit false — the default, but stamp it so the test is
        // resilient to a JVM that inherited fuel.enabled=true from
        // another test's misconfigured leak.
        System.setProperty(WebFunctionConfig.PROP_FUEL_ENABLED, "false");

        final URL url = wasm.toURI().toURL();
        try (StardogWasmInstance instance = new StardogWasmInstance(url)) {
            final Value input = Values.literal("stardog");
            try (SelectQueryResult rs = instance.evaluate(input)) {
                assertThat(rs.hasNext()).isTrue();
                final Value val = rs.next().value("value_0").orElseThrow();
                assertThat(val.toString()).contains("STARDOG");
            }
        }

        // And the mapper is a no-op even for an OUT_OF_FUEL-shaped
        // exception when fuel is disabled — the mapper honors the
        // opt-in feature flag.
        final WfBudgetError mapped = FuelTrapMapper.mapOrNull(
                new RuntimeException("[OUT_OF_FUEL] wasm trap"), null);
        assertThat(mapped).isNull();
    }

    private static void assumeBuilt(final File wasm) {
        assumeTrue(
                "example-uppercase-extension wasm not built: " + wasm.getAbsolutePath()
                        + " — build it in ~/git/webfunctions or set "
                        + "EXAMPLE_UPPERCASE_WASM to the built component path",
                wasm.exists());
    }
}
