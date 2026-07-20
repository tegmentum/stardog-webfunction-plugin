package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentResult;
import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.wasmtime4j.component.ComponentVariant;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Coverage for {@code sink-query-callbacks}. {@code scan-sink-quads}
 * lands a real linear-filter impl against the sink's accumulated
 * deque. {@code execute-sink-select} — Wave A stubbed with a targeted
 * {@code backend-error} — is now a real SPARQL SELECT evaluator over
 * the sink's quads via
 * {@link SinkSparqlEngine#evaluate(SinkEntry, String)}. Tests migrated
 * accordingly: the former "returns backend-error" negative assertion
 * is replaced by happy-path / parse-error / capability-denial /
 * no-such-sink positive coverage.
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
    public void executeSelectHappyPathReturnsFlatBindingList() {
        // Setup: alpha holds three quads across two subjects. SELECT
        // ?s ?p ?o -> 3 solutions x 3 bound vars = 9 flat binding
        // records per WIT contract's split-on-repeated-variable-identity.
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkQueryExecuteSelect().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.string("SELECT ?s ?p ?o WHERE { ?s ?p ?o }")
        });
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
        final List<ComponentVal> bindings = result.getOk().orElseThrow().asList();
        assertThat(bindings).hasSize(9);
        // First entry is a {variable, value} binding record.
        assertThat(bindings.get(0).asRecord().get("variable").asString()).isEqualTo("s");
    }

    @Test
    public void executeSelectBgpFilterReducesToMatchingSolution() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkQueryExecuteSelect().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.string(
                        "SELECT ?o WHERE { <http://ex/s1> <http://ex/p> ?o }")
        });
        final List<ComponentVal> bindings = ((ComponentVal) out[0]).asResult()
                .getOk().orElseThrow().asList();
        assertThat(bindings).hasSize(1);
        assertThat(bindings.get(0).asRecord().get("value").asVariant()
                .getPayload().orElseThrow().asString())
                .isEqualTo("http://ex/o1");
    }

    @Test
    public void executeSelectMalformedSparqlReturnsSyntaxError() {
        // SPARQLParser rejects the text -> SinkSparqlEngine.SyntaxError
        // -> WIT sink-query-error::syntax-error. Deliberately NOT
        // backend-error: syntax mistakes are guest-side bugs the
        // extension reacts to at boot; backend outages are per-call.
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkQueryExecuteSelect().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.string("this is not sparql")
        });
        final ComponentVariant err = ((ComponentVal) out[0]).asResult()
                .getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("syntax-error");
    }

    @Test
    public void executeSelectAskShapeQueryReturnsSyntaxError() {
        // WIT contract: execute-sink-select is SELECT-only. CONSTRUCT
        // parses but does not satisfy the ParsedTupleQuery gate.
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkQueryExecuteSelect().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.string("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")
        });
        final ComponentVariant err = ((ComponentVal) out[0]).asResult()
                .getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("syntax-error");
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

    @Test
    public void executeSelectCapabilityDenialShortCircuitsBeforeEval() {
        // With capability on and sink-query-callbacks denied,
        // enforceCapability throws PerCallDenied BEFORE the engine
        // parses the SPARQL or spins up a MemoryStore. Proves the gate
        // fires ahead of the real evaluator the same way it did ahead
        // of the Wave A stub.
        CapabilityEnforcer.install(CapabilityEnforcer.create());
        CapabilityEnforcer.setEnabled(true);
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(TestSinkCallbacks.grantWithInterfaces(
                Set.of("graph-callbacks")));
        final Throwable thrown = catchThrowable(() ->
                HostCallbacks.sinkQueryExecuteSelect().execute(new Object[] {
                        ComponentVal.string("alpha"),
                        ComponentVal.string("SELECT * WHERE { ?s ?p ?o }")
                }));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        assertThat(((WfCapabilityError.PerCallDenied) thrown).interfaceName())
                .isEqualTo("sink-query-callbacks");
    }
}
