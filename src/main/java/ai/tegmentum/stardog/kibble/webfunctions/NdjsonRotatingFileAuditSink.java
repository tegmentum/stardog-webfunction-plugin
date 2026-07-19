package ai.tegmentum.stardog.kibble.webfunctions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/**
 * Async single-writer NDJSON audit sink with size-based file rotation and
 * optional gzip of rotated files.
 *
 * <p>Design (Phase 6, capability + fuel share this):
 *
 * <ul>
 *   <li><b>Non-blocking {@link #write}.</b> Producers offer the row to a
 *       bounded queue. Queue-full triggers drop-oldest — a single warning
 *       log per drop-storm — because audit rows are diagnostic, not
 *       authoritative; losing a row is preferable to blocking the request
 *       path on disk I/O.</li>
 *   <li><b>Single writer thread.</b> Drains the queue, writes each row's
 *       {@link AuditRow#toNdjsonLine()} plus {@code '\n'} to the current
 *       file, checks the rotation threshold after each write, rotates when
 *       exceeded.</li>
 *   <li><b>Rotation.</b> Active file is {@code <baseName>.log}. On
 *       threshold cross, close, rename existing {@code .1..N} up one slot
 *       (drop {@code .N+1} if beyond {@code maxFiles - 1} rotated files),
 *       rename current to {@code .1}, open a fresh active file. Rotated
 *       files may be gzipped async to keep the writer thread on the
 *       critical path — the gzip happens on a small scheduled pool so a
 *       burst of rotations doesn't back up the writer.</li>
 *   <li><b>Fsync policy.</b> {@link FsyncPolicy#PER_ROW} calls
 *       {@code fd.sync()} after every row (slow but strongest durability);
 *       {@link FsyncPolicy#PER_SECOND} schedules a periodic sync (default;
 *       matches other observability tools' cadence);
 *       {@link FsyncPolicy#NEVER} relies on the OS's own page-cache flush
 *       (fastest, lossy on crash).</li>
 *   <li><b>Shutdown.</b> {@link #close()} sets the stop flag, waits up to
 *       {@code SHUTDOWN_DRAIN_MILLIS} for the writer to drain, joins the
 *       writer, cancels the fsync scheduler, closes the file. Idempotent.</li>
 * </ul>
 *
 * <p>Failure handling: I/O errors on the writer thread are logged and the
 * offending row is dropped, but the writer keeps running. A permanent disk
 * failure devolves to "audit rows silently lost" per the diagnostic-not-
 * authoritative principle. Startup failures (bad directory, permission
 * denied) throw {@link IOException} at construction so the operator learns
 * about a misconfigured sink at boot rather than at first row.
 */
public final class NdjsonRotatingFileAuditSink implements AuditSink {

    private static final Logger LOG = LoggerFactory.getLogger(NdjsonRotatingFileAuditSink.class);

    /** Total wall-clock we give the writer thread to drain during {@link #close}. */
    private static final long SHUTDOWN_DRAIN_MILLIS = 5_000L;

    /**
     * Sentinel used by {@link #close} to unblock a queue-idle writer thread
     * without polluting the {@link AuditRow} interface with a "stop" method.
     * Reference equality is fine — the sentinel is package-private and never
     * escapes this class.
     */
    private static final AuditRow STOP_SENTINEL = new AuditRow() {
        @Override public String toNdjsonLine() { return ""; }
    };

    public enum FsyncPolicy { PER_ROW, PER_SECOND, NEVER }

    /**
     * Immutable configuration bag — collected up-front so the sink's
     * constructor can validate every parameter before opening any file
     * handles.
     */
    public static final class Config {
        public final Path directory;
        public final String baseName;
        public final long rotateBytes;
        public final int maxFiles;
        public final int queueCapacity;
        public final FsyncPolicy fsyncPolicy;
        public final boolean gzipRotated;

        public Config(final Path directory,
                      final String baseName,
                      final long rotateBytes,
                      final int maxFiles,
                      final int queueCapacity,
                      final FsyncPolicy fsyncPolicy,
                      final boolean gzipRotated) {
            if (directory == null) throw new IllegalArgumentException("directory");
            if (baseName == null || baseName.isEmpty()) throw new IllegalArgumentException("baseName");
            if (rotateBytes <= 0) throw new IllegalArgumentException("rotateBytes must be > 0");
            if (maxFiles < 1) throw new IllegalArgumentException("maxFiles must be >= 1");
            if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be >= 1");
            if (fsyncPolicy == null) throw new IllegalArgumentException("fsyncPolicy");
            this.directory = directory;
            this.baseName = baseName;
            this.rotateBytes = rotateBytes;
            this.maxFiles = maxFiles;
            this.queueCapacity = queueCapacity;
            this.fsyncPolicy = fsyncPolicy;
            this.gzipRotated = gzipRotated;
        }
    }

    private final Config config;
    private final LinkedBlockingQueue<AuditRow> queue;
    private final Thread writer;
    private final ScheduledExecutorService fsyncScheduler;
    private final ScheduledExecutorService gzipExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean dropWarned = new AtomicBoolean(false);
    private final AtomicLong dropCount = new AtomicLong(0L);
    private final AtomicLong writeCount = new AtomicLong(0L);

