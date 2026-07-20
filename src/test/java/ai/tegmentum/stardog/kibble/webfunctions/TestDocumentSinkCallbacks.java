package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentResult;
import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.wasmtime4j.component.ComponentVariant;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave A coverage for {@code document-sink-callbacks} — put/get/delete
 * against an in-memory {@link SinkEntry#documents()} map keyed by the
 * canonical string form of the document's RDF-term key
 * (named-node → raw IRI, blank-node → {@code _:} prefixed id,
 * literal → value only). Mirrors the Oxigraph reference impl's
 * {@code render_term_key}.
 */
public class TestDocumentSinkCallbacks {

    private static ComponentVal namedNode(final String iri) {
        return ComponentVal.variant("named-node", ComponentVal.string(iri));
    }

    private static ComponentVal blankNode(final String id) {
        return ComponentVal.variant("blank-node", ComponentVal.string(id));
    }

    private static ComponentVal simpleLiteral(final String value) {
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("value", ComponentVal.string(value));
        fields.put("datatype", ComponentVal.none());
        fields.put("language", ComponentVal.none());
        return ComponentVal.variant("literal", ComponentVal.record(fields));
    }

    private static ComponentVal document(final ComponentVal key, final String content) {
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("key", key);
        fields.put("content", ComponentVal.string(content));
        return ComponentVal.record(fields);
    }

    @Before
    public void setUp() {
        CapabilityAttributionRing.INSTANCE.clear();
        CapabilityAttributionRing.INSTANCE.setEnabled(false);
        CapabilityEnforcer.install(null);
        CapabilityEnforcer.setEnabled(false);
        ThreadContext.unbindSubject();
        SinkRegistry.INSTANCE.reset();
        SinkRegistry.INSTANCE.register("docs");
    }

    @After
    public void tearDown() {
        CapabilityEnforcer.install(null);
        CapabilityEnforcer.setEnabled(false);
        CapabilityAttributionRing.INSTANCE.clear();
        ThreadContext.unbindSubject();
        SinkRegistry.INSTANCE.reset();
        final CallbackContext ctx = CallbackContext.current();
        if (ctx != null) CallbackContext.unbindIfOutermost(ctx);
    }

    @Test
    public void putThenGetRoundTripsNamedNode() {
        CallbackContext.bind();
        final ComponentVal key = namedNode("http://ex/doc/1");
        final Object[] putOut = HostCallbacks.documentSinkPutDocument().execute(new Object[] {
                ComponentVal.string("docs"), document(key, "{\"hello\": \"world\"}")
        });
        assertThat(((ComponentVal) putOut[0]).asResult().isOk()).isTrue();

        final Object[] getOut = HostCallbacks.documentSinkGetDocument().execute(new Object[] {
                ComponentVal.string("docs"), key
        });
        final ComponentResult result = ((ComponentVal) getOut[0]).asResult();
        assertThat(result.isOk()).isTrue();
        final Map<String, ComponentVal> fields = result.getOk().orElseThrow().asRecord();
        assertThat(fields.get("content").asString()).isEqualTo("{\"hello\": \"world\"}");
        assertThat(fields.get("key").asVariant().getPayload().orElseThrow().asString())
                .isEqualTo("http://ex/doc/1");
    }

    @Test
    public void putReplacesExistingUnderSameKey() {
        CallbackContext.bind();
        final ComponentVal key = namedNode("http://ex/doc/1");
        HostCallbacks.documentSinkPutDocument().execute(new Object[] {
                ComponentVal.string("docs"), document(key, "v1")
        });
        HostCallbacks.documentSinkPutDocument().execute(new Object[] {
                ComponentVal.string("docs"), document(key, "v2")
        });
        final Object[] getOut = HostCallbacks.documentSinkGetDocument().execute(new Object[] {
                ComponentVal.string("docs"), key
        });
        assertThat(((ComponentVal) getOut[0]).asResult().getOk().orElseThrow()
                .asRecord().get("content").asString())
                .isEqualTo("v2");
    }

    @Test
    public void getUnknownDocumentReturnsNoSuchDocument() {
        CallbackContext.bind();
        final Object[] getOut = HostCallbacks.documentSinkGetDocument().execute(new Object[] {
                ComponentVal.string("docs"), namedNode("http://ex/missing")
        });
        final ComponentVariant err = ((ComponentVal) getOut[0]).asResult()
                .getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("no-such-document");
        assertThat(err.getPayload().orElseThrow().asString()).contains("http://ex/missing");
    }

    @Test
    public void deleteRemovesDocument() {
        CallbackContext.bind();
        final ComponentVal key = namedNode("http://ex/doc/gone");
        HostCallbacks.documentSinkPutDocument().execute(new Object[] {
                ComponentVal.string("docs"), document(key, "bye")
        });
        final Object[] delOut = HostCallbacks.documentSinkDeleteDocument().execute(new Object[] {
                ComponentVal.string("docs"), key
        });
        assertThat(((ComponentVal) delOut[0]).asResult().isOk()).isTrue();

        final Object[] getOut = HostCallbacks.documentSinkGetDocument().execute(new Object[] {
                ComponentVal.string("docs"), key
        });
        assertThat(((ComponentVal) getOut[0]).asResult().getErr().orElseThrow()
                .asVariant().getCaseName())
                .isEqualTo("no-such-document");
    }

    @Test
    public void deleteMissingIsIdempotent() {
        CallbackContext.bind();
        final Object[] delOut = HostCallbacks.documentSinkDeleteDocument().execute(new Object[] {
                ComponentVal.string("docs"), namedNode("http://ex/never-put")
        });
        assertThat(((ComponentVal) delOut[0]).asResult().isOk()).isTrue();
    }

    @Test
    public void unknownSinkOnPutGetDeleteReturnsNoSuchSink() {
        CallbackContext.bind();
        final ComponentVal key = namedNode("http://ex/doc/1");

        final Object[] putOut = HostCallbacks.documentSinkPutDocument().execute(new Object[] {
                ComponentVal.string("no-such"), document(key, "x")
        });
        assertThat(((ComponentVal) putOut[0]).asResult().getErr().orElseThrow()
                .asVariant().getCaseName()).isEqualTo("no-such-sink");

        final Object[] getOut = HostCallbacks.documentSinkGetDocument().execute(new Object[] {
                ComponentVal.string("no-such"), key
        });
        assertThat(((ComponentVal) getOut[0]).asResult().getErr().orElseThrow()
                .asVariant().getCaseName()).isEqualTo("no-such-sink");

        final Object[] delOut = HostCallbacks.documentSinkDeleteDocument().execute(new Object[] {
                ComponentVal.string("no-such"), key
        });
        assertThat(((ComponentVal) delOut[0]).asResult().getErr().orElseThrow()
                .asVariant().getCaseName()).isEqualTo("no-such-sink");
    }

    @Test
    public void keysRenderCanonicallyAcrossTermKinds() {
        CallbackContext.bind();
        // named-node → raw IRI; blank-node → "_:" prefix; literal → value only.
        // Distinct key renderings do not collide.
        HostCallbacks.documentSinkPutDocument().execute(new Object[] {
                ComponentVal.string("docs"), document(namedNode("http://ex/a"), "iri-value")
        });
        HostCallbacks.documentSinkPutDocument().execute(new Object[] {
                ComponentVal.string("docs"), document(blankNode("b1"), "bnode-value")
        });
        HostCallbacks.documentSinkPutDocument().execute(new Object[] {
                ComponentVal.string("docs"), document(simpleLiteral("lit-key"), "literal-value")
        });

        assertThat(SinkRegistry.INSTANCE.sink("docs").orElseThrow().documents())
                .containsEntry("http://ex/a", "iri-value")
                .containsEntry("_:b1", "bnode-value")
                .containsEntry("lit-key", "literal-value");
    }
}
