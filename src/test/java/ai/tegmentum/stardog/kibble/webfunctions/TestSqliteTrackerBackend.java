package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Wave B integration tests for {@link SqliteTrackerBackend}. Uses a
 * per-test file (TemporaryFolder) so registrations don't leak between
 * tests — production wiring uses one backend for the plugin's
 * lifetime, but the test isolation is per-file.
 */
public class TestSqliteTrackerBackend {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private SqliteTrackerBackend backend;

    @Before
    public void setUp() throws Exception {
        backend = SqliteTrackerBackend.INSTANCE;
        backend.reset();
        final File dbFile = tmp.newFile("tracker.sqlite");
        // Delete so open() creates a fresh SQLite database.
        dbFile.delete();
        backend.open(dbFile.getAbsolutePath(), List.of("canon", "materialize"));
    }

    @After
    public void tearDown() {
        backend.reset();
    }

    // ---- open + allowlist -------------------------------------------

    @Test
    public void openThenIsOpen() {
        assertThat(backend.isOpen()).isTrue();
        assertThat(backend.allowedSinkNames()).containsExactly("canon", "materialize");
    }

    @Test
    public void injectionShapedSinkNameRejectedAtOpen() {
        backend.reset();
        assertThat(catchThrowable(() ->
                backend.open(":memory:", List.of("canon; DROP TABLE users --"))))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class);
        assertThat(backend.isOpen()).isFalse();
    }

    @Test
    public void resetClosesConnectionAndClearsAllowlist() {
        backend.reset();
        assertThat(backend.isOpen()).isFalse();
        assertThat(backend.allowedSinkNames()).isEmpty();
    }

    @Test
    public void reopenIsIdempotentUnderCloseThenReopen() {
        // Simulate a plugin re-install: open a second time on the same
        // file. Prior connection is closed cleanly and schemas dropped.
        backend.registerTables("canon", List.of(aliasMapSchema()));
        assertThat(backend.hasTable("canon", "aliases")).isTrue();
        // Reopen (same path) drops the cached schemas.
        backend.open(":memory:", List.of("canon"));
        assertThat(backend.hasTable("canon", "aliases")).isFalse();
    }

    // ---- register-tables --------------------------------------------

    @Test
    public void registerTablesUnknownSinkSurfacesNoSuchSink() {
        assertThat(catchThrowable(() ->
                backend.registerTables("stranger", List.of(aliasMapSchema()))))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.NoSuchSink.class)
                .hasMessageContaining("stranger");
    }

    @Test
    public void registerTablesCachesSchemaAndMaterializesDdl() {
        backend.registerTables("canon", List.of(aliasMapSchema(), sweepSchema()));
        assertThat(backend.hasTable("canon", "aliases")).isTrue();
        assertThat(backend.hasTable("canon", "sweep")).isTrue();
        // Round-trip a count on both — proves the DDL landed.
        assertThat(backend.countRows("canon", "aliases", List.of())).isEqualTo(0L);
        assertThat(backend.countRows("canon", "sweep", List.of())).isEqualTo(0L);
    }

    @Test
    public void registerTablesIdempotentUnderReDeclaration() {
        // Same shape twice — CREATE TABLE IF NOT EXISTS makes this safe.
        backend.registerTables("canon", List.of(aliasMapSchema()));
        backend.registerTables("canon", List.of(aliasMapSchema()));
        assertThat(backend.countRows("canon", "aliases", List.of())).isEqualTo(0L);
    }

    @Test
    public void schemaFieldValidationInvokedBeforeAnyDdl() {
        // Injection-shaped table name is rejected before any DDL runs.
        final TrackerSchema bad = new TrackerSchema("aliases; DROP", List.of(
                new TrackerSchema.ColumnDef("alias", TrackerSchema.ColumnType.TEXT, true, false)));
        assertThat(catchThrowable(() -> backend.registerTables("canon", List.of(bad))))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class);
        assertThat(backend.hasTable("canon", "aliases; DROP")).isFalse();
    }

    // ---- insert / upsert --------------------------------------------

    @Test
    public void insertThenSelectRoundTrip() {
        backend.registerTables("canon", List.of(aliasMapSchema()));
        backend.insertRow("canon", "aliases", List.of(text("http://ex/a"), text("http://ex/canon_1")));
        backend.insertRow("canon", "aliases", List.of(text("http://ex/b"), text("http://ex/canon_1")));

        final List<List<ComponentVal>> rows = backend.selectRows(
                "canon", "aliases", List.of(), List.of());
        assertThat(rows).hasSize(2);
        // Row 1 shape: 2 text values, matching schema declaration order.
        assertThat(rows.get(0)).hasSize(2);
        assertThat(rows.get(0).get(0).asVariant().getCaseName()).isEqualTo("text-value");
    }

    @Test
    public void insertDuplicatePkFailsWithBackendError() {
        backend.registerTables("canon", List.of(aliasMapSchema()));
        backend.insertRow("canon", "aliases", List.of(text("http://ex/a"), text("v1")));
        assertThat(catchThrowable(() ->
                backend.insertRow("canon", "aliases", List.of(text("http://ex/a"), text("v2")))))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.BackendError.class);
    }

    @Test
    public void upsertReplacesOnPkCollision() {
        backend.registerTables("canon", List.of(aliasMapSchema()));
        backend.upsertRow("canon", "aliases", List.of(text("http://ex/a"), text("v1")));
        backend.upsertRow("canon", "aliases", List.of(text("http://ex/a"), text("v2")));

        final List<List<ComponentVal>> rows = backend.selectRows(
                "canon", "aliases",
                List.of(eqWhere("alias", text("http://ex/a"))),
                List.of("canonical"));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(0).asVariant().getPayload().orElseThrow().asString())
                .isEqualTo("v2");
    }

    @Test
    public void insertUnknownSinkSurfacesNoSuchSink() {
        assertThat(catchThrowable(() ->
                backend.insertRow("stranger", "aliases",
                        List.of(text("x"), text("y")))))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.NoSuchSink.class);
    }

    @Test
    public void insertUnknownTableSurfacesNoSuchTable() {
        assertThat(catchThrowable(() ->
                backend.insertRow("canon", "unknown_table",
                        List.of(text("x"), text("y")))))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.NoSuchTable.class);
    }

    @Test
    public void insertRowShapeMismatchSurfacesSchemaViolation() {
        backend.registerTables("canon", List.of(aliasMapSchema()));
        assertThat(catchThrowable(() ->
                backend.insertRow("canon", "aliases", List.of(text("only-one-value")))))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class)
                .hasMessageContaining("1 values");
    }

    // ---- select -----------------------------------------------------

    @Test
    public void selectProjectionRespectsRequestedOrder() {
        backend.registerTables("canon", List.of(aliasMapSchema()));
        backend.upsertRow("canon", "aliases", List.of(text("a"), text("c1")));
        // Ask for canonical FIRST, alias SECOND — schema declares them
        // in the opposite order, so this proves projection ordering.
        final List<List<ComponentVal>> rows = backend.selectRows(
                "canon", "aliases", List.of(), List.of("canonical", "alias"));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(0).asVariant().getPayload().orElseThrow().asString())
                .isEqualTo("c1");
        assertThat(rows.get(0).get(1).asVariant().getPayload().orElseThrow().asString())
                .isEqualTo("a");
    }

    @Test
    public void selectWithNullaryOperatorFilters() {
        backend.registerTables("canon", List.of(sweepSchema()));
        backend.upsertRow("canon", "sweep",
                List.of(text("s1"), text("h1"), ComponentVal.variant("integer-value", ComponentVal.s64(100L))));
        backend.upsertRow("canon", "sweep",
                List.of(text("s2"), text("h2"), ComponentVal.variant("null-value")));
        assertThat(backend.countRows("canon", "sweep",
                List.of(new TrackerWhere.Clause("updated_at", "IS NULL", Optional.empty()))))
                .isEqualTo(1L);
        assertThat(backend.countRows("canon", "sweep",
                List.of(new TrackerWhere.Clause("updated_at", "IS NOT NULL", Optional.empty()))))
                .isEqualTo(1L);
    }

    @Test
    public void selectUnknownColumnRejected() {
        backend.registerTables("canon", List.of(aliasMapSchema()));
        assertThat(catchThrowable(() ->
                backend.selectRows("canon", "aliases", List.of(),
                        List.of("nope"))))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class)
                .hasMessageContaining("nope");
    }

    // ---- delete + count ---------------------------------------------

    @Test
    public void deleteWithWhereReturnsCountAndRemoves() {
        backend.registerTables("canon", List.of(aliasMapSchema()));
        backend.upsertRow("canon", "aliases", List.of(text("a"), text("c1")));
        backend.upsertRow("canon", "aliases", List.of(text("b"), text("c1")));
        backend.upsertRow("canon", "aliases", List.of(text("c"), text("c2")));

        final long removed = backend.deleteRows("canon", "aliases",
                List.of(eqWhere("canonical", text("c1"))));
        assertThat(removed).isEqualTo(2L);
        assertThat(backend.countRows("canon", "aliases", List.of())).isEqualTo(1L);
    }

    @Test
    public void deleteEmptyWhereWipesTable() {
        backend.registerTables("canon", List.of(aliasMapSchema()));
        backend.upsertRow("canon", "aliases", List.of(text("a"), text("c")));
        backend.upsertRow("canon", "aliases", List.of(text("b"), text("c")));
        final long removed = backend.deleteRows("canon", "aliases", List.of());
        assertThat(removed).isEqualTo(2L);
        assertThat(backend.countRows("canon", "aliases", List.of())).isEqualTo(0L);
    }

    @Test
    public void countWithMultipleAndClauses() {
        backend.registerTables("canon", List.of(sweepSchema()));
        backend.upsertRow("canon", "sweep",
                List.of(text("s1"), text("hA"), ComponentVal.variant("integer-value", ComponentVal.s64(100L))));
        backend.upsertRow("canon", "sweep",
                List.of(text("s2"), text("hA"), ComponentVal.variant("integer-value", ComponentVal.s64(200L))));
        backend.upsertRow("canon", "sweep",
                List.of(text("s3"), text("hB"), ComponentVal.variant("integer-value", ComponentVal.s64(50L))));

        final long n = backend.countRows("canon", "sweep", List.of(
                new TrackerWhere.Clause("doc_hash", "=",
                        Optional.of(text("hA"))),
                new TrackerWhere.Clause("updated_at", ">",
                        Optional.of(ComponentVal.variant("integer-value", ComponentVal.s64(150L))))));
        assertThat(n).isEqualTo(1L);
    }

    // ---- injection surface (belt-and-suspenders) --------------------

    @Test
    public void whereClauseWithUnsafeOperatorRejected() {
        backend.registerTables("canon", List.of(aliasMapSchema()));
        assertThat(catchThrowable(() ->
                backend.countRows("canon", "aliases", List.of(
                        new TrackerWhere.Clause("alias", "; DROP TABLE t --",
                                Optional.of(text("x")))))))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class);
    }

    @Test
    public void whereClauseWithInjectionColumnRejected() {
        backend.registerTables("canon", List.of(aliasMapSchema()));
        assertThat(catchThrowable(() ->
                backend.countRows("canon", "aliases", List.of(
                        new TrackerWhere.Clause("alias; DROP TABLE t --", "=",
                                Optional.of(text("x")))))))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class);
    }

    // ---- physical naming --------------------------------------------

    @Test
    public void physicalNamingMatchesConvention() {
        assertThat(SqliteTrackerBackend.physicalTableName("canon", "aliases"))
                .isEqualTo("sink_canon_tracker_aliases");
        assertThat(SqliteTrackerBackend.physicalIndexName("canon", "aliases", "by_canonical"))
                .isEqualTo("idx_sink_canon_tracker_aliases_by_canonical");
    }

    // ---- helpers ----------------------------------------------------

    private static TrackerSchema aliasMapSchema() {
        return new TrackerSchema("aliases", List.of(
                new TrackerSchema.ColumnDef("alias", TrackerSchema.ColumnType.TEXT, true, false),
                new TrackerSchema.ColumnDef("canonical", TrackerSchema.ColumnType.TEXT, false, false)));
    }

    private static TrackerSchema sweepSchema() {
        return new TrackerSchema("sweep", List.of(
                new TrackerSchema.ColumnDef("subject_iri", TrackerSchema.ColumnType.TEXT, true, false),
                new TrackerSchema.ColumnDef("doc_hash", TrackerSchema.ColumnType.TEXT, false, false),
                new TrackerSchema.ColumnDef("updated_at", TrackerSchema.ColumnType.INTEGER, false, true)));
    }

    private static ComponentVal text(final String s) {
        return ComponentVal.variant("text-value", ComponentVal.string(s));
    }

    private static TrackerWhere.Clause eqWhere(final String col, final ComponentVal val) {
        return new TrackerWhere.Clause(col, "=", Optional.of(val));
    }
}
