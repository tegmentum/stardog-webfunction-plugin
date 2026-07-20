package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentResult;
import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.wasmtime4j.component.ComponentVariant;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave A coverage for {@code sink-query-callbacks}. The
 * {@code scan-sink-quads} handler lands a real linear-filter impl
 * against the sink's accumulated deque; {@code execute-sink-select}
 * remains deferred (no SPARQL evaluator on the in-memory backend) and
 * returns a targeted {@code backend-error} — deliberately NOT
 * {@code not-permitted}: capability checks pass, the backend just
 * cannot evaluate the query text.
 */
public class TestSinkQueryCallbacks {

    private static ComponentVal quad(final String s, final String p, final String o) {
        return TestSinkCallbacks.quad(s, p, o);
    }

    private static ComponentVal namedNode(final String iri) {
        return ComponentVal.variant("named-node", ComponentVal.string(iri));
    }

    @Before
    public void setUp() {
        CapabilityAttributionRing.INSTANCE.clear();
        CapabilityAttributionRing.INSTANCE.setEnabled(false);
        CapabilityEnforcer.install(null);
        CapabilityEnforcer.setEnabled(false);
        ThreadContext.unbindSubject();
        SinkRegistry.INSTANCE.reset();
        final SinkEntry alpha = SinkRegistry.INSTANCE.register("alpha");
        alpha.addQuad(quad("http://ex/s1", "http://ex/p", "http://ex/o1"));
        alpha.addQuad(quad("http://ex/s2", "http://ex/p", "http://ex/o2"));
        alpha.addQuad(quad("http://ex/s1", "http://ex/q", "http://ex/o3"));
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
    public void scanReturnsAllQuadsWhenAllWildcard() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkQueryScanQuads().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.none(), ComponentVal.none(), ComponentVal.none()
        });
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
        assertThat(result.getOk().orElseThrow().asList()).hasSize(3);
    }

    @Test
    public void scanFiltersBySubject() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkQueryScanQuads().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.some(namedNode("http://ex/s1")),
                ComponentVal.none(),
                ComponentVal.none()
        });
        final List<ComponentVal> quads = ((ComponentVal) out[0]).asResult()
                .getOk().orElseThrow().asList();
        assertThat(quads).hasSize(2);
        for (final ComponentVal q : quads) {
            assertThat(q.asRecord().get("subject").asVariant()
                    .getPayload().orElseThrow().asString())
                    .isEqualTo("http://ex/s1");
        }
    }

    @Test
    public void scanFiltersByPredicate() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkQueryScanQuads().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.none(),
                ComponentVal.some(namedNode("http://ex/q")),
                ComponentVal.none()
        });
        final List<ComponentVal> quads = ((ComponentVal) out[0]).asResult()
                .getOk().orElseThrow().asList();
        assertThat(quads).hasSize(1);
    }

    @Test
    public void scanFiltersByObject() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkQueryScanQuads().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.none(), ComponentVal.none(),
                ComponentVal.some(namedNode("http://ex/o3"))
        });
        final List<ComponentVal> quads = ((ComponentVal) out[0]).asResult()
                .getOk().orElseThrow().asList();
        assertThat(quads).hasSize(1);
    }

    @Test
    public void scanCombinesFiltersConjunctively() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkQueryScanQuads().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.some(namedNode("http://ex/s1")),
                ComponentVal.some(namedNode("http://ex/p")),
                ComponentVal.none()
        });
        final List<ComponentVal> quads = ((ComponentVal) out[0]).asResult()
                .getOk().orElseThrow().asList();
        assertThat(quads).hasSize(1);
        assertThat(quads.get(0).asRecord().get("object").asVariant()
                .getPayload().orElseThrow().asString())
                .isEqualTo("http://ex/o1");
    }

    @Test
    public void scanUnknownSinkReturnsNoSuchSink() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkQueryScanQuads().execute(new Object[] {
                ComponentVal.string("gamma"),
                ComponentVal.none(), ComponentVal.none(), ComponentVal.none()
        });
        final ComponentVariant err = ((ComponentVal) out[0]).asResult()
                .getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("no-such-sink");
    }

    @Test
    public void executeSelectRegisteredSinkReturnsBackendErrorNotNotPermitted() {
        // A registered sink still returns backend-error - the in-memory
        // backend does not embed a SPARQL evaluator. Deliberately NOT
        // not-permitted (capability checks pass; this is a backend-
        // capability gap, not a policy denial).
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkQueryExecuteSelect().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.string("SELECT * WHERE { ?s ?p ?o }")
        });
        final ComponentVariant err = ((ComponentVal) out[0]).asResult()
                .getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("backend-error");
        assertThat(err.getCaseName()).isNotEqualTo("not-permitted");
        assertThat(err.getPayload().orElseThrow().asString())
                .contains("not yet implemented")
                .contains("scan-sink-quads");
    }

    @Test
    public void executeSelectUnknownSinkReturnsNoSuchSink() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkQueryExecuteSelect().execute(new Object[] {
                ComponentVal.string("gamma"),
                ComponentVal.string("SELECT * WHERE { ?s ?p ?o }")
        });
        final ComponentVariant err = ((ComponentVal) out[0]).asResult()
                .getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("no-such-sink");
    }
}
