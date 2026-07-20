package ai.tegmentum.stardog.kibble.webfunctions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Wave B — declarative table schema + DDL rendering + identifier
 * validation for {@link SqliteTrackerBackend}. Mirrors the shape of
 * {@code TrackerTableSchema} / {@code TrackerColumn} / {@code TrackerIndex}
 * on the Rust reference impl (see
 * {@code oxigraph-webfunction-plugin/crates/host-callbacks-impl/src/tracker_sink.rs})
 * but with the DDL-rendering + ident-validation helpers pulled into
 * this class rather than free functions — the Java form keeps the
 * validation callable in isolation from tests.
 *
 * <p>Two responsibilities:
 *
 * <ol>
 *   <li><b>Ident validation.</b> Every identifier interpolated into a
 *       SQL string (sink name, table name, column name, index name)
 *       must match {@code [A-Za-z0-9_]+} — {@link #validateIdent(String)}
 *       is the single choke point. Anything else surfaces
 *       {@link SqliteTrackerBackend.TrackerError.SchemaViolation}. This is
 *       the injection-immune layer — no interpolated name reaches
 *       {@link java.sql.Connection#prepareStatement(String)} without
 *       passing here first.</li>
 *   <li><b>DDL rendering.</b> {@link #renderCreateTable(String, String)}
 *       assembles the {@code CREATE TABLE IF NOT EXISTS} statement for
 *       one schema against a caller-computed physical table name (the
 *       backend prefixes {@code sink_<sink>_tracker_<table>} — see
 *       {@link SqliteTrackerBackend#physicalTableName}). Index DDL
 *       lands via {@link #renderCreateIndex(String, String, IndexDef)}
 *       for each declared secondary index.</li>
 * </ol>
 *
 * <p>Immutable + comparable by name — two schemas registered under the
 * same (sink, table) key are treated as identical if the column list
 * matches, so a guest that re-declares the same shape (idempotent
 * startup) is not treated as a conflict.
 */
public final class TrackerSchema {

    /**
     * SQLite column types the tracker surface supports. Mirrors the
     * WIT {@code column-type} variant one-to-one — no extra arms. A
     * backend that widens (Postgres integer → bigint) or narrows
     * (DuckDB collapsing BLOB) coerces at its own layer.
     */
    public enum ColumnType {
        TEXT("TEXT"),
        INTEGER("INTEGER"),
        BLOB("BLOB"),
        REAL("REAL");

        private final String sql;
        ColumnType(final String sql) { this.sql = sql; }
        public String sql() { return sql; }
    }

    /** Immutable per-column definition. Order in the schema's
     *  {@code columns} list is the row-value order for
     *  insert / upsert and the natural (schema-order) projection order
     *  for select-with-empty-projection. */
    public static final class ColumnDef {
        public final String name;
        public final ColumnType type;
        public final boolean primaryKey;
        public final boolean nullable;

        public ColumnDef(final String name,
                         final ColumnType type,
                         final boolean primaryKey,
                         final boolean nullable) {
            this.name = name;
            this.type = type;
            this.primaryKey = primaryKey;
            this.nullable = nullable;
        }
    }

    /** Immutable secondary-index definition. {@code unique=true} maps
     *  to a {@code UNIQUE INDEX}; {@code false} to a plain
     *  {@code INDEX}. Every column named here must appear in the
     *  parent table's {@code columns} list — cross-checked at
     *  {@link SqliteTrackerBackend#registerTables} time. */
    public static final class IndexDef {
        public final String name;
        public final List<String> columns;
        public final boolean unique;

        public IndexDef(final String name,
                        final List<String> columns,
                        final boolean unique) {
            this.name = name;
            this.columns = List.copyOf(columns);
            this.unique = unique;
        }
    }

    public final String name;
    public final List<ColumnDef> columns;
    public final List<IndexDef> indexes;

    public TrackerSchema(final String name,
                         final List<ColumnDef> columns,
                         final List<IndexDef> indexes) {
        this.name = name;
        this.columns = List.copyOf(columns);
        this.indexes = List.copyOf(indexes);
    }

    /** Convenience constructor without indexes. */
    public TrackerSchema(final String name, final List<ColumnDef> columns) {
        this(name, columns, Collections.emptyList());
    }

    /**
     * Validate an identifier is safe to interpolate into a SQL string.
     * The single choke point — every name that reaches
     * {@code prepareStatement(...)} passes through here first.
     *
     * <p>Rule: non-empty ASCII characters drawn from
     * {@code [A-Za-z0-9_]}. Anything else — spaces, punctuation,
     * quotes, semicolons, comments — surfaces
     * {@link SqliteTrackerBackend.TrackerError.SchemaViolation}. This
     * matches the reference Rust impl's {@code validate_ident}
     * exactly.
     */
    public static void validateIdent(final String name) {
        if (name == null || name.isEmpty()) {
            throw new SqliteTrackerBackend.TrackerError.SchemaViolation(
                    "identifier is empty");
        }
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            final boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_';
            if (!ok) {
                throw new SqliteTrackerBackend.TrackerError.SchemaViolation(
                        "identifier '" + name
                                + "' contains characters outside [A-Za-z0-9_]");
            }
        }
    }

    /**
     * Validate schema structure — non-empty column list, non-empty
     * names, unique column names, index columns referenced elsewhere
     * in the schema. Every identifier flows through
     * {@link #validateIdent(String)}. Called by
     * {@link SqliteTrackerBackend#registerTables} before any SQL runs.
     */
    public void validate() {
        validateIdent(name);
        if (columns.isEmpty()) {
            throw new SqliteTrackerBackend.TrackerError.SchemaViolation(
                    "table '" + name + "' declares zero columns");
        }
        final Set<String> seen = new LinkedHashSet<>();
        for (final ColumnDef col : columns) {
            validateIdent(col.name);
            if (!seen.add(col.name)) {
                throw new SqliteTrackerBackend.TrackerError.SchemaViolation(
                        "table '" + name + "' declares column '" + col.name
                                + "' more than once");
            }
        }
        for (final IndexDef idx : indexes) {
            validateIdent(idx.name);
            if (idx.columns.isEmpty()) {
                throw new SqliteTrackerBackend.TrackerError.SchemaViolation(
                        "index '" + idx.name + "' on table '" + name
                                + "' has no columns");
            }
            for (final String c : idx.columns) {
                validateIdent(c);
                if (!seen.contains(c)) {
                    throw new SqliteTrackerBackend.TrackerError.SchemaViolation(
                            "index '" + idx.name + "' references column '" + c
                                    + "' not declared on table '" + name + "'");
                }
            }
        }
    }

    /**
     * Render the {@code CREATE TABLE IF NOT EXISTS} statement for a
     * physical (backend-side) table name. The caller supplies
     * {@code physicalTable} because the sink → table name mangling
     * ({@code sink_<sink>_tracker_<table>}) is the backend's
     * responsibility; the schema itself is agnostic.
     *
     * <p>Composite primary key: any column with {@code primaryKey=true}
     * contributes to a trailing {@code PRIMARY KEY (col1, col2, ...)}
     * clause; single-PK cases render as the standard
     * {@code PRIMARY KEY} column modifier for compatibility with the
     * reference impl. Both encode the same SQLite semantics; the
     * reference impl uses the trailing form.
     */
    public String renderCreateTable(final String physicalTable, final String columnQuoteChar) {
        // No quoting needed for the identifiers — validate() has
        // already restricted every name to [A-Za-z0-9_]. Passing
        // columnQuoteChar so a future backend that wants "quoted" idents
        // can override; the SQLite reference impl passes "" (unquoted).
        final StringBuilder sb = new StringBuilder(64 + 32 * columns.size());
        sb.append("CREATE TABLE IF NOT EXISTS ")
          .append(columnQuoteChar).append(physicalTable).append(columnQuoteChar)
          .append(" (");
        final List<String> pkCols = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            final ColumnDef col = columns.get(i);
            if (i > 0) sb.append(", ");
            sb.append(columnQuoteChar).append(col.name).append(columnQuoteChar)
              .append(' ').append(col.type.sql());
            if (!col.nullable) sb.append(" NOT NULL");
            if (col.primaryKey) pkCols.add(col.name);
        }
        if (!pkCols.isEmpty()) {
            sb.append(", PRIMARY KEY (");
            for (int i = 0; i < pkCols.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(columnQuoteChar).append(pkCols.get(i)).append(columnQuoteChar);
            }
            sb.append(')');
        }
        sb.append(')');
        return sb.toString();
    }

    /** Render {@code CREATE [UNIQUE] INDEX IF NOT EXISTS} for one
     *  secondary index over {@code physicalTable}. Every column in
     *  {@code idx.columns} has already passed {@link #validate()}. */
    public static String renderCreateIndex(final String physicalIndexName,
                                           final String physicalTable,
                                           final IndexDef idx) {
        final StringBuilder sb = new StringBuilder(64);
        sb.append("CREATE ");
        if (idx.unique) sb.append("UNIQUE ");
        sb.append("INDEX IF NOT EXISTS ")
          .append(physicalIndexName)
          .append(" ON ")
          .append(physicalTable)
          .append(" (");
        for (int i = 0; i < idx.columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(idx.columns.get(i));
        }
        sb.append(')');
        return sb.toString();
    }

    /** Find a column by name; empty when the name isn't declared. */
    public java.util.Optional<ColumnDef> findColumn(final String colName) {
        for (final ColumnDef c : columns) {
            if (c.name.equals(colName)) return java.util.Optional.of(c);
        }
        return java.util.Optional.empty();
    }
}
