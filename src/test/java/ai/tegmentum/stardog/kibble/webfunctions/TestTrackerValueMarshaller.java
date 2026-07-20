package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.wasmtime4j.component.ComponentVariant;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Wave B unit tests for {@link TrackerValueMarshaller}. Uses an
 * in-memory SQLite connection to prove the round-trip: bind a WIT
 * value through {@link PreparedStatement}, read it back through
 * {@link ResultSet}, expect the same variant + payload.
 */
public class TestTrackerValueMarshaller {

    private Connection conn;

    @Before
    public void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            // One column of each supported type, all nullable so the
            // null arm has somewhere to land.
            st.executeUpdate(
                    "CREATE TABLE t ("
                    + "t_col TEXT, "
                    + "i_col INTEGER, "
                    + "r_col REAL, "
                    + "b_col BLOB)");
        }
    }

    @After
    public void tearDown() throws SQLException {
        if (conn != null) conn.close();
    }

    // ---- round-trip: bind then read ---------------------------------

    @Test
    public void textValueRoundTrips() throws SQLException {
        final TrackerSchema.ColumnDef col = new TrackerSchema.ColumnDef(
                "t_col", TrackerSchema.ColumnType.TEXT, false, true);
        final ComponentVal bound = ComponentVal.variant(
                "text-value", ComponentVal.string("hello"));
        insertAndAssert(col, bound, "t_col");
    }

    @Test
    public void integerValueRoundTrips() throws SQLException {
        final TrackerSchema.ColumnDef col = new TrackerSchema.ColumnDef(
                "i_col", TrackerSchema.ColumnType.INTEGER, false, true);
        final ComponentVal bound = ComponentVal.variant(
                "integer-value", ComponentVal.s64(-1234567890123L));
        insertAndAssert(col, bound, "i_col");
    }

    @Test
    public void realValueRoundTrips() throws SQLException {
        final TrackerSchema.ColumnDef col = new TrackerSchema.ColumnDef(
                "r_col", TrackerSchema.ColumnType.REAL, false, true);
        final ComponentVal bound = ComponentVal.variant(
                "real-value", ComponentVal.f64(3.141592653589793));
        insertAndAssert(col, bound, "r_col");
    }

    @Test
    public void blobValueRoundTrips() throws SQLException {
        final TrackerSchema.ColumnDef col = new TrackerSchema.ColumnDef(
                "b_col", TrackerSchema.ColumnType.BLOB, false, true);
        final byte[] payload = {0, 1, 2, 3, (byte) 0xFF, (byte) 0x80, 127};
        final ComponentVal bound = ComponentVal.variant(
                "blob-value", ComponentVal.listU8(payload));
        insertAndAssert(col, bound, "b_col");
    }

    @Test
    public void nullValueRoundTripsForEveryColumnType() throws SQLException {
        final TrackerSchema.ColumnType[] types = TrackerSchema.ColumnType.values();
        final String[] cols = {"t_col", "i_col", "r_col", "b_col"};
        for (int i = 0; i < types.length; i++) {
            final TrackerSchema.ColumnDef col = new TrackerSchema.ColumnDef(
                    cols[i], types[i], false, true);
            final ComponentVal bound = ComponentVal.variant("null-value");
            try (Statement st = conn.createStatement()) { st.executeUpdate("DELETE FROM t"); }
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO t (" + cols[i] + ") VALUES (?)")) {
                TrackerValueMarshaller.bind(ins, 1, col, bound);
                ins.executeUpdate();
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT " + cols[i] + " FROM t")) {
                assertThat(rs.next()).isTrue();
                final ComponentVal out = TrackerValueMarshaller.encode(rs, 1, col);
                assertThat(out.asVariant().getCaseName()).isEqualTo("null-value");
            }
        }
    }

    // ---- validateValueAgainstColumn ---------------------------------

    @Test
    public void textArmAgainstIntegerColumnRejected() {
        final TrackerSchema.ColumnDef col = new TrackerSchema.ColumnDef(
                "i_col", TrackerSchema.ColumnType.INTEGER, false, false);
        final ComponentVal bad = ComponentVal.variant("text-value", ComponentVal.string("x"));
        assertThat(catchThrowable(() ->
                TrackerValueMarshaller.validateValueAgainstColumn(col, bad)))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class)
                .hasMessageContaining("type mismatch")
                .hasMessageContaining("i_col");
    }

    @Test
    public void integerArmAgainstTextColumnRejected() {
        final TrackerSchema.ColumnDef col = new TrackerSchema.ColumnDef(
                "t_col", TrackerSchema.ColumnType.TEXT, false, false);
        final ComponentVal bad = ComponentVal.variant("integer-value", ComponentVal.s64(1L));
        assertThat(catchThrowable(() ->
                TrackerValueMarshaller.validateValueAgainstColumn(col, bad)))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class);
    }

    @Test
    public void blobArmAgainstRealColumnRejected() {
        final TrackerSchema.ColumnDef col = new TrackerSchema.ColumnDef(
                "r_col", TrackerSchema.ColumnType.REAL, false, false);
        final ComponentVal bad = ComponentVal.variant(
                "blob-value", ComponentVal.listU8(new byte[]{1, 2, 3}));
        assertThat(catchThrowable(() ->
                TrackerValueMarshaller.validateValueAgainstColumn(col, bad)))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class);
    }

    @Test
    public void nullAgainstNotNullColumnRejected() {
        final TrackerSchema.ColumnDef col = new TrackerSchema.ColumnDef(
                "t_col", TrackerSchema.ColumnType.TEXT, false, /* nullable = */ false);
        final ComponentVal bad = ComponentVal.variant("null-value");
        assertThat(catchThrowable(() ->
                TrackerValueMarshaller.validateValueAgainstColumn(col, bad)))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class)
                .hasMessageContaining("NOT NULL");
    }

    @Test
    public void matchingArmAndColumnPasses() {
        final TrackerSchema.ColumnDef col = new TrackerSchema.ColumnDef(
                "t_col", TrackerSchema.ColumnType.TEXT, false, false);
        TrackerValueMarshaller.validateValueAgainstColumn(
                col, ComponentVal.variant("text-value", ComponentVal.string("ok")));
    }

    @Test
    public void nullAgainstNullableColumnPasses() {
        final TrackerSchema.ColumnDef col = new TrackerSchema.ColumnDef(
                "t_col", TrackerSchema.ColumnType.TEXT, false, /* nullable = */ true);
        TrackerValueMarshaller.validateValueAgainstColumn(
                col, ComponentVal.variant("null-value"));
    }

    // ---- helpers ----------------------------------------------------

    /** Bind {@code bound} to a single-column INSERT, read it back with
     *  {@link TrackerValueMarshaller#encode}, and assert the round-trip
     *  preserves the arm + payload. */
    private void insertAndAssert(final TrackerSchema.ColumnDef col,
                                 final ComponentVal bound,
                                 final String columnName) throws SQLException {
        try (Statement st = conn.createStatement()) { st.executeUpdate("DELETE FROM t"); }
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO t (" + columnName + ") VALUES (?)")) {
            TrackerValueMarshaller.bind(ins, 1, col, bound);
            ins.executeUpdate();
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + columnName + " FROM t")) {
            assertThat(rs.next()).isTrue();
            final ComponentVal out = TrackerValueMarshaller.encode(rs, 1, col);
            final ComponentVariant inV = bound.asVariant();
            final ComponentVariant outV = out.asVariant();
            assertThat(outV.getCaseName()).isEqualTo(inV.getCaseName());
            // Compare payloads by shape.
            switch (inV.getCaseName()) {
                case "text-value":
                    assertThat(outV.getPayload().orElseThrow().asString())
                            .isEqualTo(inV.getPayload().orElseThrow().asString());
                    break;
                case "integer-value":
                    assertThat(outV.getPayload().orElseThrow().asS64())
                            .isEqualTo(inV.getPayload().orElseThrow().asS64());
                    break;
                case "real-value":
                    assertThat(outV.getPayload().orElseThrow().asF64())
                            .isEqualTo(inV.getPayload().orElseThrow().asF64());
                    break;
                case "blob-value":
                    assertThat(outV.getPayload().orElseThrow().asByteArray())
                            .isEqualTo(inV.getPayload().orElseThrow().asByteArray());
                    break;
                default:
                    throw new AssertionError("unexpected variant: " + inV.getCaseName());
            }
        }
    }
}