    // Writer-thread-only state (no external synchronization needed).
    private FileOutputStream fos;
    private BufferedOutputStream bos;
    private long bytesInCurrent;

    public NdjsonRotatingFileAuditSink(final Config config) throws IOException {
        this.config = config;
        Files.createDirectories(config.directory);
        openActive();
        this.queue = new LinkedBlockingQueue<>(config.queueCapacity);
        this.writer = new Thread(this::runWriterLoop,
                "webfunctions-audit-" + config.baseName);
        this.writer.setDaemon(true);
        this.writer.start();

        // Gzip executor — one thread, unbounded queue. If rotations pile up
        // faster than gzip can drain (extremely unlikely with 100 MB
        // rotation and small files), gzip lags but the writer keeps going.
        this.gzipExecutor = new ScheduledThreadPoolExecutor(1,
                daemonFactory("webfunctions-audit-gzip-" + config.baseName));

        // Fsync scheduler — only spun up for PER_SECOND. PER_ROW does its
        // sync inline; NEVER does no work.
        if (config.fsyncPolicy == FsyncPolicy.PER_SECOND) {
            this.fsyncScheduler = new ScheduledThreadPoolExecutor(1,
                    daemonFactory("webfunctions-audit-fsync-" + config.baseName));
            this.fsyncScheduler.scheduleAtFixedRate(this::scheduledFsync,
                    1, 1, TimeUnit.SECONDS);
        } else {
            this.fsyncScheduler = null;
        }
    }

    // --- AuditSink -------------------------------------------------------

    @Override
    public void write(final AuditRow row) {
        if (row == null || closed.get()) return;
        // Non-blocking offer — drop-oldest on queue full so the request
        // path never stalls. offer() returning false is the only "queue
        // full" signal LinkedBlockingQueue gives us; drop-oldest is a
        // separate poll → offer step, and losing the race with another
        // producer's offer is fine (the ring's diagnostic value is
        // preserved either way).
        if (!queue.offer(row)) {
            queue.poll();               // drop oldest
            queue.offer(row);           // try again — may again fail; that's ok
            final long dropped = dropCount.incrementAndGet();
            if (dropWarned.compareAndSet(false, true)) {
                LOG.warn("Audit sink queue full for {} — dropping oldest rows "
                                + "(capacity={}); further drops suppressed",
                        config.baseName, config.queueCapacity);
            }
            if ((dropped & 0xFFFFL) == 0L) {
                // Every 65_536 drops, remind the operator we're still shedding.
                LOG.warn("Audit sink for {} has dropped {} rows total",
                        config.baseName, dropped);
            }
        }
    }

