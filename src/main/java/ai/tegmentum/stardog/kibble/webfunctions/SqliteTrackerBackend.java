package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Wave B — SQLite-JDBC-backed tracker-sink registry singleton. Mirrors
 * the shape of Oxigraph's {@code SqliteTrackerSinkImpl} (see
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
 * When the property is unset the starter never touches the backend —
 * every tracker-sink-callbacks dispatch surfaces {@code no-such-sink}
 * because the allowlist stays empty.
 *
 * <p><b>Thread safety.</b> sqlite-jdbc's default {@link Connection} is
 * not safe for concurrent statement execution. This class serialises
 * every SQL operation on the singleton instance itself
 * ({@code synchronized(this)}) — SQLite likewise serialises writes
 * internally, so the extra JVM monitor is a straight port of the Rust
 * reference impl's {@code Mutex<Connection>} shape. Test isolation is
 * achieved by opening a per-test file (or {@code :memory:}) rather than
 * by concurrent locking.
 *
 * <p><b>Sink allowlist.</b> A sink name outside the config-declared
 * allowlist surfaces {@link TrackerError.NoSuchSink} on any operation.
 * Injection-shaped names (anything outside {@code [A-Za-z0-9_]+}) are
 * rejected at {@link #open} time so bad config aborts install rather
 * than lingering as a runtime landmine.
 *
 * <p><b>Physical naming.</b> Tables land at
 * {@code sink_<sink>_tracker_<table>} and indexes at
 * {@code idx_sink_<sink>_tracker_<table>_<idx>}. Matches the Rust
 * reference so a shared SQLite file between engines stays interop.
 */
public final class SqliteTrackerBackend implements AutoCloseable {

    /** Ambient singleton. Prefer this at call sites; {@link #getInstance()}
     *  exists for symmetry with {@link SinkRegistry} / {@link InMemoryFulltextRegistry}. */
    public static final SqliteTrackerBackend INSTANCE = new SqliteTrackerBackend();

    private Connection conn;                                   // guarded by this
    private final Set<String> allowedSinks = new CopyOnWriteArraySet<>();
    /** (sink, table) → declared schema. Populated by
     *  {@link #registerTables}; consulted on every row op to validate
     *  column names / row shapes / operator strings before any SQL is
     *  composed. */
    private final Map<SinkTableKey, TrackerSchema> schemas = new HashMap<>(); // guarded by this

    /**
     * Tracker-family error variants — one arm per case the
     * {@code tracker-error} WIT variant defines. Ported from the Rust
     * reference impl's {@code TrackerError} enum; each concrete
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
     * prior connection first and drops the cached schemas.
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
            TrackerSchema.validateIdent(name);
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
        schemas.clear();
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
     *  drops the cached schemas. Production paths never call this; the
     *  singleton's lifetime is the plugin's. */
    public synchronized void reset() {
        closeQuietly();
        allowedSinks.clear();
        schemas.clear();
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

    /** Package-private accessor for the live JDBC connection. Every
     *  row op that reads through this is already inside a
     *  {@code synchronized(this)} block. */
    synchronized Connection connectionOrNull() {
        return conn;
    }

    // ---- register-tracker-tables ------------------------------------

    /**
     * Materialize each declared schema on the sink. Idempotent under
     * {@code CREATE TABLE IF NOT EXISTS} — re-declaring the same
     * schema is safe. Schemas are cached in-process so subsequent row
     * ops can validate row shape / WHERE-clause columns without
     * re-parsing DDL.
     *
     * @throws TrackerError.NoSuchSink      when {@code sinkName} is
     *         not in the operator-declared allowlist.
     * @throws TrackerError.SchemaViolation when any table / column /
     *         index identifier fails validation.
     * @throws TrackerError.BackendError    when the JDBC execute fails.
     */
    public synchronized void registerTables(final String sinkName,
                                            final List<TrackerSchema> tables) {
        requireOpen();
        requireSinkKnown(sinkName);
        TrackerSchema.validateIdent(sinkName);
        // Validate up-front so a bad name later in the list doesn't
        // leave a half-materialised sink behind.
        for (final TrackerSchema s : tables) s.validate();
        for (final TrackerSchema s : tables) {
            final String physical = physicalTableName(sinkName, s.name);
            final String ddl = s.renderCreateTable(physical, "");
            executeUpdate(ddl);
            for (final TrackerSchema.IndexDef idx : s.indexes) {
                final String indexName = physicalIndexName(sinkName, s.name, idx.name);
                executeUpdate(TrackerSchema.renderCreateIndex(indexName, physical, idx));
            }
            schemas.put(new SinkTableKey(sinkName, s.name), s);
        }
    }

    // ---- row ops ----------------------------------------------------

    /** Insert one row. Fails on PK collision with
     *  {@link TrackerError.BackendError} (SQLite {@code UNIQUE
     *  constraint failed}). */
    public synchronized void insertRow(final String sinkName,
                                       final String tableName,
                                       final List<ComponentVal> rowValues) {
        insertRowInternal(sinkName, tableName, rowValues, /*replace=*/ false);
    }

    /** Upsert one row — {@code INSERT OR REPLACE INTO ...}. */
    public synchronized void upsertRow(final String sinkName,
                                       final String tableName,
                                       final List<ComponentVal> rowValues) {
        insertRowInternal(sinkName, tableName, rowValues, /*replace=*/ true);
    }

    private void insertRowInternal(final String sinkName,
                                   final String tableName,
                                   final List<ComponentVal> rowValues,
                                   final boolean replace) {
        requireOpen();
        requireSinkKnown(sinkName);
        final TrackerSchema s = requireSchema(sinkName, tableName);
        if (rowValues.size() != s.columns.size()) {
            throw new TrackerError.SchemaViolation(
                    "row has " + rowValues.size() + " values but table '"
                    + tableName + "' has " + s.columns.size() + " columns");
        }
        for (int i = 0; i < s.columns.size(); i++) {
            TrackerValueMarshaller.validateValueAgainstColumn(
                    s.columns.get(i), rowValues.get(i));
        }
        final String physical = physicalTableName(sinkName, tableName);
        final StringBuilder sql = new StringBuilder(64);
        sql.append(replace ? "INSERT OR REPLACE INTO " : "INSERT INTO ")
           .append(physical).append(" (");
        for (int i = 0; i < s.columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(s.columns.get(i).name);
        }
        sql.append(") VALUES (");
        for (int i = 0; i < s.columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append('?');
        }
        sql.append(')');
        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < s.columns.size(); i++) {
                TrackerValueMarshaller.bind(stmt, i + 1, s.columns.get(i), rowValues.get(i));
            }
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new TrackerError.BackendError(
                    "insert into '" + physical + "' failed: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /**
     * Select rows matching every WHERE clause (implicit AND). Empty
     * {@code projection} means "all columns in schema declaration
     * order". Returns rows as a list of {@code ComponentVal}
     * value-lists in projection order — the caller wraps each as a
     * {@code tracker-row} record.
     */
    public synchronized List<List<ComponentVal>> selectRows(final String sinkName,
                                                            final String tableName,
                                                            final List<TrackerWhere.Clause> where,
                                                            final List<String> projection) {
        requireOpen();
        requireSinkKnown(sinkName);
        final TrackerSchema s = requireSchema(sinkName, tableName);

        final List<TrackerSchema.ColumnDef> projCols;
        if (projection.isEmpty()) {
            projCols = s.columns;
        } else {
            projCols = new ArrayList<>(projection.size());
            for (final String name : projection) {
                TrackerSchema.validateIdent(name);
                projCols.add(s.findColumn(name).orElseThrow(() ->
                        new TrackerError.SchemaViolation(
                                "select projection references column '" + name
                                + "' not in schema for table '" + tableName + "'")));
            }
        }

        final TrackerWhere.Composed composed = TrackerWhere.compose(s, where);
        final String physical = physicalTableName(sinkName, tableName);
        final StringBuilder sql = new StringBuilder(64);
        sql.append("SELECT ");
        for (int i = 0; i < projCols.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(projCols.get(i).name);
        }
        sql.append(" FROM ").append(physical);
        composed.fragment.ifPresent(f -> sql.append(" WHERE ").append(f));

        final List<List<ComponentVal>> out = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < composed.params.size(); i++) {
                final TrackerWhere.Bound b = composed.params.get(i);
                TrackerValueMarshaller.bind(stmt, i + 1, b.column, b.value);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    final List<ComponentVal> row = new ArrayList<>(projCols.size());
                    for (int i = 0; i < projCols.size(); i++) {
                        row.add(TrackerValueMarshaller.encode(rs, i + 1, projCols.get(i)));
                    }
                    out.add(row);
                }
            }
        } catch (SQLException e) {
            throw new TrackerError.BackendError(
                    "select from '" + physical + "' failed: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        return out;
    }

    /** Delete rows matching every WHERE clause. Empty {@code where}
     *  deletes every row. Returns the row count actually removed. */
    public synchronized long deleteRows(final String sinkName,
                                        final String tableName,
                                        final List<TrackerWhere.Clause> where) {
        requireOpen();
        requireSinkKnown(sinkName);
        final TrackerSchema s = requireSchema(sinkName, tableName);
        final TrackerWhere.Composed composed = TrackerWhere.compose(s, where);
        final String physical = physicalTableName(sinkName, tableName);
        final StringBuilder sql = new StringBuilder(64);
        sql.append("DELETE FROM ").append(physical);
        composed.fragment.ifPresent(f -> sql.append(" WHERE ").append(f));
        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < composed.params.size(); i++) {
                final TrackerWhere.Bound b = composed.params.get(i);
                TrackerValueMarshaller.bind(stmt, i + 1, b.column, b.value);
            }
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new TrackerError.BackendError(
                    "delete from '" + physical + "' failed: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /** Count rows matching every WHERE clause. Empty {@code where}
     *  counts every row. Returns a non-negative {@code long}. */
    public synchronized long countRows(final String sinkName,
                                       final String tableName,
                                       final List<TrackerWhere.Clause> where) {
        requireOpen();
        requireSinkKnown(sinkName);
        final TrackerSchema s = requireSchema(sinkName, tableName);
        final TrackerWhere.Composed composed = TrackerWhere.compose(s, where);
        final String physical = physicalTableName(sinkName, tableName);
        final StringBuilder sql = new StringBuilder(64);
        sql.append("SELECT COUNT(*) FROM ").append(physical);
        composed.fragment.ifPresent(f -> sql.append(" WHERE ").append(f));
        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < composed.params.size(); i++) {
                final TrackerWhere.Bound b = composed.params.get(i);
                TrackerValueMarshaller.bind(stmt, i + 1, b.column, b.value);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return 0L;
                final long n = rs.getLong(1);
                return Math.max(0L, n);
            }
        } catch (SQLException e) {
            throw new TrackerError.BackendError(
                    "count from '" + physical + "' failed: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    // ---- helpers ----------------------------------------------------

    /** Whether a schema has been registered for (sink, table). Useful
     *  for tests. */
    public synchronized boolean hasTable(final String sinkName, final String tableName) {
        return schemas.containsKey(new SinkTableKey(sinkName, tableName));
    }

    /** Snapshot of the schema previously registered under (sink, table).
     *  Empty when the guest never called
     *  {@link #registerTables(String, List)} with that key. */
    public synchronized Optional<TrackerSchema> schemaFor(final String sinkName,
                                                          final String tableName) {
        return Optional.ofNullable(schemas.get(new SinkTableKey(sinkName, tableName)));
    }

    private void requireOpen() {
        if (conn == null) {
            throw new TrackerError.BackendError(
                    "tracker-sink backend not open — "
                    + WebFunctionConfig.PROP_TRACKER_SQLITE_PATH
                    + " is unset or the starter didn't run");
        }
    }

    private void requireSinkKnown(final String sinkName) {
        if (!sinkAllowed(sinkName)) {
            throw new TrackerError.NoSuchSink(
                    "no tracker-sink registered under name '" + sinkName
                    + "' — declare it in " + WebFunctionConfig.PROP_TRACKER_SQLITE_SINKS
                    + " at boot");
        }
    }

    private TrackerSchema requireSchema(final String sinkName, final String tableName) {
        final TrackerSchema s = schemas.get(new SinkTableKey(sinkName, tableName));
        if (s == null) {
            throw new TrackerError.NoSuchTable(
                    "no tracker table registered as '" + tableName
                    + "' on sink '" + sinkName
                    + "' — call register-tracker-tables first");
        }
        return s;
    }

    private void executeUpdate(final String ddl) {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(ddl);
        } catch (SQLException e) {
            throw new TrackerError.BackendError(
                    "tracker-sink DDL failed: '" + ddl + "': "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /** Compose {@code sink_<sink>_tracker_<table>}. Both segments
     *  have already passed {@link TrackerSchema#validateIdent}. */
    static String physicalTableName(final String sinkName, final String tableName) {
        return "sink_" + sinkName + "_tracker_" + tableName;
    }

    /** Compose {@code idx_sink_<sink>_tracker_<table>_<idx>}. */
    static String physicalIndexName(final String sinkName,
                                    final String tableName,
                                    final String indexName) {
        return "idx_sink_" + sinkName + "_tracker_" + tableName + "_" + indexName;
    }

    private static final class SinkTableKey {
        final String sink;
        final String table;

        SinkTableKey(final String sink, final String table) {
            this.sink = sink;
            this.table = table;
        }

        @Override
        public boolean equals(final Object other) {
            if (!(other instanceof SinkTableKey)) return false;
            final SinkTableKey o = (SinkTableKey) other;
            return sink.equals(o.sink) && table.equals(o.table);
        }

        @Override
        public int hashCode() {
            return sink.hashCode() * 31 + table.hashCode();
        }
    }
}
