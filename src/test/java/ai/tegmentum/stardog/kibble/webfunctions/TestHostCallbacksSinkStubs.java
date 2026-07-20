package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentResult;
import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.wasmtime4j.component.ComponentVariant;
import ai.tegmentum.webassembly4j.api.WitHostFunction;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MVP-stub coverage for the two host-callback interfaces still in stub
 * shape after Wave A: {@code tracker-sink-callbacks} (Wave B —
 * SQLite-backed tracker registry) and {@code fulltext-callbacks}
 * (Wave C — Stardog BITES adapter, once its plugin-side surface
 * exists).
 *
 * <p>Sink-family write / read / document interfaces have moved to
 * {@link TestSinkCallbacks}, {@link TestSinkQueryCallbacks}, and
 * {@link TestDocumentSinkCallbacks} respectively; every dispatch there
 * routes through the {@link SinkRegistry} rather than short-circuiting
 * to {@code not-permitted}.
 *
 * <p>Drives the {@link WitHostFunction#execute} entry point directly
 * — the stubs are pure Java lambdas with no wasm engine dependency,
 * so tests exercise the full dispatch shape without bootstrapping
 * wasmtime4j.
 */
public class TestHostCallbacksSinkStubs {

    @Before
    public void setUp() {
        CapabilityAttributionRing.INSTANCE.clear();
        CapabilityAttributionRing.INSTANCE.setEnabled(false);
        CapabilityEnforcer.install(null);
        CapabilityEnforcer.setEnabled(false);
        ThreadContext.unbindSubject();
    }

    @After
    public void tearDown() {
        CapabilityEnforcer.install(null);
        CapabilityEnforcer.setEnabled(false);
        CapabilityAttributionRing.INSTANCE.clear();
        ThreadContext.unbindSubject();
        final CallbackContext ctx = CallbackContext.current();
        if (ctx != null) CallbackContext.unbindIfOutermost(ctx);
    }

    // sink-callbacks, sink-query-callbacks, and document-sink-callbacks
    // are no longer stubs — see TestSinkCallbacks / TestSinkQueryCallbacks /
    // TestDocumentSinkCallbacks for the Wave A real-impl coverage. This
    // file retains coverage of the still-stubbed families: tracker-sink-
    // callbacks (Wave B) and fulltext-callbacks (Wave C).

    // ---- tracker-sink-callbacks --------------------------------------

    @Test
    public void trackerRegisterTablesReturnsNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind();
        final Object[] out = HostCallbacks.trackerRegisterTables().execute(new Object[] {
                ComponentVal.string("my-sink"),
                ComponentVal.list(new ArrayList<>())
        });
        assertNotPermitted(out, "tracker-sink-callbacks", "register-tracker-tables");
    }

    @Test
    public void trackerInsertReturnsNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind();
        final Object[] out = HostCallbacks.trackerInsert().execute(new Object[] {
                ComponentVal.string("my-sink"),
                ComponentVal.string("my-table"),
                ComponentVal.list(new ArrayList<>())
        });
        assertNotPermitted(out, "tracker-sink-callbacks", "tracker-insert");
    }

    @Test
    public void trackerUpsertReturnsNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind();
        final Object[] out = HostCallbacks.trackerUpsert().execute(new Object[] {
                ComponentVal.string("my-sink"),
                ComponentVal.string("my-table"),
                ComponentVal.list(new ArrayList<>())
        });
        assertNotPermitted(out, "tracker-sink-callbacks", "tracker-upsert");
    }

    @Test
    public void trackerSelectReturnsNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind();
        final Object[] out = HostCallbacks.trackerSelect().execute(new Object[] {
                ComponentVal.string("my-sink"),
                ComponentVal.string("my-table"),
                ComponentVal.list(new ArrayList<>()),
                ComponentVal.list(new ArrayList<>())
        });
        assertNotPermitted(out, "tracker-sink-callbacks", "tracker-select");
    }

    @Test
    public void trackerDeleteReturnsNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind();
        final Object[] out = HostCallbacks.trackerDelete().execute(new Object[] {
                ComponentVal.string("my-sink"),
                ComponentVal.string("my-table"),
                ComponentVal.list(new ArrayList<>())
        });
        assertNotPermitted(out, "tracker-sink-callbacks", "tracker-delete");
    }

    @Test
    public void trackerCountReturnsNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind();
        final Object[] out = HostCallbacks.trackerCount().execute(new Object[] {
                ComponentVal.string("my-sink"),
                ComponentVal.string("my-table"),
                ComponentVal.list(new ArrayList<>())
        });
        assertNotPermitted(out, "tracker-sink-callbacks", "tracker-count");
    }

    // ---- fulltext-callbacks ------------------------------------------

    @Test
    public void fulltextInsertDocumentsReturnsNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind();
        final Object[] out = HostCallbacks.fulltextInsertDocuments().execute(new Object[] {
                ComponentVal.string("my-index"),
                ComponentVal.list(new ArrayList<>())
        });
        assertNotPermitted(out, "fulltext-callbacks", "insert-documents");
    }

    @Test
    public void fulltextDeleteDocumentsReturnsNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind();
        final Object[] out = HostCallbacks.fulltextDeleteDocuments().execute(new Object[] {
                ComponentVal.string("my-index"),
                ComponentVal.list(new ArrayList<>())
        });
        assertNotPermitted(out, "fulltext-callbacks", "delete-documents");
    }

    @Test
    public void fulltextSearchIndexReturnsNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind();
        final Object[] out = HostCallbacks.fulltextSearchIndex().execute(new Object[] {
                ComponentVal.string("my-index"),
                ComponentVal.string("some query"),
                ComponentVal.none()
        });
        assertNotPermitted(out, "fulltext-callbacks", "search-index");
    }

    // ---- helpers ------------------------------------------------------

    /** Assert the {@code Object[]} returned by a WitHostFunction has the
     *  shape {@code [result::err(<iface>-error::not-permitted(msg))]}
     *  and the message mentions the interface + method for operator
     *  clarity. */
    private static void assertNotPermitted(final Object[] out,
                                           final String expectedInterfaceInMessage,
                                           final String expectedMethodInMessage) {
        assertThat(out).hasSize(1);
        final ComponentVal top = (ComponentVal) out[0];
        final ComponentResult result = top.asResult();
        assertThat(result.getErr()).as("stub must return result::err").isPresent();
        final ComponentVariant err = result.getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("not-permitted");
        final String msg = err.getPayload().orElseThrow().asString();
        assertThat(msg).contains(expectedInterfaceInMessage);
        assertThat(msg).contains(expectedMethodInMessage);
    }

}
