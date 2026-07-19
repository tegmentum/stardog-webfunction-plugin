package ai.tegmentum.stardog.kibble.webfunctions;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded in-memory FIFO ring of {@link CapabilityAuditRow}s — Phase 1
 * diagnostic surface for the capability-policy layer.
 *
 * <p>Sibling of {@link AttributionRing} (fuel-attribution ring); the
 * capability wave uses a separate ring per implementation memo §11 to
 * keep each ring's rows schema-uniform (compliance queries can scan
 * either without instanceof branching, and the Phase 6 disk backing
 * lands the same shape twice — once per ring).
 *
 * <p>Semantics mirror {@link AttributionRing}:
 * <ul>
 *   <li><b>Opt-in.</b> {@link #setEnabled(boolean)} flips the master
 *       gate. Phase 1b wires this from
 *       {@code webfunctions.capability.audit.enabled}; default enabled
 *       per implementation memo §12 (opposite of the fuel ring's
 *       default off — capability audit is load-bearing for the security
 *       story).</li>
 *   <li><b>Zero-cost when disabled.</b> {@link #append} early-returns.</li>
 *   <li><b>Bounded FIFO.</b> Oldest row evicted at capacity.
 *       {@link #setCapacity(int)} rescales; Phase 1b wires from
 *       {@code webfunctions.capability.audit.capacity} (default
 *       100_000 per implementation memo §12 — capability rows outnumber
 *       fuel rows by one order of magnitude since one lands per callback
 *       dispatch, not per invocation).</li>
 *   <li><b>Thread-safe.</b> Serializes on an intrinsic lock — same
 *       pattern as {@link AttributionRing}.</li>
 * </ul>
 *
 * <p>Singleton — {@link #INSTANCE}.
 */
public final class CapabilityAttributionRing {

    /** Default per implementation memo §12. */
    public static final int DEFAULT_CAPACITY = 100_000;

    /** Process-wide instance. Same JVM lifetime as the plugin. */
    public static final CapabilityAttributionRing INSTANCE = new CapabilityAttributionRing();

    private final Object lock = new Object();
    private final Deque<CapabilityAuditRow> ring = new ArrayDeque<>();

    // Enabled + capacity are volatile so the append-hot-path reads them
    // without lock contention. Mutation goes through setters.
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicInteger capacity = new AtomicInteger(DEFAULT_CAPACITY);

    /**
     * Optional durable sink installed at Phase 6 boot. Default
     * {@link NoopAuditSink#INSTANCE}. Volatile — {@link #setSink}
     * may run on the KernelModule install thread while {@link #append}
     * runs on request-path threads.
     */
    private volatile AuditSink sink = NoopAuditSink.INSTANCE;

    private CapabilityAttributionRing() {}

    /**
     * Install the Phase 6 durable audit sink. Pass
     * {@link NoopAuditSink#INSTANCE} to disable. Every subsequent
     * {@link #append} both records in the in-memory ring AND forwards
     * to the sink; the sink's own {@code write} is expected to be
     * non-blocking (the ring's append runs on the request path).
     */
    public void setSink(final AuditSink newSink) {
        this.sink = (newSink == null) ? NoopAuditSink.INSTANCE : newSink;
    }

    /** Currently installed sink — introspection for tests. */
    public AuditSink sink() { return sink; }

    /**
     * Flip the master gate. When {@code false}, {@link #append} is a
     * no-op — row construction is the caller's cost to avoid.
     */
    public void setEnabled(final boolean on) {
        enabled.set(on);
    }

    /** Current enabled state — for tests and audit surfaces. */
    public boolean enabled() {
        return enabled.get();
    }

    /**
     * Update the bounded capacity. Values ≤ 0 fall back to
     * {@link #DEFAULT_CAPACITY} — a policy of "0 rows retained" is
     * indistinguishable from "ring disabled" and callers should use
     * {@link #setEnabled} for that.
     */
    public void setCapacity(final int newCapacity) {
        capacity.set(newCapacity <= 0 ? DEFAULT_CAPACITY : newCapacity);
    }

    /** Current capacity — the append path re-reads this on every call. */
    public int capacity() {
        return capacity.get();
    }

    /**
     * Append a row. No-op when disabled or {@code row == null}.
     * Evicts oldest entries as needed to fit under {@link #capacity()};
     * capacity is re-read each call so a live tuning change is honored
     * on the next append.
     */
    public void append(final CapabilityAuditRow row) {
        if (!enabled.get() || row == null) return;
        final int cap = capacity.get();
        synchronized (lock) {
            while (ring.size() >= cap) {
                ring.pollFirst();
            }
            ring.addLast(row);
        }
    }

    /**
     * Immutable-safe copy of the current ring contents, oldest first.
     * Empty list when the ring is empty or disabled and unwritten.
     */
    public List<CapabilityAuditRow> snapshot() {
        synchronized (lock) {
            return new ArrayList<>(ring);
        }
    }

    /** Reset the ring — for tests that need a clean slate between cases. */
    public void clear() {
        synchronized (lock) {
            ring.clear();
        }
    }

    /** Current row count. O(1). */
    public int size() {
        synchronized (lock) {
            return ring.size();
        }
    }

    // --- Convenience helpers used by CapabilityEnforcer. -----------------

    /**
     * Record a {@link CapabilityAuditRow.Outcome#GRANTED} outcome.
     * Convenience wrapper — no-op when the ring is disabled.
     */
    public static void recordGranted(final String userId,
                                     final String extensionUri,
                                     final String interfaceName,
                                     final String method,
                                     final String argumentsSummary) {
        if (!INSTANCE.enabled.get()) return;
        INSTANCE.append(new CapabilityAuditRow(
                Instant.now(),
                nonNull(userId),
                "",
                nonNull(extensionUri),
                nonNull(interfaceName),
                nonNull(method),
                nonNull(argumentsSummary),
                CapabilityAuditRow.Outcome.GRANTED,
                ""));
    }

    /**
     * Record a {@link CapabilityAuditRow.Outcome#GRANTED_UNDECLARED}
     * outcome — the capability-ask warn-on-undeclared diagnostic
     * ({@code capability-ask.md} §8). The dispatch was permitted by
     * grant but not declared by the extension's ask. Used to catch
     * malicious understated asks and buggy drift. Dispatch still
     * proceeded; the diagnostic is advisory.
     */
    public static void recordGrantedUndeclared(final String userId,
                                               final String extensionUri,
                                               final String interfaceName,
                                               final String method,
                                               final String argumentsSummary) {
        if (!INSTANCE.enabled.get()) return;
        INSTANCE.append(new CapabilityAuditRow(
                Instant.now(),
                nonNull(userId),
                "",
                nonNull(extensionUri),
                nonNull(interfaceName),
                nonNull(method),
                nonNull(argumentsSummary),
                CapabilityAuditRow.Outcome.GRANTED_UNDECLARED,
                ""));
    }

    /**
     * Record a {@link CapabilityAuditRow.Outcome#DENIED} outcome with a
     * discriminator tag from {@link WfCapabilityError.PerCallDenied}'s
     * {@code REASON_*} constants.
     */
    public static void recordDenied(final String userId,
                                    final String extensionUri,
                                    final String interfaceName,
                                    final String method,
                                    final String argumentsSummary,
                                    final String denyReason) {
        if (!INSTANCE.enabled.get()) return;
        INSTANCE.append(new CapabilityAuditRow(
                Instant.now(),
                nonNull(userId),
                "",
                nonNull(extensionUri),
                nonNull(interfaceName),
                nonNull(method),
                nonNull(argumentsSummary),
                CapabilityAuditRow.Outcome.DENIED,
                nonNull(denyReason)));
    }

    private static String nonNull(final String s) {
        return s == null ? "" : s;
    }
}
