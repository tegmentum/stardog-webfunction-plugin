package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Wave B unit tests for {@link TrackerSchema}. Pure JVM — no Stardog,
 * no wasmtime, no SQLite connection. Every path through
 * {@link TrackerSchema#validateIdent(String)},
 * {@link TrackerSchema#validate()}, and
 * {@link TrackerSchema#renderCreateTable(String, String)} lands here
 * so higher-tier tests can trust the injection boundary.
 */
public class TestTrackerSchema {

    // ---- validateIdent -----------------------------------------------

    @Test
    public void validIdentifiersAccepted() {
        TrackerSchema.validateIdent("a");
        TrackerSchema.validateIdent("A");
        TrackerSchema.validateIdent("_");
        TrackerSchema.validateIdent("_underscore_start");
        TrackerSchema.validateIdent("mix123_ABC");
        TrackerSchema.validateIdent("0numeric_start"); // SQLite accepts; regex accepts
    }

    @Test
    public void emptyIdentifierRejected() {
        assertThat(catchThrowable(() -> TrackerSchema.validateIdent("")))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class)
                .hasMessageContaining("empty");
    }

    @Test
    public void nullIdentifierRejected() {
        assertThat(catchThrowable(() -> TrackerSchema.validateIdent(null)))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class)
                .hasMessageContaining("empty");
    }

    @Test
    public void injectionShapedNamesRejected() {
        // The security-critical cases — anything that would let an
        // attacker escape identifier-position interpolation.
        final String[] hostile = {
                "aliases; DROP TABLE users --",
                "aliases--",
                "aliases /* comment */",
                "aliases\"",
                "aliases`",
                "aliases'",
                "aliases ",           // trailing space
                " aliases",           // leading space
                "aliases\n",          // newline
                "sink.tables",        // dot injection
                "aliases[",           // bracket
                "aliases)",           // close paren
                "aliases;",           // bare semicolon
        };
        for (final String bad : hostile) {
            final Throwable thrown = catchThrowable(() -> TrackerSchema.validateIdent(bad));
            assertThat(thrown)
                    .as("must reject: %s", bad)
                    .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class);
        }
    }

    // ---- validate (whole schema) -------------------------------------

    @Test
    public void emptyColumnListRejected() {
        final TrackerSchema s = new TrackerSchema("t", List.of());
        assertThat(catchThrowable(s::validate))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class)
                .hasMessageContaining("zero columns");
    }

    @Test
    public void duplicateColumnNameRejected() {
        final TrackerSchema s = new TrackerSchema("t", List.of(
                new TrackerSchema.ColumnDef("x", TrackerSchema.ColumnType.TEXT, false, false),
                new TrackerSchema.ColumnDef("x", TrackerSchema.ColumnType.INTEGER, false, false)));
        assertThat(catchThrowable(s::validate))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class)
                .hasMessageContaining("more than once");
    }

    @Test
    public void columnNameInjectionRejected() {
        final TrackerSchema s = new TrackerSchema("t", List.of(
                new TrackerSchema.ColumnDef("ok", TrackerSchema.ColumnType.TEXT, false, false),
                new TrackerSchema.ColumnDef("bad; DROP",
                        TrackerSchema.ColumnType.TEXT, false, false)));
        assertThat(catchThrowable(s::validate))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class);
    }

    @Test
    public void tableNameInjectionRejected() {
        final TrackerSchema s = new TrackerSchema("t; DROP", List.of(
                new TrackerSchema.ColumnDef("x", TrackerSchema.ColumnType.TEXT, false, false)));
        assertThat(catchThrowable(s::validate))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class);
    }

    @Test
    public void indexReferencingUnknownColumnRejected() {
        final TrackerSchema s = new TrackerSchema("t",
                List.of(new TrackerSchema.ColumnDef("x", TrackerSchema.ColumnType.TEXT, false, false)),
                List.of(new TrackerSchema.IndexDef("by_y", List.of("y"), false)));
        assertThat(catchThrowable(s::validate))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class)
                .hasMessageContaining("not declared");
    }

    @Test
    public void indexWithEmptyColumnsRejected() {
        final TrackerSchema s = new TrackerSchema("t",
                List.of(new TrackerSchema.ColumnDef("x", TrackerSchema.ColumnType.TEXT, false, false)),
                List.of(new TrackerSchema.IndexDef("empty", List.of(), false)));
        assertThat(catchThrowable(s::validate))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class)
                .hasMessageContaining("no columns");
    }

    @Test
    public void validSchemaWithIndexPasses() {
        final TrackerSchema s = new TrackerSchema("aliases",
                List.of(
                        new TrackerSchema.ColumnDef("alias", TrackerSchema.ColumnType.TEXT, true, false),
                        new TrackerSchema.ColumnDef("canonical", TrackerSchema.ColumnType.TEXT, false, false),
                        new TrackerSchema.ColumnDef("updated_at", TrackerSchema.ColumnType.INTEGER, false, true)),
                List.of(new TrackerSchema.IndexDef("by_canonical",
                        List.of("canonical"), false)));
        s.validate(); // no throw
    }

    // ---- renderCreateTable -------------------------------------------

    @Test
    public void renderCreateTableProducesExpectedDdl() {
        final TrackerSchema s = new TrackerSchema("aliases", List.of(
                new TrackerSchema.ColumnDef("alias", TrackerSchema.ColumnType.TEXT, true, false),
                new TrackerSchema.ColumnDef("canonical", TrackerSchema.ColumnType.TEXT, false, false)));
        final String ddl = s.renderCreateTable("sink_canon_tracker_aliases", "");
        // Order-preserving, exact-shape assertion.
        assertThat(ddl).isEqualTo(
                "CREATE TABLE IF NOT EXISTS sink_canon_tracker_aliases "
                + "(alias TEXT NOT NULL, canonical TEXT NOT NULL, PRIMARY KEY (alias))");
    }

    @Test
    public void renderCreateTableNullableColumnOmitsNotNull() {
        final TrackerSchema s = new TrackerSchema("t", List.of(
                new TrackerSchema.ColumnDef("x", TrackerSchema.ColumnType.INTEGER, false, true)));
        final String ddl = s.renderCreateTable("t_physical", "");
        assertThat(ddl).isEqualTo("CREATE TABLE IF NOT EXISTS t_physical (x INTEGER)");
    }

    @Test
    public void renderCreateTableCompositePrimaryKey() {
        final TrackerSchema s = new TrackerSchema("t", List.of(
                new TrackerSchema.ColumnDef("a", TrackerSchema.ColumnType.TEXT, true, false),
                new TrackerSchema.ColumnDef("b", TrackerSchema.ColumnType.INTEGER, true, false),
                new TrackerSchema.ColumnDef("data", TrackerSchema.ColumnType.BLOB, false, true)));
        final String ddl = s.renderCreateTable("t_physical", "");
        assertThat(ddl).contains("PRIMARY KEY (a, b)");
        assertThat(ddl).contains("data BLOB");
    }

    // ---- renderCreateIndex -------------------------------------------

    @Test
    public void renderCreateIndexPlainProducesExpectedDdl() {
        final TrackerSchema.IndexDef idx = new TrackerSchema.IndexDef(
                "by_canonical", List.of("canonical"), false);
        final String ddl = TrackerSchema.renderCreateIndex(
                "idx_sink_canon_tracker_aliases_by_canonical",
                "sink_canon_tracker_aliases", idx);
        assertThat(ddl).isEqualTo(
                "CREATE INDEX IF NOT EXISTS idx_sink_canon_tracker_aliases_by_canonical "
                + "ON sink_canon_tracker_aliases (canonical)");
    }

    @Test
    public void renderCreateIndexUniqueEmitsUniqueKeyword() {
        final TrackerSchema.IndexDef idx = new TrackerSchema.IndexDef(
                "by_ab", List.of("a", "b"), true);
        final String ddl = TrackerSchema.renderCreateIndex("idx_ab", "t_physical", idx);
        assertThat(ddl).isEqualTo(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_ab ON t_physical (a, b)");
    }
}
