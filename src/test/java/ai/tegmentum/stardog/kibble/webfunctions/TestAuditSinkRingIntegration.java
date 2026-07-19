package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6d — the rings forward every appended row to their installed
 * sink in addition to the in-memory ring, and reverting the sink to
 * {@link NoopAuditSink} restores pre-Phase-6 behavior exactly.
 *
 * <p>Uses a fake capturing sink rather than the real file-backed sink so
 * assertions are synchronous and no filesystem I/O is required — the
 * on-disk write path is covered by
 * {@link TestNdjsonRotatingFileAuditSink}. The two coverages meet here:
 * the ring's forward step, the sink's write shape.
 */
public class TestAuditSinkRingIntegration {

    /**
     * Capturing {@link AuditSink} — records every row written and
     * exposes it for assertions. Also counts flush / close calls so the
     * ring's contract on close-idempotency can be observed.
     */
    static final class CapturingSink implements AuditSink {
        final List<AuditRow> writes = new CopyOnWriteArrayList<>();
        int flushCount = 0;
        int closeCount = 0;
        @Override public void write(final AuditRow row) { writes.add(row); }
        @Override public void flush() { flushCount++; }
        @Override public void close() { closeCount++; }
    }

    @Before
    public void resetRingsAndConfig() {
        AttributionRing.INSTANCE.clear();
        AttributionRing.INSTANCE.setSink(NoopAuditSink.INSTANCE);
        CapabilityAttributionRing.INSTANCE.clear();
        CapabilityAttributionRing.INSTANCE.setSink(NoopAuditSink.INSTANCE);
        CapabilityAttributionRing.INSTANCE.setEnabled(true);
        CapabilityAttributionRing.INSTANCE.setCapacity(
                CapabilityAttributionRing.DEFAULT_CAPACITY);
        System.setProperty(WebFunctionConfig.PROP_ATTRIBUTION_LOG_ENABLED, "true");
        System.setProperty(WebFunctionConfig.PROP_ATTRIBUTION_LOG_CAPACITY, "10000");
    }

    @After
    public void resetAll() {
        AttributionRing.INSTANCE.clear();
        AttributionRing.INSTANCE.setSink(NoopAuditSink.INSTANCE);
        CapabilityAttributionRing.INSTANCE.clear();
        CapabilityAttributionRing.INSTANCE.setSink(NoopAuditSink.INSTANCE);
        System.clearProperty(WebFunctionConfig.PROP_ATTRIBUTION_LOG_ENABLED);
        System.clearProperty(WebFunctionConfig.PROP_ATTRIBUTION_LOG_CAPACITY);
    }

    // ---- Fuel ring ------------------------------------------------------

    @Test
    public void fuelRingForwardsEveryAppendToSink() {
        final CapturingSink sink = new CapturingSink();
        AttributionRing.INSTANCE.setSink(sink);

        final List<AttributionRow> submitted = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            final AttributionRow row = new AttributionRow(
                    Instant.now(), "u-" + i, "", "file:///ext-" + i + ".wasm",
                    (long) i, AttributionRow.Outcome.SUCCESS, "");
            submitted.add(row);
            AttributionRing.INSTANCE.append(row);
        }

        // In-memory ring behavior unchanged — snapshot() returns the same
        // rows in the same order.
        assertThat(AttributionRing.INSTANCE.snapshot())
                .containsExactlyElementsOf(submitted);
        // Sink saw every row.
        assertThat(sink.writes).containsExactlyElementsOf(submitted);
    }

    @Test
    public void fuelRingNoopSinkPreservesBehavior() {
        // Sink defaults to NoopAuditSink — this is the pre-Phase-6 state.
        assertThat(AttributionRing.INSTANCE.sink()).isSameAs(NoopAuditSink.INSTANCE);
        for (int i = 0; i < 3; i++) {
            AttributionRing.INSTANCE.append(new AttributionRow(
                    Instant.now(), "", "", "file:///x-" + i + ".wasm",
                    0L, AttributionRow.Outcome.SUCCESS, ""));
        }
        assertThat(AttributionRing.INSTANCE.snapshot()).hasSize(3);
    }

    @Test
    public void fuelRingDisabledSkipsSinkToo() {
        System.setProperty(WebFunctionConfig.PROP_ATTRIBUTION_LOG_ENABLED, "false");
        final CapturingSink sink = new CapturingSink();
        AttributionRing.INSTANCE.setSink(sink);

        AttributionRing.INSTANCE.append(new AttributionRow(
                Instant.now(), "", "", "file:///gated.wasm",
                0L, AttributionRow.Outcome.SUCCESS, ""));
        // Neither the ring nor the sink saw the row — the enabled gate
        // short-circuits before both.
        assertThat(AttributionRing.INSTANCE.snapshot()).isEmpty();
        assertThat(sink.writes).isEmpty();
    }

    // ---- Capability ring -----------------------------------------------

    @Test
    public void capabilityRingForwardsEveryAppendToSink() {
        final CapturingSink sink = new CapturingSink();
        CapabilityAttributionRing.INSTANCE.setSink(sink);

        final List<CapabilityAuditRow> submitted = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final CapabilityAuditRow row = new CapabilityAuditRow(
                    Instant.now(), "u-" + i, "", "file:///ext.wasm",
                    "if", "m", "arg-" + i,
                    CapabilityAuditRow.Outcome.GRANTED, "");
            submitted.add(row);
            CapabilityAttributionRing.INSTANCE.append(row);
        }
        assertThat(CapabilityAttributionRing.INSTANCE.snapshot())
                .containsExactlyElementsOf(submitted);
        assertThat(sink.writes).containsExactlyElementsOf(submitted);
    }

    @Test
    public void capabilityRingDisabledSkipsSinkToo() {
        CapabilityAttributionRing.INSTANCE.setEnabled(false);
        final CapturingSink sink = new CapturingSink();
        CapabilityAttributionRing.INSTANCE.setSink(sink);

        CapabilityAttributionRing.INSTANCE.append(new CapabilityAuditRow(
                Instant.now(), "", "", "file:///gated.wasm",
                "if", "m", "", CapabilityAuditRow.Outcome.GRANTED, ""));
        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
        assertThat(sink.writes).isEmpty();
    }

    // ---- Setter contract -----------------------------------------------

    @Test
    public void setSinkNullReverts_toNoop() {
        AttributionRing.INSTANCE.setSink(null);
        assertThat(AttributionRing.INSTANCE.sink()).isSameAs(NoopAuditSink.INSTANCE);
        CapabilityAttributionRing.INSTANCE.setSink(null);
        assertThat(CapabilityAttributionRing.INSTANCE.sink()).isSameAs(NoopAuditSink.INSTANCE);
    }

    @Test
    public void ringSwapFromRealSinkBackToNoopRestoresPreviousShape() {
        final CapturingSink sink = new CapturingSink();
        AttributionRing.INSTANCE.setSink(sink);
        AttributionRing.INSTANCE.append(new AttributionRow(
                Instant.now(), "", "", "file:///a.wasm",
                0L, AttributionRow.Outcome.SUCCESS, ""));
        // Now revert to noop — subsequent appends only land in memory.
        AttributionRing.INSTANCE.setSink(NoopAuditSink.INSTANCE);
        AttributionRing.INSTANCE.append(new AttributionRow(
                Instant.now(), "", "", "file:///b.wasm",
                0L, AttributionRow.Outcome.SUCCESS, ""));
        assertThat(sink.writes).hasSize(1); // only the first one
        assertThat(AttributionRing.INSTANCE.snapshot()).hasSize(2); // both
    }
}
