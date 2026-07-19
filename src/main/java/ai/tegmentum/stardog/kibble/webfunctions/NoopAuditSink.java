package ai.tegmentum.stardog.kibble.webfunctions;

/**
 * Zero-cost {@link AuditSink} used when
 * {@code webfunctions.audit.disk.enabled=false} (the default) — every
 * operation is a no-op. Ring append still records in-memory; the sink call
 * is a single virtual dispatch to an empty method, which the JIT reduces
 * to a no-op after a warm-up.
 *
 * <p>Singleton — the ring hot-path compares {@code sink != null} rather
 * than {@code sink != NoopAuditSink.INSTANCE}, but the singleton is
 * available for tests and for explicit "reset to noop" wiring during
 * {@code close()} of a real sink.
 */
public final class NoopAuditSink implements AuditSink {

    /** Process-wide instance — safe to share; no state. */
    public static final NoopAuditSink INSTANCE = new NoopAuditSink();

    private NoopAuditSink() {}

    @Override
    public void write(final AuditRow row) {
        // no-op — Phase 1 in-memory-only behavior preserved.
    }

    @Override
    public void flush() {
        // no-op.
    }

    @Override
    public void close() {
        // no-op — noop sink holds no resources.
    }
}
