package ai.tegmentum.stardog.kibble.webfunctions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Wave B — SQLite-JDBC-backed tracker-sink registry singleton. Mirrors the
 * shape of Oxigraph's {@code SqliteTrackerSinkImpl} (see
 * {@code ~/git/oxigraph-webfunction-plugin/crates/host-callbacks-impl/src/tracker_sink.rs})
 * scaled down to the six MVP callbacks the substrate memo commits to:
 * {@code register-tracker-tables}, {@code tracker-insert},
 * {@code tracker-upsert}, {@code tracker-select}, {@code tracker-delete},
 * {@code tracker-count}.
 *
 * <p>Physical wiring: one JDBC {@link Connection} per singleton, opened
 * lazily via {@link #open(String, List)} at plugin startup by
 * {@code WebFunctionServiceModule.SqliteTrackerBackendStarter} when the
 * {@link WebFunctionConfig#PROP_TRACKER_SQLITE_PATH} property is set.
 * When the property is unset, the starter never touches the backend —
 * every tracker-sink-callbacks dispatch surfaces {@code no-such-sink}
 * because the allowlist stays empty.
 *
 * <p><b>Thread safety.</b> sqlite-jdbc's default {@link Connection}
 * is not safe for concurrent statement execution. This class serialises
 * every SQL operation on a single {@code synchronized} monitor
 * (the backend instance itself). SQLite likewise serialises writes
 * internally, so the extra JVM monitor is a straight port of the Rust
 * reference impl's {@code Mutex<Connection>} shape.
 *
 * <p><b>Sink allowlist.</b> A sink name outside the config-declared
 * allowlist surfaces {@link TrackerError.NoSuchSink} on any operation.
 * Injection-shaped names (anything outside {@code [A-Za-z0-9_]+}) are
 * rejected at {@link #open} time so bad config aborts install rather
 * than lingering as a runtime landmine.
 *
 * <p>Full implementation of register / row ops lands in Wave B sub-phase
 * WB5. The skeleton here (WB1) supports open / close / allowlist
 * queries so the starter and pom wiring can land ahead of the row-op
 * code.
 */
public final class SqliteTrackerBackend implements AutoCloseable {

    /** Ambient singleton. Prefer this at call sites; {@link #getInstance()}
     *  exists for symmetry with {@link SinkRegistry} / {@link InMemoryFulltextRegistry}. */
    public static final SqliteTrackerBackend INSTANCE = new SqliteTrackerBackend();

    private Connection conn;                                   // guarded by this
    private final Set<String> allowedSinks = new CopyOnWriteArraySet<>();

    /**
     * Tracker-family error variants — one arm per case the
     * {@code tracker-error} WIT variant defines. Ported from the
     * Rust reference impl's {@code TrackerError} enum; each concrete
     * subclass carries the payload string the WIT variant expects.
     * Handler code in {@link HostCallbacks} maps these to
     * {@code ComponentVal.variant(armName, ComponentVal.string(payload))}
     * one-to-one.
     */
    public abstract static class TrackerError extends RuntimeException {
        private final String armName;

        protected TrackerError(final String armName, final String message) {
            super(message);
            this.armName = armName;
        }

        /** WIT variant arm name — pass through to
         *  {@code ComponentVal.variant(armName(), ...)}. */
        public String armName() { return armName; }

        public static final class NoSuchSink extends TrackerError {
            public NoSuchSink(final String message) { super("no-such-sink", message); }
        }
        public static final class NoSuchTable extends TrackerError {
            public NoSuchTable(final String message) { super("no-such-table", message); }
        }
        public static final class SchemaViolation extends TrackerError {
            public SchemaViolation(final String message) { super("schema-violation", message); }
        }
        public static final class BackendError extends TrackerError {
            public BackendError(final String message) { super("backend-error", message); }
        }
        public static final class NotPermitted extends TrackerError {
            public NotPermitted(final String message) { super("not-permitted", message); }
        }
    }

    private SqliteTrackerBackend() {}

    /** Ambient singleton accessor — parallel to
     *  {@link SinkRegistry#getInstance()}. */
    public static SqliteTrackerBackend getInstance() {
        return INSTANCE;
    }

    /**
     * Open the SQLite database at {@code path} and seed the sink
     * allowlist. Idempotent under {@link #close()} — a re-open in a
     * single JVM (test harness re-installing the plugin) closes the
     * prior connection first.
     *
     * <p>Every allowed-sink name is validated against
     * {@link TrackerSchema#validateIdent(String)} at this point so a
     * config-time typo (or an injection attempt) aborts install rather
     * than lingering as a runtime landmine.
     *
     * @throws TrackerError.SchemaViolation when an allowed-sink name
     *         contains characters outside {@code [A-Za-z0-9_]}.
     * @throws TrackerError.BackendError    when the JDBC open fails.
     */
    public synchronized void open(final String path, final List<String> sinks) {
        for (final String name : sinks) {
            validateIdentInline(name); // WB2 moves this to TrackerSchema.validateIdent
        }
        closeQuietly();
        try {
            // sqlite-jdbc auto-registers via ServiceLoader; the explicit
            // Class.forName below defends against classloader edge cases
            // when the plugin's shaded jar is loaded by a non-standard
            // Stardog classloader.
            try { Class.forName("org.sqlite.JDBC"); } catch (ClassNotFoundException ignore) {}
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        } catch (SQLException e) {
            throw new TrackerError.BackendError(
                    "tracker-sink: sqlite open failed for path '" + path + "': "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        allowedSinks.clear();
        allowedSinks.addAll(sinks);
    }

    /** True when {@link #open(String, List)} has been called and the
     *  connection is still live. */
    public synchronized boolean isOpen() {
        try {
            return conn != null && !conn.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    /** Whether the given sink name is in the operator-declared
     *  allowlist. Handlers translate {@code false} into
     *  {@code no-such-sink}. */
    public boolean sinkAllowed(final String name) {
        return name != null && allowedSinks.contains(name);
    }

    /** Snapshot of the operator-declared allowlist. Sorted for
     *  determinism. */
    public List<String> allowedSinkNames() {
        final java.util.List<String> names = new java.util.ArrayList<>(allowedSinks);
        Collections.sort(names);
        return names;
    }

    /** Test-only reset — closes the connection, clears the allowlist,
     *  drops the cached schemas (once WB5 lands). Production paths
     *  never call this; the singleton's lifetime is the plugin's. */
    public synchronized void reset() {
        closeQuietly();
        allowedSinks.clear();
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (conn == null) return;
        try {
            conn.close();
        } catch (SQLException ignore) {
            // best-effort — a broken close does not merit an install
            // failure or throw across a reset boundary.
        }
        conn = null;
    }

    /** Package-private accessor for the live JDBC connection. WB5 wires
     *  every row op through this + a synchronized{} block on
     *  {@code this}. */
    synchronized Connection connectionOrNull() {
        return conn;
    }

    // WB1 placeholder — WB2 replaces callers with TrackerSchema.validateIdent.
    private static void validateIdentInline(final String name) {
        if (name == null || name.isEmpty()) {
            throw new TrackerError.SchemaViolation("identifier is empty");
        }
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            final boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_';
            if (!ok) {
                throw new TrackerError.SchemaViolation(
                        "identifier '" + name + "' contains characters outside [A-Za-z0-9_]");
            }
        }
    }
}
