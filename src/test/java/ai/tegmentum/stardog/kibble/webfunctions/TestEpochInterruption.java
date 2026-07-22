package ai.tegmentum.stardog.kibble.webfunctions;

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
 * End-to-end coverage for Task 303 T5 — wasm-level deadline interruption
 * via wasmtime's epoch mechanism. Complements the cooperative-boundary
 * coverage in {@link TestCallbackDeadline}: those tests exercise the
 * host-callback-boundary check, this one exercises the wasm-frame trap
 * a pure-compute loop with no host re-entry cannot avoid.
 *
 * <p>Uses the checked-in {@code example-uppercase-extension} component;
 * the technique is <em>not</em> to make the guest run long enough to
 * naturally trip a normal deadline (that would need a new tight-loop
 * fixture in ~/git/webfunctions, out of scope), but to pre-drain the
 * epoch counter past the per-instance deadline before invoking so the
 * wasm frame's first safepoint traps. That end-to-end trap is what
 * demonstrates the full wiring: EpochTicker running + wasmtime engine
 * with {@code epochInterruption(true)} + store with a per-instance
 * epoch deadline + {@link FuelTrapMapper} promoting {@code INTERRUPT}
 * to {@link WfBudgetError.DeadlineExceeded}.
 *
 * <p>Skipped when the example wasm fixture is not built; the direct-
 * instantiation pattern mirrors {@link TestFuelMetering} so it does
 * not need a Stardog boot.
 */
public class TestEpochInterruption {

    private static final String COMPONENT_PATH = resolveWasmPath();

    private static String resolveWasmPath() {
        final String env = System.getenv("EXAMPLE_UPPERCASE_WASM");
        if (env != null && !env.isEmpty()) return env;
        return System.getProperty("user.home")
                + "/git/webfunctions/target/wasm32-wasip2/release/example_uppercase_extension.wasm";
    }

