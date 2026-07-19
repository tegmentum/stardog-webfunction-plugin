package ai.tegmentum.stardog.kibble.webfunctions;

import com.stardog.stark.Value;
import com.stardog.stark.Values;
import com.stardog.stark.query.SelectQueryResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.Assume.assumeTrue;

/**
 * Fuel-metering Phase 1 attribution ring — bounded in-memory diagnostic
 * buffer for {@code wf:call} invocations.
 *
 * <p>Covers:
 * <ul>
 *   <li>Disabled-flag no-op — the ring stays empty even when a fuel-metered
 *       invocation completes.</li>
 *   <li>Enabled-flag success + trap capture — SUCCESS and
 *       PER_INVOCATION_TRAP rows land through the recording helpers
 *       {@link Call#evaluate} / {@link WebFunctionServiceOperator#computeNext}
 *       call after their wasm invocation resolves.</li>
 *   <li>Bounded FIFO — configured capacity is honored; oldest rows drop
 *       when the ring overflows.</li>
 *   <li>Row shape — the fields we can populate in Phase 1 (timestamp,
 *       extensionUri, outcome) are non-empty; the fields we can't
 *       (userId, orgId, queryId on filter-function path) are "".</li>
 * </ul>
 *
 * <p>Not exercised end-to-end through Stardog boot — the recording surface
 * is the {@link AttributionRing#recordSuccess}/{@link AttributionRing#recordTrap}
 * helpers that both entry points call. Exercising them directly mirrors
 * what {@link Call} does after its wasm invocation while keeping the
 * assertion focused on the ring semantics. The runaway-extension path
 * uses the same wasm fixture {@link TestFuelMetering} does.
 */
public class TestAttributionRing {

    /** Same locator convention as {@link TestFuelMetering}. */
    private static final String COMPONENT_PATH = resolveWasmPath();

    private static String resolveWasmPath() {
        final String env = System.getenv("EXAMPLE_UPPERCASE_WASM");
        if (env != null && !env.isEmpty()) return env;
        return System.getProperty("user.home")
                + "/git/webfunctions/target/wasm32-wasip2/release/example_uppercase_extension.wasm";
    }

    @Before
    public void resetRingAndConfig() {
        AttributionRing.INSTANCE.clear();
        // Explicit false — the default, but stamp it so leaks from prior
        // tests don't accidentally leave the ring enabled.
        System.setProperty(WebFunctionConfig.PROP_ATTRIBUTION_LOG_ENABLED, "false");
    }

    @After
    public void resetAll() {
        AttributionRing.INSTANCE.clear();
        System.clearProperty(WebFunctionConfig.PROP_FUEL_ENABLED);
        System.clearProperty(WebFunctionConfig.PROP_FUEL_PER_INVOCATION_MAX);
        System.clearProperty(WebFunctionConfig.PROP_FUEL_HOST_CALLBACK_TOLL);
        System.clearProperty(WebFunctionConfig.PROP_ATTRIBUTION_LOG_ENABLED);
        System.clearProperty(WebFunctionConfig.PROP_ATTRIBUTION_LOG_CAPACITY);
    }

