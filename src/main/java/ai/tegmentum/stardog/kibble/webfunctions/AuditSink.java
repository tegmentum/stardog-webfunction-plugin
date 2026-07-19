package ai.tegmentum.stardog.kibble.webfunctions;

/**
 * Sink that receives {@link AuditRow}s from the in-memory rings so a
 * Phase 6 deployment can capture the ring-tail on durable storage.
 *
 * <p>Two implementations ship in Phase 6:
 * <ul>
 *   <li>{@link NoopAuditSink} — the default when
 *       {@code webfunctions.audit.disk.enabled=false} (also the default
 *       Phase 1 behavior — in-memory rings only, no disk I/O). Every
 *       operation is a no-op; callers that check for the noop-ness via
 *       an {@code == NoopAuditSink.INSTANCE} comparison can skip constructing
 *       the row entirely, but in practice the ring's own enabled check is
 *       the earlier short-circuit and the noop's zero-cost body suffices.</li>
 *   <li>{@link NdjsonRotatingFileAuditSink} — the durable backing: single
 *       writer thread reads from a bounded queue, appends one JSON
 *       object per line, rotates by size, optionally gzips rotated
 *       files.</li>
 * </ul>
 *
 * <p>Contract:
 * <ul>
 *   <li><b>Non-blocking {@link #write}.</b> The ring's {@code append}
 *       runs on the request path — sink writes MUST NOT block the caller
 *       waiting for disk. The disk sink offers to a bounded queue; if the
 *       queue is full, drop-oldest and continue (audit rows are diagnostic,
 *       not authoritative).</li>
 *   <li><b>{@link #flush} is best-effort.</b> Callers use it before a
 *       snapshot-and-compare test or before a scheduled compliance query
 *       consumes the file; it MAY still return before the OS has fsynced
 *       depending on the sink's fsync policy.</li>
 *   <li><b>{@link #close} drains and joins.</b> Safe to call from a JVM
 *       shutdown hook; MUST NOT throw checked exceptions (matches the
 *       {@link AutoCloseable} idiom used across the plugin).</li>
 *   <li><b>Idempotent close.</b> A second {@code close()} is a no-op so
 *       the shutdown wiring doesn't have to coordinate the "who closes"
 *       question.</li>
 * </ul>
 */
public interface AuditSink extends AutoCloseable {

    /**
     * Enqueue a row for durable write. MUST NOT block the caller;
     * disk-backed impls that can't accept the row (queue full, disk
     * failure) drop-oldest or drop-newest and continue.
     */
    void write(AuditRow row);

    /**
     * Best-effort flush of any buffered rows to disk. Provides a
     * synchronization point for tests and scheduled log-consumers.
     */
    void flush();

    /**
     * Drain any queued rows, flush the writer, and release resources.
     * Idempotent. Overridden to drop the {@code throws Exception} from
     * {@link AutoCloseable} so callers don't have to handle a checked
     * exception on a shutdown path.
     */
    @Override
    void close();
}
