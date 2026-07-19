package ai.tegmentum.stardog.kibble.webfunctions;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded in-memory FIFO ring of {@link AttributionRow}s — Phase 1
 * diagnostic surface for fuel metering.
 *
 * <p>Not the disk-backed compliance audit trail (that's Phase 6 —
 * {@code webfunctions.fuel.attribution-log.path}). This ring is:
 *
 * <ul>
 *   <li><b>Opt-in.</b> Off by default even when
 *       {@link WebFunctionConfig#fuelEnabled()} is on — flip
 *       {@link WebFunctionConfig#PROP_ATTRIBUTION_LOG_ENABLED} to
 *       {@code true} to capture rows.</li>
 *   <li><b>Zero-cost when disabled.</b> {@link #append(AttributionRow)}
 *       returns immediately if the enabled flag is false; row construction
 *       is the caller's cost to avoid, which it can also short-circuit on
 *       the same flag.</li>
 *   <li><b>Bounded FIFO.</b> Oldest row evicted when the ring is at
 *       capacity ({@link WebFunctionConfig#attributionLogCapacity()},
 *       default 10 000). Capacity is re-read on each append so tuning via
 *       {@link System#setProperty} takes effect without restart.</li>
 *   <li><b>Thread-safe.</b> All mutation and snapshot operations serialize
 *       on an intrinsic lock. Append is O(1) amortized; snapshot is O(n).
 *       The invocation path already synchronizes on the wasm instance, so
 *       the lock contention introduced here is negligible.</li>
 * </ul>
 *
 * <p>Singleton — {@link #INSTANCE}. Static access mirrors the pattern
 * used by other Phase 1 error surfaces ({@link FuelTrapMapper},
 * {@link CallbackContext#current}).
 */
public final class AttributionRing {

    /** Process-wide instance. Same JVM lifetime as the plugin. */
    public static final AttributionRing INSTANCE = new AttributionRing();

    private final Object lock = new Object();
    /** Backing storage — head is the oldest row, tail is the newest. */
    private final Deque<AttributionRow> ring = new ArrayDeque<>();

    /**
     * Optional durable sink installed at Phase 6 boot. Default
     * {@link NoopAuditSink#INSTANCE}. Volatile because {@link #setSink}
     * may run on the KernelModule install thread while {@link #append}
     * runs on request-path threads — publish must be visible.
     */
    private volatile AuditSink sink = NoopAuditSink.INSTANCE;

    private AttributionRing() {}

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
     * Append a row to the ring. No-op when
     * {@link WebFunctionConfig#attributionLogEnabled()} returns false.
     *
     * <p>Capacity is read on each call so a live config change is honored
     * on the next append. When adding would exceed capacity, the oldest
     * row(s) are dropped first ("drop-oldest" FIFO — the diagnostic value
     * of the ring is in the most recent invocations).
     */
    public void append(final AttributionRow row) {
        if (!WebFunctionConfig.attributionLogEnabled()) return;
        if (row == null) return;
        final int cap = WebFunctionConfig.attributionLogCapacity();
        synchronized (lock) {
            // Evict oldest until adding the new row fits under the cap.
            // Loop rather than single poll so a mid-flight capacity shrink
            // still converges to the invariant.
            while (ring.size() >= cap) {
                ring.pollFirst();
            }
            ring.addLast(row);
        }
        // Phase 6 — forward to the durable sink AFTER the in-memory
        // append. The sink's write is contractually non-blocking (bounded
        // queue + drop-oldest on full); when the sink is the noop default,
        // this is a single virtual dispatch to an empty method.
        sink.write(row);
    }

    /**
     * Immutable-safe copy of the current ring contents, oldest first. Safe
     * for callers to iterate without holding the ring lock. Empty list
     * when the ring is empty or disabled and unwritten.
     */
    public List<AttributionRow> snapshot() {
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

    /**
     * Current row count. O(1); primarily for tests to distinguish "ring
     * disabled → no rows" from "ring enabled but nothing appended yet".
     */
    public int size() {
        synchronized (lock) {
            return ring.size();
        }
    }

    // --- Convenience helpers used by the wf:call entry points. ------------

    /**
     * Record a SUCCESS outcome. Convenience wrapper around {@link #append}
     * that constructs the row with {@link Instant#now()} and the "" fills
     * for userId / orgId (Phase 1 always empty).
     *
     * <p>{@code fuelConsumed} — Phase 1 reports {@code CallbackContext.tollUsed()}
     * since wasmtime4j-provider does not expose the store's real
     * fuelConsumed to component-mode Java callers today (see
     * {@code fuel-implementation.md} §8 honest failure paths). Phase 2+
     * threads the real accounting through once the provider surfaces it.
     */
    public static void recordSuccess(final String extensionUri,
                                     final long fuelConsumed,
                                     final String queryId) {
        if (!WebFunctionConfig.attributionLogEnabled()) return;
        INSTANCE.append(new AttributionRow(
                Instant.now(),
                "",
                "",
                nonNull(extensionUri),
                fuelConsumed,
                AttributionRow.Outcome.SUCCESS,
                nonNull(queryId)));
    }

    /**
     * Record a fuel-related trap outcome. Distinguishes toll exhaustion
     * from a generic per-invocation trap via the concrete
     * {@link WfBudgetError} subtype.
     */
    public static void recordTrap(final String extensionUri,
                                  final WfBudgetError err,
                                  final long fuelConsumed,
                                  final String queryId) {
        if (!WebFunctionConfig.attributionLogEnabled()) return;
        final AttributionRow.Outcome outcome =
                (err instanceof WfBudgetError.HostCallbackTollExhausted)
                        ? AttributionRow.Outcome.HOST_CALLBACK_TOLL_EXHAUSTED
                        : AttributionRow.Outcome.PER_INVOCATION_TRAP;
        INSTANCE.append(new AttributionRow(
                Instant.now(),
                "",
                "",
                nonNull(extensionUri),
                fuelConsumed,
                outcome,
                nonNull(queryId)));
    }

    private static String nonNull(final String s) {
        return s == null ? "" : s;
    }
}