    /**
     * With the attribution log disabled (Phase 1 default), even a completed
     * invocation writes nothing to the ring. Verifies the append gate on
     * the enabled flag is honored on both helper paths.
     */
    @Test
    public void disabledRingIsNoOp() throws Exception {
        final File wasm = new File(COMPONENT_PATH);
        assumeBuilt(wasm);

        // Fuel enabled — matters for the invocation path — but attribution
        // ring disabled. The recording helpers must no-op.
        System.setProperty(WebFunctionConfig.PROP_FUEL_ENABLED, "true");
        System.setProperty(WebFunctionConfig.PROP_FUEL_PER_INVOCATION_MAX, "10000000");
        System.setProperty(WebFunctionConfig.PROP_ATTRIBUTION_LOG_ENABLED, "false");

        final URL url = wasm.toURI().toURL();
        final CallbackContext ctx = CallbackContext.bind();
        try {
            ctx.setFuelMeteringContext(
                    url.toString(),
                    WebFunctionConfig.fuelPerInvocationMax(),
                    WebFunctionConfig.fuelHostCallbackToll());
            try (StardogWasmInstance instance = new StardogWasmInstance(url)) {
                try (SelectQueryResult rs = instance.evaluate(Values.literal("stardog"))) {
                    // Drain — same as Call.evaluate would.
                    while (rs.hasNext()) rs.next();
                }
            }
            // Mirror what Call.evaluate does on success.
            AttributionRing.recordSuccess(url.toString(), ctx.tollUsed(), "");
            // Same for the trap helper, to prove both are gated.
            AttributionRing.recordTrap(url.toString(),
                    new WfBudgetError.PerInvocationTrap(url.toString(), 42L, 100L),
                    ctx.tollUsed(), "");

            assertThat(AttributionRing.INSTANCE.snapshot())
                    .as("ring must be empty when attribution.enabled=false")
                    .isEmpty();
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /**
     * With the ring enabled and an under-budget invocation, the SUCCESS
     * row lands with the extension URI on it.
     */
    @Test
    public void enabledRingCapturesSuccessRow() throws Exception {
        final File wasm = new File(COMPONENT_PATH);
        assumeBuilt(wasm);

        System.setProperty(WebFunctionConfig.PROP_FUEL_ENABLED, "true");
        System.setProperty(WebFunctionConfig.PROP_FUEL_PER_INVOCATION_MAX, "10000000");
        System.setProperty(WebFunctionConfig.PROP_ATTRIBUTION_LOG_ENABLED, "true");

        final URL url = wasm.toURI().toURL();
        final CallbackContext ctx = CallbackContext.bind();
        try {
            ctx.setFuelMeteringContext(
                    url.toString(),
                    WebFunctionConfig.fuelPerInvocationMax(),
                    WebFunctionConfig.fuelHostCallbackToll());
            try (StardogWasmInstance instance = new StardogWasmInstance(url)) {
                final Value input = Values.literal("stardog");
                try (SelectQueryResult rs = instance.evaluate(input)) {
                    assertThat(rs.hasNext()).isTrue();
                    rs.next();
                }
            }
            AttributionRing.recordSuccess(url.toString(), ctx.tollUsed(), "");

            final List<AttributionRow> rows = AttributionRing.INSTANCE.snapshot();
            assertThat(rows).hasSize(1);
            final AttributionRow row = rows.get(0);
            assertThat(row.outcome()).isEqualTo(AttributionRow.Outcome.SUCCESS);
            assertThat(row.extensionUri()).isEqualTo(url.toString());
            // Phase 1: user/org unknown, filter-function path has no queryId.
            assertThat(row.userId()).isEqualTo("");
            assertThat(row.orgId()).isEqualTo("");
            assertThat(row.queryId()).isEqualTo("");
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /**
     * With the ring enabled and an under-budget cap forcing an OUT_OF_FUEL
     * trap, the PER_INVOCATION_TRAP row lands with the extension URI on it.
     * Mirrors {@link TestFuelMetering#infiniteLoopExtensionTrapsWithPerInvocationError}
     * for the invocation shape.
     */
    @Test
    public void enabledRingCapturesTrapRow() throws Exception {
        final File wasm = new File(COMPONENT_PATH);
        assumeBuilt(wasm);

        System.setProperty(WebFunctionConfig.PROP_FUEL_ENABLED, "true");
        // Tiny cap — same shape as the runaway-loop trap in TestFuelMetering.
        System.setProperty(WebFunctionConfig.PROP_FUEL_PER_INVOCATION_MAX, "100");
        System.setProperty(WebFunctionConfig.PROP_ATTRIBUTION_LOG_ENABLED, "true");

        final URL url = wasm.toURI().toURL();
        final CallbackContext ctx = CallbackContext.bind();
        try {
            ctx.setFuelMeteringContext(
                    url.toString(),
                    WebFunctionConfig.fuelPerInvocationMax(),
                    WebFunctionConfig.fuelHostCallbackToll());
            final Throwable thrown = catchThrowable(() -> {
                try (StardogWasmInstance instance = new StardogWasmInstance(url)) {
                    try (SelectQueryResult ignored = instance.evaluate(Values.literal("stardog"))) {
                        // The invocation is expected to trap; if it doesn't
                        // (test misconfigured), the assertion below catches it.
                    }
                }
            });
            assertThat(thrown).as("expected wasmtime OUT_OF_FUEL trap").isNotNull();
            final WfBudgetError typed = FuelTrapMapper.mapOrNull(thrown, ctx);
            assertThat(typed).as("expected FuelTrapMapper to promote").isNotNull();
            // What Call.evaluate would do on the trap path.
            AttributionRing.recordTrap(url.toString(), typed, ctx.tollUsed(), "");

            final List<AttributionRow> rows = AttributionRing.INSTANCE.snapshot();
            assertThat(rows).hasSize(1);
            final AttributionRow row = rows.get(0);
            assertThat(row.outcome()).isEqualTo(AttributionRow.Outcome.PER_INVOCATION_TRAP);
            assertThat(row.extensionUri()).isEqualTo(url.toString());
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /**
     * A capacity of 5 with 10 appended rows drops the oldest 5; the ring
     * keeps the most recent 5. Doesn't need a wasm fixture — pure ring
     * semantics.
     */
    @Test
    public void ringBoundsToCapacity() {
        System.setProperty(WebFunctionConfig.PROP_ATTRIBUTION_LOG_ENABLED, "true");
        System.setProperty(WebFunctionConfig.PROP_ATTRIBUTION_LOG_CAPACITY, "5");

        for (int i = 0; i < 10; i++) {
            AttributionRing.INSTANCE.append(new AttributionRow(
                    Instant.now(),
                    "",
                    "",
                    "file:///fake/ext-" + i + ".wasm",
                    (long) i,
                    AttributionRow.Outcome.SUCCESS,
                    ""));
        }

        final List<AttributionRow> rows = AttributionRing.INSTANCE.snapshot();
        assertThat(rows).hasSize(5);
        // Oldest-first order — the surviving rows are indices 5..9.
        assertThat(rows.get(0).extensionUri()).isEqualTo("file:///fake/ext-5.wasm");
        assertThat(rows.get(4).extensionUri()).isEqualTo("file:///fake/ext-9.wasm");
        assertThat(rows.get(0).fuelConsumed()).isEqualTo(5L);
        assertThat(rows.get(4).fuelConsumed()).isEqualTo(9L);
    }

    /**
     * A recorded row has the fields Phase 1 can populate; the ones it can't
     * (userId, orgId, queryId on filter-function path) are empty strings,
     * not null. Ring-only test — no wasm fixture.
     */
    @Test
    public void rowFieldsPopulated() {
        System.setProperty(WebFunctionConfig.PROP_ATTRIBUTION_LOG_ENABLED, "true");

        final Instant before = Instant.now();
        AttributionRing.recordSuccess(
                "file:///fake/toUpper.wasm",
                4200L,
                "");

        final List<AttributionRow> rows = AttributionRing.INSTANCE.snapshot();
        assertThat(rows).hasSize(1);
        final AttributionRow row = rows.get(0);

        assertThat(row.extensionUri()).isEqualTo("file:///fake/toUpper.wasm");
        assertThat(row.outcome()).isEqualTo(AttributionRow.Outcome.SUCCESS);
        assertThat(row.fuelConsumed()).isEqualTo(4200L);
        assertThat(row.timestamp())
                .as("timestamp should be captured at record time or later")
                .isAfterOrEqualTo(before);

        // Phase 1: unset, but non-null (record contract).
        assertThat(row.userId()).isEqualTo("");
        assertThat(row.orgId()).isEqualTo("");
        assertThat(row.queryId()).isEqualTo("");
    }

    private static void assumeBuilt(final File wasm) {
        assumeTrue(
                "example-uppercase-extension wasm not built: " + wasm.getAbsolutePath()
                        + " — build it in ~/git/webfunctions or set "
                        + "EXAMPLE_UPPERCASE_WASM to the built component path",
                wasm.exists());
    }
}
