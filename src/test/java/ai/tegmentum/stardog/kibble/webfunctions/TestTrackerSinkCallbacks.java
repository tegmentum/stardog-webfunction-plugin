package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentResult;
import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.wasmtime4j.component.ComponentVariant;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Wave B coverage for {@code tracker-sink-callbacks}. Drives the
 * {@link HostCallbacks} lambdas directly against a per-test SQLite
 * file (via TemporaryFolder) with the singleton
 * {@link SqliteTrackerBackend} pre-opened by {@link #setUp()}.
 *
 * <p>Covers all six methods (register-tracker-tables, tracker-insert,
 * tracker-upsert, tracker-select, tracker-delete, tracker-count),
 * plus the two error surfaces (typed {@code tracker-error} variants and
 * capability short-circuit) that make this the security-critical
 * surface Wave B lands. Mirrors the shape of
 * {@link TestSinkCallbacks} / {@link TestFulltextCallbacks}.
 */
public class TestTrackerSinkCallbacks {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() throws Exception {
        CapabilityAttributionRing.INSTANCE.clear();
        CapabilityAttributionRing.INSTANCE.setEnabled(false);
        CapabilityEnforcer.install(null);
        CapabilityEnforcer.setEnabled(false);
        ThreadContext.unbindSubject();
        SqliteTrackerBackend.INSTANCE.reset();
        final File dbFile = tmp.newFile("tracker.sqlite");
        dbFile.delete();
        SqliteTrackerBackend.INSTANCE.open(dbFile.getAbsolutePath(),
                List.of("canon", "materialize"));
    }

    @After
    public void tearDown() {
        CapabilityEnforcer.install(null);
        CapabilityEnforcer.setEnabled(false);
        CapabilityAttributionRing.INSTANCE.clear();
        ThreadContext.unbindSubject();
        SqliteTrackerBackend.INSTANCE.reset();
        final CallbackContext ctx = CallbackContext.current();
        if (ctx != null) CallbackContext.unbindIfOutermost(ctx);
    }

    // ---- register-tracker-tables ------------------------------------

    @Test
    public void registerTablesUnknownSinkReturnsNoSuchSink() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.trackerRegisterTables().execute(new Object[] {
                ComponentVal.string("stranger"),
                ComponentVal.list(List.of(aliasTableWit()))
        });
        assertErr(out, "no-such-sink", "stranger");
    }

    @Test
    public void registerTablesHappyPath() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.trackerRegisterTables().execute(new Object[] {
                ComponentVal.string("canon"),
                ComponentVal.list(List.of(aliasTableWit()))
        });
        assertOk(out);
        assertThat(SqliteTrackerBackend.INSTANCE.hasTable("canon", "aliases")).isTrue();
    }

    @Test
    public void registerTablesInjectionShapedTableRejected() {
        CallbackContext.bind();
        final ComponentVal bad = tableRecord("aliases; DROP TABLE users",
                List.of(columnRecord("alias", "text", true, false)),
                List.of());
        final Object[] out = HostCallbacks.trackerRegisterTables().execute(new Object[] {
                ComponentVal.string("canon"),
                ComponentVal.list(List.of(bad))
        });
        assertErr(out, "schema-violation", "DROP");
    }

    // ---- tracker-insert / tracker-upsert ----------------------------

    @Test
    public void insertUnknownSinkReturnsNoSuchSink() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.trackerInsert().execute(new Object[] {
                ComponentVal.string("stranger"),
                ComponentVal.string("aliases"),
                rowRecord(List.of(text("x"), text("y")))
        });
        assertErr(out, "no-such-sink", "stranger");
    }

    @Test
    public void insertHappyPath() {
        CallbackContext.bind();
        registerAliases();
        final Object[] out = HostCallbacks.trackerInsert().execute(new Object[] {
                ComponentVal.string("canon"),
                ComponentVal.string("aliases"),
                rowRecord(List.of(text("http://ex/a"), text("http://ex/canon_1")))
        });
        assertOk(out);
        assertThat(SqliteTrackerBackend.INSTANCE.countRows("canon", "aliases", List.of()))
                .isEqualTo(1L);
    }

    @Test
    public void insertDuplicatePkReturnsBackendError() {
        CallbackContext.bind();
        registerAliases();
        // First insert succeeds
        HostCallbacks.trackerInsert().execute(new Object[] {
                ComponentVal.string("canon"),
                ComponentVal.string("aliases"),
                rowRecord(List.of(text("http://ex/a"), text("v1")))
        });
        // Second insert on same PK -> backend-error (UNIQUE constraint failed)
        final Object[] out = HostCallbacks.trackerInsert().execute(new Object[] {
                ComponentVal.string("canon"),
                ComponentVal.string("aliases"),
                rowRecord(List.of(text("http://ex/a"), text("v2")))
        });
        assertErr(out, "backend-error", null);
    }

    @Test
    public void upsertReplacesOnPkCollision() {
        CallbackContext.bind();
        registerAliases();
        HostCallbacks.trackerUpsert().execute(new Object[] {
                ComponentVal.string("canon"),
                ComponentVal.string("aliases"),
                rowRecord(List.of(text("http://ex/a"), text("v1")))
        });
        final Object[] out = HostCallbacks.trackerUpsert().execute(new Object[] {
                ComponentVal.string("canon"),
                ComponentVal.string("aliases"),
                rowRecord(List.of(text("http://ex/a"), text("v2")))
        });
        assertOk(out);
        assertThat(SqliteTrackerBackend.INSTANCE.countRows("canon", "aliases", List.of()))
                .isEqualTo(1L);
    }

    // ---- tracker-select ---------------------------------------------

    @Test
    public void selectReturnsRowsAsTrackerRowRecords() {
        CallbackContext.bind();
        registerAliases();
        SqliteTrackerBackend.INSTANCE.upsertRow("canon", "aliases",
                List.of(textV("a"), textV("c1")));
        SqliteTrackerBackend.INSTANCE.upsertRow("canon", "aliases",
                List.of(textV("b"), textV("c2")));

        final Object[] out = HostCallbacks.trackerSelect().execute(new Object[] {
                ComponentVal.string("canon"),
                ComponentVal.string("aliases"),
                ComponentVal.list(new ArrayList<>()),
                ComponentVal.list(new ArrayList<>())
        });
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
        final List<ComponentVal> rows = result.getOk().orElseThrow().asList();
        assertThat(rows).hasSize(2);
        // Each row is a record with a `values` list.
        final Map<String, ComponentVal> firstRow = rows.get(0).asRecord();
        assertThat(firstRow.get("values").asList()).hasSize(2);
    }

    @Test
    public void selectProjectionRespectsRequestedOrder() {
        CallbackContext.bind();
        registerAliases();
        SqliteTrackerBackend.INSTANCE.upsertRow("canon", "aliases",
                List.of(textV("a"), textV("c1")));
        final Object[] out = HostCallbacks.trackerSelect().execute(new Object[] {
                ComponentVal.string("canon"),
                ComponentVal.string("aliases"),
                ComponentVal.list(new ArrayList<>()),
                ComponentVal.list(List.of(ComponentVal.string("canonical"),
                                          ComponentVal.string("alias")))
        });
        final List<ComponentVal> rows = ((ComponentVal) out[0]).asResult()
                .getOk().orElseThrow().asList();
        final List<ComponentVal> values = rows.get(0).asRecord().get("values").asList();
        assertThat(values.get(0).asVariant().getPayload().orElseThrow().asString())
                .isEqualTo("c1");
        assertThat(values.get(1).asVariant().getPayload().orElseThrow().asString())
                .isEqualTo("a");
    }

    @Test
    public void selectUnknownTableReturnsNoSuchTable() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.trackerSelect().execute(new Object[] {
                ComponentVal.string("canon"),
                ComponentVal.string("nowhere"),
                ComponentVal.list(new ArrayList<>()),
                ComponentVal.list(new ArrayList<>())
        });
        assertErr(out, "no-such-table", "nowhere");
    }

    @Test
    public void selectUnsafeOperatorRejected() {
        CallbackContext.bind();
        registerAliases();
        final Map<String, ComponentVal> hostileClause = new LinkedHashMap<>();
        hostileClause.put("column", ComponentVal.string("alias"));
        hostileClause.put("operator", ComponentVal.string("; DROP TABLE users --"));
        hostileClause.put("value", ComponentVal.some(text("x")));
        final Object[] out = HostCallbacks.trackerSelect().execute(new Object[] {
                ComponentVal.string("canon"),
                ComponentVal.string("aliases"),
                ComponentVal.list(List.of(ComponentVal.record(hostileClause))),
                ComponentVal.list(new ArrayList<>())
        });
        assertErr(out, "schema-violation", null);
    }

    // ---- tracker-delete + tracker-count -----------------------------

    @Test
    public void deleteWithWhereReturnsRemovedCount() {
        CallbackContext.bind();
        registerAliases();
        SqliteTrackerBackend.INSTANCE.upsertRow("canon", "aliases", List.of(textV("a"), textV("c1")));
        SqliteTrackerBackend.INSTANCE.upsertRow("canon", "aliases", List.of(textV("b"), textV("c1")));
        SqliteTrackerBackend.INSTANCE.upsertRow("canon", "aliases", List.of(textV("c"), textV("c2")));

        final Map<String, ComponentVal> clause = whereRecord("canonical", "=",
                text("c1"));
        final Object[] out = HostCallbacks.trackerDelete().execute(new Object[] {
                ComponentVal.string("canon"),
                ComponentVal.string("aliases"),
                ComponentVal.list(List.of(ComponentVal.record(clause)))
        });
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
        assertThat(result.getOk().orElseThrow().asU32()).isEqualTo(2L);
    }

    @Test
    public void countHappyPath() {
        CallbackContext.bind();
        registerAliases();
        SqliteTrackerBackend.INSTANCE.upsertRow("canon", "aliases", List.of(textV("a"), textV("c")));
        SqliteTrackerBackend.INSTANCE.upsertRow("canon", "aliases", List.of(textV("b"), textV("c")));

        final Object[] out = HostCallbacks.trackerCount().execute(new Object[] {
                ComponentVal.string("canon"),
                ComponentVal.string("aliases"),
                ComponentVal.list(new ArrayList<>())
        });
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
        assertThat(result.getOk().orElseThrow().asU64()).isEqualTo(2L);
    }

    @Test
    public void countUnknownSinkReturnsNoSuchSink() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.trackerCount().execute(new Object[] {
                ComponentVal.string("stranger"),
                ComponentVal.string("aliases"),
                ComponentVal.list(new ArrayList<>())
        });
        assertErr(out, "no-such-sink", "stranger");
    }

    // ---- capability enforcement -------------------------------------

    @Test
    public void capabilityDenialShortCircuitsBeforeDispatch() {
        // With capability on and tracker-sink-callbacks denied,
        // enforceCapability throws PerCallDenied BEFORE the handler
        // runs — proves the gate fires ahead of the real impl.
        CapabilityEnforcer.install(CapabilityEnforcer.create());
        CapabilityEnforcer.setEnabled(true);
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(grantWithInterfaces(Set.of("graph-callbacks")));

        final Throwable thrown = catchThrowable(() ->
                HostCallbacks.trackerRegisterTables().execute(new Object[] {
                        ComponentVal.string("canon"),
                        ComponentVal.list(new ArrayList<>())
                }));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        assertThat(((WfCapabilityError.PerCallDenied) thrown).interfaceName())
                .isEqualTo("tracker-sink-callbacks");
        // Sanity: handler did NOT run, so no schema landed.
        assertThat(SqliteTrackerBackend.INSTANCE.hasTable("canon", "aliases")).isFalse();
    }

    @Test
    public void capabilityDenialShortCircuitsInsertBeforeDispatch() {
        CapabilityEnforcer.install(CapabilityEnforcer.create());
        CapabilityEnforcer.setEnabled(true);
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(grantWithInterfaces(Set.of("graph-callbacks")));

        final Throwable thrown = catchThrowable(() ->
                HostCallbacks.trackerInsert().execute(new Object[] {
                        ComponentVal.string("canon"),
                        ComponentVal.string("aliases"),
                        rowRecord(List.of(text("x"), text("y")))
                }));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        assertThat(((WfCapabilityError.PerCallDenied) thrown).interfaceName())
                .isEqualTo("tracker-sink-callbacks");
    }

    @Test
    public void capabilityDenialShortCircuitsCountBeforeDispatch() {
        CapabilityEnforcer.install(CapabilityEnforcer.create());
        CapabilityEnforcer.setEnabled(true);
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(grantWithInterfaces(Set.of("graph-callbacks")));

        final Throwable thrown = catchThrowable(() ->
                HostCallbacks.trackerCount().execute(new Object[] {
                        ComponentVal.string("canon"),
                        ComponentVal.string("aliases"),
                        ComponentVal.list(new ArrayList<>())
                }));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
    }

    // ---- WIT helpers -------------------------------------------------

    private static void registerAliases() {
        SqliteTrackerBackend.INSTANCE.registerTables("canon", List.of(new TrackerSchema(
                "aliases", List.of(
                        new TrackerSchema.ColumnDef("alias",
                                TrackerSchema.ColumnType.TEXT, true, false),
                        new TrackerSchema.ColumnDef("canonical",
                                TrackerSchema.ColumnType.TEXT, false, false)))));
    }

    /** Build a WIT {@code tracker-table-schema} record for the alias
     *  map — matches {@link #registerAliases}. */
    private static ComponentVal aliasTableWit() {
        return tableRecord("aliases", List.of(
                columnRecord("alias", "text", true, false),
                columnRecord("canonical", "text", false, false)),
                List.of());
    }

    private static ComponentVal tableRecord(final String name,
                                            final List<ComponentVal> columns,
                                            final List<ComponentVal> indexes) {
        final Map<String, ComponentVal> rec = new LinkedHashMap<>();
        rec.put("name", ComponentVal.string(name));
        rec.put("columns", ComponentVal.list(columns));
        rec.put("indexes", ComponentVal.list(indexes));
        return ComponentVal.record(rec);
    }

    private static ComponentVal columnRecord(final String name,
                                             final String typeCase,
                                             final boolean primaryKey,
                                             final boolean nullable) {
        final Map<String, ComponentVal> rec = new LinkedHashMap<>();
        rec.put("name", ComponentVal.string(name));
        // wit-bindgen emits enum-shaped variants as
        // ComponentVal.variant(caseName) with no payload — matches the
        // decodeColumnType() path in HostCallbacks.
        rec.put("column-type", ComponentVal.variant(typeCase));
        rec.put("primary-key", ComponentVal.bool(primaryKey));
        rec.put("nullable", ComponentVal.bool(nullable));
        return ComponentVal.record(rec);
    }

    /** Build a WIT {@code tracker-row} record. */
    private static ComponentVal rowRecord(final List<ComponentVal> values) {
        final Map<String, ComponentVal> rec = new LinkedHashMap<>();
        rec.put("values", ComponentVal.list(values));
        return ComponentVal.record(rec);
    }

    /** Build a WIT {@code tracker-value::text-value(...)} variant. */
    private static ComponentVal text(final String s) {
        return ComponentVal.variant("text-value", ComponentVal.string(s));
    }

    /** Same as {@link #text} but distinct name — used inside
     *  hand-built value lists we hand directly to the backend (rather
     *  than through the WIT-encoding row record). */
    private static ComponentVal textV(final String s) {
        return ComponentVal.variant("text-value", ComponentVal.string(s));
    }

    private static Map<String, ComponentVal> whereRecord(final String column,
                                                         final String operator,
                                                         final ComponentVal value) {
        final Map<String, ComponentVal> rec = new LinkedHashMap<>();
        rec.put("column", ComponentVal.string(column));
        rec.put("operator", ComponentVal.string(operator));
        rec.put("value", ComponentVal.some(value));
        return rec;
    }

    private static CapabilityGrant grantWithInterfaces(final Set<String> allowedInterfaces) {
        return new CapabilityGrant(
                "file:///ext.wasm",
                allowedInterfaces,
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                HttpPathAllowlist.ALLOW_NONE,
                WasmCalleeAllowlist.ALLOW_NONE,
                "alice",
                CapabilityModel.INVOKER_SUBJECT);
    }

    // ---- ok/err assertions -------------------------------------------

    private static void assertOk(final Object[] out) {
        assertThat(out).hasSize(1);
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk())
                .as("expected ok, got err: %s",
                        result.getErr().map(v -> v.asVariant().getCaseName()).orElse("<absent>"))
                .isTrue();
    }

    private static void assertErr(final Object[] out,
                                  final String expectedArm,
                                  final String expectedInMessage) {
        assertThat(out).hasSize(1);
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.getErr()).as("expected result::err").isPresent();
        final ComponentVariant err = result.getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo(expectedArm);
        if (expectedInMessage != null) {
            final String msg = err.getPayload().orElseThrow().asString();
            assertThat(msg).contains(expectedInMessage);
        }
    }
}