    @Override
    public void flush() {
        // Producer-side flush — best effort. Block briefly to let the
        // writer drain any already-enqueued rows. Tests use this to
        // synchronize before reading the file back.
        final long deadline = System.currentTimeMillis() + 2_000L;
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // Ask the writer to fsync its buffered bytes to disk. We can't do
        // it directly (would race with the writer thread's own writes), so
        // enqueue a no-op sentinel row that carries a "flush" side effect
        // is overkill — instead just call fsync from here after a brief
        // wait. The writer's BufferedOutputStream flush inside the loop
        // means bytes are on the FD once queue drains; syncing the FD is
        // safe from another thread as of Java 8+.
        try {
            if (fos != null) fos.getFD().sync();
        } catch (IOException ioe) {
            LOG.debug("Audit sink flush fsync failed: {}", ioe.toString());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // Unblock the writer via the sentinel; the loop treats it as a
        // stop signal.
        queue.offer(STOP_SENTINEL);
        try {
            writer.join(SHUTDOWN_DRAIN_MILLIS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (fsyncScheduler != null) {
            fsyncScheduler.shutdownNow();
        }
        gzipExecutor.shutdown();
        try {
            gzipExecutor.awaitTermination(SHUTDOWN_DRAIN_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        // Writer thread should have closed fos/bos in its finally; if the
        // wait timed out, close from here as a safety net.
        closeStreamsQuietly();
    }

    // --- Test / observation accessors ------------------------------------

    /** Total rows dropped due to queue-full. Test / metric surface. */
    public long droppedRowCount() { return dropCount.get(); }

    /** Total rows successfully written to disk. Test / metric surface. */
    public long writtenRowCount() { return writeCount.get(); }

    /** The active (unrotated) file path. */
    public Path activeFile() {
        return config.directory.resolve(config.baseName + ".log");
    }

    /** Config used to build this sink — introspection for tests / metrics. */
    public Config config() { return config; }

    // --- Writer thread ---------------------------------------------------

    private void runWriterLoop() {
        try {
            while (true) {
                final AuditRow row;
                try {
                    row = queue.take();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (row == STOP_SENTINEL) {
                    // Drain anything the producer offered before the sentinel.
                    AuditRow more;
                    while ((more = queue.poll()) != null) {
                        if (more == STOP_SENTINEL) continue;
                        writeOne(more);
                    }
                    break;
                }
                writeOne(row);
            }
        } finally {
            closeStreamsQuietly();
        }
    }

    private void writeOne(final AuditRow row) {
        try {
            final String line = row.toNdjsonLine();
            final byte[] payload = (line + '\n').getBytes(StandardCharsets.UTF_8);
            bos.write(payload);
            bos.flush(); // to the underlying FileOutputStream — not to disk.
            bytesInCurrent += payload.length;
            writeCount.incrementAndGet();
            if (config.fsyncPolicy == FsyncPolicy.PER_ROW) {
                fos.getFD().sync();
            }
            if (bytesInCurrent >= config.rotateBytes) {
                rotate();
            }
        } catch (IOException ioe) {
            // Log-and-continue — a permanent disk failure devolves to
            // silent audit-row loss, which is the diagnostic-not-
            // authoritative contract.
            LOG.warn("Audit sink write failed for {}: {}",
                    config.baseName, ioe.toString());
        }
    }

    private void openActive() throws IOException {
        final Path active = config.directory.resolve(config.baseName + ".log");
        // Append mode — if the plugin restarts, the file continues.
        this.fos = new FileOutputStream(active.toFile(), true);
        this.bos = new BufferedOutputStream(fos, 8_192);
        this.bytesInCurrent = active.toFile().length();
    }

    private void rotate() throws IOException {
        // Close active before renaming (Windows friendliness; also required
        // on POSIX before gzip so we don't gzip a partial page).
        try {
            bos.flush();
            fos.getFD().sync();
        } catch (IOException ioe) {
            // Best-effort — proceed with rotation anyway.
        }
        try { bos.close(); } catch (IOException ignored) {}
        // Shift .N-1 -> .N ... .1 -> .2, drop the oldest if it exceeds
        // maxFiles - 1 (the active file itself counts against maxFiles).
        final int oldestKept = config.maxFiles - 1;
        // Delete .oldestKept (and any stragglers beyond) in either
        // compressed or plaintext form.
        for (int i = oldestKept; i < oldestKept + 4; i++) {
            deleteQuietly(rotatedPath(i));
            deleteQuietly(rotatedGzPath(i));
        }
        // Shift down from oldest to newest. Handle either the .N or the
        // .N.gz form at each slot (the previous rotation cycle may have
        // already gzipped or not, depending on config changes).
        for (int i = oldestKept - 1; i >= 1; i--) {
            renameQuietly(rotatedPath(i), rotatedPath(i + 1));
            renameQuietly(rotatedGzPath(i), rotatedGzPath(i + 1));
        }
        // Rename active -> .1
        final Path active = config.directory.resolve(config.baseName + ".log");
        final Path first = rotatedPath(1);
        renameQuietly(active, first);

        // Gzip inline on the writer thread. Rotation is infrequent at
        // production defaults (100 MB threshold) so the writer pause is
        // in the tens-of-ms range for a 100 MB file, and the writer is
        // already off the request path — no request-path latency impact.
        // Doing gzip inline (rather than on a separate executor) avoids
        // a genuine race: two consecutive rotations could otherwise race
        // an in-flight gzip against the rename of .1 -> .2, leaving
        // either a partial .1.gz or a missing .1. The gzipExecutor
        // remains only for the shutdown-drain contract.
        if (config.gzipRotated && Files.exists(first)) {
            gzipRotatedFile(first);
        }

        openActive();
    }

    private Path rotatedPath(final int n) {
        return config.directory.resolve(config.baseName + ".log." + n);
    }

    private Path rotatedGzPath(final int n) {
        return config.directory.resolve(config.baseName + ".log." + n + ".gz");
    }

    private void gzipRotatedFile(final Path src) {
        final Path dst = Path.of(src.toString() + ".gz");
        try (OutputStream out = new GZIPOutputStream(
                new BufferedOutputStream(new FileOutputStream(dst.toFile())));
             java.io.InputStream in = Files.newInputStream(src)) {
            in.transferTo(out);
        } catch (IOException ioe) {
            LOG.warn("Audit gzip failed for {}: {}", src, ioe.toString());
            deleteQuietly(dst); // avoid leaving a truncated .gz on disk
            return;
        }
        deleteQuietly(src);
    }

    private void scheduledFsync() {
        try {
            if (fos != null) fos.getFD().sync();
        } catch (IOException ioe) {
            LOG.debug("Scheduled fsync failed for {}: {}",
                    config.baseName, ioe.toString());
        }
    }

    private void closeStreamsQuietly() {
        try {
            if (bos != null) { bos.flush(); bos.close(); bos = null; }
        } catch (IOException ignored) {}
        try {
            if (fos != null) { fos.close(); fos = null; }
        } catch (IOException ignored) {}
    }

    private static void deleteQuietly(final Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
    }

    private static void renameQuietly(final Path from, final Path to) {
        if (!Files.exists(from)) return;
        try {
            Files.move(from, to,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ioe) {
            // Fall back to non-atomic move if the FS doesn't support it.
            try { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException iioe) {
                LOG.warn("Audit rotate rename failed {} -> {}: {}",
                        from, to, iioe.toString());
            }
        }
    }

    private static ThreadFactory daemonFactory(final String name) {
        return r -> {
            final Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
