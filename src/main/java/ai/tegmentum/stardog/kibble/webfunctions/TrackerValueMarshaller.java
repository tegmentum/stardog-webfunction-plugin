package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.wasmtime4j.component.ComponentVariant;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Wave B — marshalling between WIT {@code tracker-value} variants and
 * JDBC parameter / result-set types. Mirrors the Rust reference impl's
 * {@code value_to_sql} / {@code sql_value_to_tracker} pair one-to-one
 * on the wire — the JVM side lands as
 * {@link PreparedStatement#setObject(int, Object)} plus per-arm
 * {@code setXxx} calls.
 *
 * <p>Five WIT arms, five JDBC handlers:
 * <ul>
 *   <li>{@code text-value(string)}   → {@link PreparedStatement#setString}
 *       and {@link ResultSet#getString}.</li>
 *   <li>{@code integer-value(s64)}   → {@link PreparedStatement#setLong}
 *       and {@link ResultSet#getLong}.</li>
 *   <li>{@code real-value(f64)}      → {@link PreparedStatement#setDouble}
 *       and {@link ResultSet#getDouble}.</li>
 *   <li>{@code blob-value(list&lt;u8&gt;)} →
 *       {@link PreparedStatement#setBytes}
 *       and {@link ResultSet#getBytes}.</li>
 *   <li>{@code null-value}           → {@link PreparedStatement#setNull}
 *       (SQL type derived from the column) and
 *       {@link ResultSet#wasNull}-guarded lookup.</li>
 * </ul>
 *
 * <p><b>Type coercion at the boundary.</b>
 * {@link #validateValueAgainstColumn} refuses a text value bound to an
 * integer column with {@link SqliteTrackerBackend.TrackerError.SchemaViolation}
 * — matches the reference impl's discipline. The backend calls this
 * on every row before parameter binding so type mismatches surface as
 * a typed WIT error rather than a JDBC ClassCastException.
 *
 * <p><b>SQLite null semantics.</b> A {@code null-value} arm bound
 * against a NOT NULL column is a
 * {@link SqliteTrackerBackend.TrackerError.SchemaViolation}. SQLite
 * would surface this as a {@code NOT NULL constraint failed} backend
 * error at INSERT time; catching it at the boundary keeps the WIT
 * error variant precise.
 */
public final class TrackerValueMarshaller {

    private TrackerValueMarshaller() {}

    // ---- decode: WIT ComponentVal -> ready-to-bind JDBC value --------

    /**
     * Bind one {@code tracker-value} at parameter index {@code idx} on
     * {@code stmt}. Caller is responsible for having called
     * {@link #validateValueAgainstColumn(TrackerSchema.ColumnDef, ComponentVal)}
     * beforehand — this method assumes a valid arm-vs-column pairing.
     *
     * <p>The {@code column} parameter is used only for
     * {@link Types#NULL} discrimination — a {@code null-value} arm
     * lands as {@code setNull(idx, sqlTypeForColumn(column.type))}.
     */
    public static void bind(final PreparedStatement stmt,
                            final int idx,
                            final TrackerSchema.ColumnDef column,
                            final ComponentVal value) throws SQLException {
        final ComponentVariant variant = value.asVariant();
        final String armName = variant.getCaseName();
        switch (armName) {
            case "text-value":
                stmt.setString(idx, variant.getPayload().orElseThrow().asString());
                break;
            case "integer-value":
                stmt.setLong(idx, variant.getPayload().orElseThrow().asS64());
                break;
            case "real-value":
                stmt.setDouble(idx, variant.getPayload().orElseThrow().asF64());
                break;
            case "blob-value":
                stmt.setBytes(idx, variant.getPayload().orElseThrow().asByteArray());
                break;
            case "null-value":
                // Use the declared column type so SQLite gets the
                // expected type-affinity signal. Bare setNull(_, NULL)
                // works too but this matches JDBC-idiomatic shape.
                stmt.setNull(idx, sqlTypeForColumn(column.type));
                break;
            default:
                throw new SqliteTrackerBackend.TrackerError.SchemaViolation(
                        "unknown tracker-value arm: '" + armName + "'");
        }
    }

    // ---- encode: JDBC ResultSet -> WIT ComponentVal ------------------

    /**
     * Read one column from {@code rs} at 1-based index
     * {@code sqlIdx} and encode as a {@code tracker-value} variant.
     * The column's declared type steers the encoding — SQLite storage
     * classes are looser than the WIT surface (a value stored in a
     * TEXT column might come back as INTEGER-shaped), and we map
     * consistently against the schema the guest declared.
     */
    public static ComponentVal encode(final ResultSet rs,
                                      final int sqlIdx,
                                      final TrackerSchema.ColumnDef column) throws SQLException {
        switch (column.type) {
            case TEXT: {
                final String s = rs.getString(sqlIdx);
                if (rs.wasNull()) return ComponentVal.variant("null-value");
                return ComponentVal.variant("text-value", ComponentVal.string(s));
            }
            case INTEGER: {
                final long v = rs.getLong(sqlIdx);
                if (rs.wasNull()) return ComponentVal.variant("null-value");
                return ComponentVal.variant("integer-value", ComponentVal.s64(v));
            }
            case REAL: {
                final double v = rs.getDouble(sqlIdx);
                if (rs.wasNull()) return ComponentVal.variant("null-value");
                return ComponentVal.variant("real-value", ComponentVal.f64(v));
            }
            case BLOB: {
                final byte[] bs = rs.getBytes(sqlIdx);
                if (rs.wasNull() || bs == null) return ComponentVal.variant("null-value");
                return ComponentVal.variant("blob-value", ComponentVal.listU8(bs));
            }
            default:
                throw new SqliteTrackerBackend.TrackerError.BackendError(
                        "unknown column type for encode: " + column.type);
        }
    }

    // ---- validation --------------------------------------------------

    /**
     * Type-check a {@code tracker-value} against the declared column.
     * A text arm bound to an integer column, or a null arm bound to a
     * NOT NULL column, surfaces
     * {@link SqliteTrackerBackend.TrackerError.SchemaViolation}.
     *
     * <p>Called by the backend before every {@link #bind} to keep
     * boundary errors on the WIT error surface rather than the JDBC
     * exception surface — which the guest cannot introspect.
     */
    public static void validateValueAgainstColumn(final TrackerSchema.ColumnDef column,
                                                  final ComponentVal value) {
        final ComponentVariant variant = value.asVariant();
        final String armName = variant.getCaseName();
        if ("null-value".equals(armName)) {
            if (!column.nullable) {
                throw new SqliteTrackerBackend.TrackerError.SchemaViolation(
                        "null value bound against NOT NULL column '" + column.name + "'");
            }
            return;
        }
        final boolean ok;
        switch (column.type) {
            case TEXT:    ok = "text-value".equals(armName); break;
            case INTEGER: ok = "integer-value".equals(armName); break;
            case REAL:    ok = "real-value".equals(armName); break;
            case BLOB:    ok = "blob-value".equals(armName); break;
            default:      ok = false;
        }
        if (!ok) {
            throw new SqliteTrackerBackend.TrackerError.SchemaViolation(
                    "value type mismatch for column '" + column.name
                    + "': expected " + column.type + " arm, got '" + armName + "'");
        }
    }

    /** Map a tracker column type to its {@link Types} constant, so a
     *  {@code null-value} arm's {@link PreparedStatement#setNull} call
     *  passes a type hint matching what the column expects. */
    static int sqlTypeForColumn(final TrackerSchema.ColumnType t) {
        switch (t) {
            case TEXT:    return Types.VARCHAR;
            case INTEGER: return Types.BIGINT;
            case REAL:    return Types.DOUBLE;
            case BLOB:    return Types.BLOB;
            default:      return Types.NULL;
        }
    }
}