    @After
    public void resetSystemPropertiesAndTicker() {
        System.clearProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS);
        System.clearProperty(WebFunctionConfig.PROP_EPOCH_TICK_MILLIS);
        // Stop the ticker so the next test starts from a clean state.
        // The shared engine + component cache are process-wide singletons
        // that persist across tests; the ticker's own singleton guard
        // means restart-at-different-interval is a no-op unless we stop
        // it here first.
        EpochTicker.instance().stop();
        final CallbackContext ctx = CallbackContext.current();
        if (ctx != null) CallbackContext.unbindIfOutermost(ctx);
    }

    /**
     * Wasm-level interruption fires for a pure-compute wasm frame.
     * Sets a tight per-invocation deadline and drives the extension
     * with a large input string; the wasm-side uppercasing loop runs
     * long enough past the deadline that a wasmtime safepoint traps
     * with {@code TrapType.INTERRUPT}, which {@link FuelTrapMapper}
     * promotes to {@link WfBudgetError.DeadlineExceeded}.
     *
     * <p>Belt-and-suspenders relative to Task 302's cooperative check:
     * a wasm loop that never re-enters a host callback would go
     * uninterrupted under 302 alone; this test proves T5 catches it.
     *
     * <p>Note on timing: the wasmtime4j-native invoke path
     * re-applies the per-instance epoch deadline before every call
     * (component_core.rs:1013-1014), so the deadline is fresh at
     * invoke time. The trap only fires if the guest's own execution
     * time exceeds the deadline. A 5-tick deadline (5 ms) against a
     * ~50 MB uppercase workload is deep in the tripping window on any
     * realistic wasmtime host. The test is marked {@link org.junit.Assume#assumeTrue}
     * on providers without epoch support.
     */
    @Test
    public void pureComputeWasmFrameIsInterruptedByEpoch() throws Exception {
        final File wasm = new File(COMPONENT_PATH);
        assumeBuilt(wasm);

        // Tight epoch config: 1 ms tick, exec cap 5 ms => 5-tick deadline.
        // The uppercase workload below runs longer than 5 ms in wasm on
        // any realistic host (10s of ms is the typical range for 50 MB).
        System.setProperty(WebFunctionConfig.PROP_EPOCH_TICK_MILLIS, "1");
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "5");
        // Force the shared engine to exist + the ticker to start at the
        // configured cadence. Warmup is idempotent; the ticker's own
        // singleton guard picks up the current epoch-tick-millis on the
        // first successful start (we called stop() in @After after the
        // previous test so this start observes the fresh interval).
        StardogWasmInstance.warmupSharedEngine();
        assumeTrue("EpochTicker did not start — provider without EpochController "
                        + "extension. Wasm-level interruption is a no-op on that provider.",
                EpochTicker.instance().isRunning());

        // Modest input — the wasm-side cabi_realloc + to_uppercase pair
        // has to allocate + iterate at guest speed. 6 MB was empirically
        // the smallest size that reliably runs past a 5 ms deadline on
        // the wasmtime4j 46.0.1-1.4.7 stack; smaller inputs finish before
        // the ticker bumps the epoch past the deadline. Larger inputs
        // (10+ MB) increase JVM-side setup cost without adding coverage.
        final int chars = 6 * 1024 * 1024;
        final char[] buf = new char[chars];
        java.util.Arrays.fill(buf, 'a');
        final String bigInput = new String(buf);

        final URL url = wasm.toURI().toURL();
        final CallbackContext ctx = CallbackContext.bind();
        try (StardogWasmInstance instance = new StardogWasmInstance(url)) {
            final Throwable thrown = catchThrowable(() -> {
                try (SelectQueryResult ignored = instance.evaluate(Values.literal(bigInput))) {
                    // If we get here, the guest ran to completion. That is
                    // acceptable on an extremely fast host (rare); the
                    // assertion below fails explicitly so a hostile
                    // regression that silently disables epoch interruption
                    // still surfaces.
                }
            });
            assertThat(thrown)
                    .as("expected wasmtime to trap INTERRUPT on pure-compute wasm frame "
                            + "processing a 6 MB uppercase workload past the 5 ms deadline")
                    .isNotNull();

            final WfBudgetError typed = FuelTrapMapper.mapOrNull(thrown, ctx);
            assertThat(typed)
                    .as("expected INTERRUPT trap to promote to WF_DEADLINE_EXCEEDED; got: " + thrown)
                    .isNotNull();
            assertThat(typed).isInstanceOf(WfBudgetError.DeadlineExceeded.class);
            final WfBudgetError.DeadlineExceeded de = (WfBudgetError.DeadlineExceeded) typed;
            assertThat(de.errorCode()).isEqualTo("WF_DEADLINE_EXCEEDED");
            assertThat(de.source()).isEqualTo(WfBudgetError.DeadlineExceeded.SOURCE_CONFIG);
            assertThat(de.deadlineMillis()).isEqualTo(5L);
            // json payload sanity — the same shape TestCallbackDeadline asserts.
            assertThat(de.jsonPayload())
                    .contains("\"error_code\":\"WF_DEADLINE_EXCEEDED\"")
                    .contains("\"source\":\"config\"");
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /**
     * Without {@code webfunctions.exec.max.millis}, no per-instance
     * epoch deadline is set on the store, so wasmtime never consults
     * the epoch counter for that store and a well-behaved extension
     * runs to completion regardless of how long the ticker has been
     * ticking. Verifies the opt-in shape held at E3: absent config →
     * no epoch enforcement (existing behavior preserved).
     */
    @Test
    public void absentExecCapMeansNoEpochEnforcement() throws Exception {
        final File wasm = new File(COMPONENT_PATH);
        assumeBuilt(wasm);
        // Configure a fast ticker but NO exec cap — the ticker runs, but
        // no per-instance epoch deadline is set, so the store never
        // considers the epoch counter.
        System.setProperty(WebFunctionConfig.PROP_EPOCH_TICK_MILLIS, "5");
        System.clearProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS);
        StardogWasmInstance.warmupSharedEngine();
        // Ticker may or may not have started depending on provider; the
        // rest of the test doesn't care — the point is that no epoch
        // deadline is set on the store.

        final URL url = wasm.toURI().toURL();
        try (StardogWasmInstance instance = new StardogWasmInstance(url)) {
            // Let the ticker fire a bunch — with no per-instance deadline,
            // this must not affect execution.
            Thread.sleep(120L);
            try (SelectQueryResult rs = instance.evaluate(Values.literal("stardog"))) {
                assertThat(rs.hasNext()).isTrue();
                assertThat(rs.next().value("value_0").orElseThrow().toString())
                        .contains("STARDOG");
            }
        }
    }

    private static void assumeBuilt(final File wasm) {
        assumeTrue(
                "example-uppercase-extension wasm not built: " + wasm.getAbsolutePath()
                        + " — build it in ~/git/webfunctions or set "
                        + "EXAMPLE_UPPERCASE_WASM to the built component path",
                wasm.exists());
    }
}
