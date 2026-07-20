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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * MVP-stub coverage for the five sink-family + fulltext host-callback
 * interfaces newly registered in {@link StardogWasmInstance}:
 * {@code sink-callbacks}, {@code sink-query-callbacks},
 * {@code document-sink-callbacks}, {@code tracker-sink-callbacks},
 * {@code fulltext-callbacks}.
 *
 * <p>All five interfaces land as stubs — the Stardog plugin does not
 * ship a sink registry, tracker backend, or fulltext-index adapter, so
 * every dispatch returns the interface's {@code not-permitted} error
 * arm with a descriptive message. Tests verify both the WIT-boundary
 * shape (result::err with the {@code not-permitted} case) and that
 * capability enforcement short-circuits before the stub body runs when
 * the interface is denied by policy.
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

    // ---- sink-callbacks -----------------------------------------------

    @Test
    public void sinkListSinksReturnsEmptyList() {
        // Under MVP the plugin has no sinks — list-sinks returns an
        // empty list rather than an error arm. This matches the WIT
        // return shape (list<sink-descriptor>) and lets guests probe
        // the substrate without a spurious "not-permitted" surface for
        // a nominally read-only discovery call.
        final CallbackContext ctx = CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkListSinks().execute(new Object[] {});
        final ComponentVal listVal = (ComponentVal) out[0];
        assertThat(listVal.asList()).isEmpty();
    }

    @Test
    public void sinkEmitQuadReturnsNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind();
        // The emit-quad quad arg is unread in the stub — pass an empty
        // list placeholder so the arg-array shape matches the WIT signature.
        final Object[] out = HostCallbacks.sinkEmitQuad().execute(new Object[] {
                ComponentVal.string("my-sink"),
                ComponentVal.list(new ArrayList<>())
        });
        assertNotPermitted(out, "sink-callbacks", "emit-quad");
    }

    @Test
    public void sinkEmitQuadsReturnsNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkEmitQuads().execute(new Object[] {
                ComponentVal.string("my-sink"),
                ComponentVal.list(new ArrayList<>())
        });
        assertNotPermitted(out, "sink-callbacks", "emit-quads");
    }

    @Test
    public void sinkEmitQuadCapabilityDenialShortCircuitsStub() {
        // With capability on and the sink-callbacks interface denied,
        // enforceCapability fires PerCallDenied before the stub returns
        // its own not-permitted arm. Confirms both paths coexist —
        // policy-side denial surfaces as WfCapabilityError; WIT-boundary
        // denial surfaces as the interface's error variant.
        CapabilityEnforcer.install(CapabilityEnforcer.create());
        CapabilityEnforcer.setEnabled(true);
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(grantWithInterfaces(Set.of("graph-callbacks"))); // sink-callbacks not allowed
        final Throwable thrown = catchThrowable(() ->
                HostCallbacks.sinkEmitQuad().execute(new Object[] {
                        ComponentVal.string("my-sink"),
                        ComponentVal.list(new ArrayList<>())
                }));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        assertThat(((WfCapabilityError.PerCallDenied) thrown).interfaceName())
                .isEqualTo("sink-callbacks");
    }

    // ---- sink-query-callbacks ----------------------------------------

    @Test
    public void sinkQueryExecuteSelectReturnsNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkQueryExecuteSelect().execute(new Object[] {
                ComponentVal.string("my-sink"),
                ComponentVal.string("SELECT * WHERE { ?s ?p ?o }")
        });
        assertNotPermitted(out, "sink-query-callbacks", "execute-sink-select");
    }

    @Test
    public void sinkQueryScanQuadsReturnsNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkQueryScanQuads().execute(new Object[] {
                ComponentVal.string("my-sink"),
                ComponentVal.none(),
                ComponentVal.none(),
                ComponentVal.none()
        });
        assertNotPermitted(out, "sink-query-callbacks", "scan-sink-quads");
    }

    // ---- document-sink-callbacks -------------------------------------

    @Test
    public void documentSinkPutDocumentReturnsNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind();
        final Object[] out = HostCallbacks.documentSinkPutDocument().execute(new Object[] {
                ComponentVal.string("my-doc-sink"),
                ComponentVal.string("doc-payload-placeholder")
        });
        assertNotPermitted(out, "document-sink-callbacks", "put-document");
    }

    @Test
    public void documentSinkGetDocumentReturnsNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind();
        final Object[] out = HostCallbacks.documentSinkGetDocument().execute(new Object[] {
                ComponentVal.string("my-doc-sink"),
                ComponentVal.string("doc-key-placeholder")
        });
        assertNotPermitted(out, "document-sink-callbacks", "get-document");
    }

    @Test
    public void documentSinkDeleteDocumentReturnsNotPermitted() {
        final CallbackContext ctx = CallbackContext.bind();
        final Object[] out = HostCallbacks.documentSinkDeleteDocument().execute(new Object[] {
                ComponentVal.string("my-doc-sink"),
                ComponentVal.string("doc-key-placeholder")
        });
        assertNotPermitted(out, "document-sink-callbacks", "delete-document");
    }

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

    /** Build a minimal grant that allows only the named interfaces —
     *  everything else denies at the enforceCapability step. */
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
}
