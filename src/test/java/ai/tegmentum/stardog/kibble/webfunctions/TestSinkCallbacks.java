package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentResult;
import ai.tegmentum.wasmtime4j.component.ComponentVal;
import ai.tegmentum.wasmtime4j.component.ComponentVariant;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Wave A coverage for {@code sink-callbacks} — the write half of
 * polyglot demotion. Drives the {@link HostCallbacks} lambdas
 * directly against an in-memory {@link SinkRegistry} pre-populated by
 * {@link #setUp()}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code list-sinks} enumerates registered names.</li>
 *   <li>{@code emit-quad} against a registered sink returns
 *       {@code ok} and appends to the entry's deque.</li>
 *   <li>{@code emit-quad} against an unknown name returns
 *       {@code no-such-sink}.</li>
 *   <li>{@code emit-quads} batch counts accepted quads.</li>
 *   <li>Capability denial short-circuits BEFORE the handler dispatches
 *       — a policy that denies the {@code sink-callbacks} interface
 *       raises {@link WfCapabilityError.PerCallDenied} whether the sink
 *       exists or not.</li>
 * </ul>
 */
public class TestSinkCallbacks {

    @Before
    public void setUp() {
        CapabilityAttributionRing.INSTANCE.clear();
        CapabilityAttributionRing.INSTANCE.setEnabled(false);
        CapabilityEnforcer.install(null);
        CapabilityEnforcer.setEnabled(false);
        ThreadContext.unbindSubject();
        SinkRegistry.INSTANCE.reset();
        SinkRegistry.INSTANCE.register("alpha");
        SinkRegistry.INSTANCE.register("beta");
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
    public void listSinksReturnsRegisteredNames() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkListSinks().execute(new Object[] {});
        final ComponentVal listVal = (ComponentVal) out[0];
        final List<ComponentVal> records = listVal.asList();
        assertThat(records).hasSize(2);
        assertThat(records.get(0).asRecord().get("name").asString()).isEqualTo("alpha");
        assertThat(records.get(1).asRecord().get("name").asString()).isEqualTo("beta");
    }

    @Test
    public void listSinksIsEmptyWhenRegistryEmpty() {
        SinkRegistry.INSTANCE.reset();
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkListSinks().execute(new Object[] {});
        assertThat(((ComponentVal) out[0]).asList()).isEmpty();
    }

    @Test
    public void emitQuadAppendsToEntry() {
        CallbackContext.bind();
        final ComponentVal quad = quad("s1", "p", "o");
        final Object[] out = HostCallbacks.sinkEmitQuad().execute(new Object[] {
                ComponentVal.string("alpha"),
                quad
        });
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
        assertThat(SinkRegistry.INSTANCE.sink("alpha").orElseThrow().quadCount())
                .isEqualTo(1);
    }

    @Test
    public void emitQuadUnknownSinkReturnsNoSuchSink() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkEmitQuad().execute(new Object[] {
                ComponentVal.string("gamma"),
                quad("s", "p", "o")
        });
        final ComponentVariant err = ((ComponentVal) out[0]).asResult()
                .getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("no-such-sink");
        assertThat(err.getPayload().orElseThrow().asString()).contains("gamma");
    }

    @Test
    public void emitQuadsAppendsAllAndReturnsCount() {
        CallbackContext.bind();
        final List<ComponentVal> batch = new ArrayList<>();
        batch.add(quad("s1", "p", "o"));
        batch.add(quad("s2", "p", "o"));
        batch.add(quad("s3", "p", "o"));
        final Object[] out = HostCallbacks.sinkEmitQuads().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.list(batch)
        });
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
        // u32 return
        final long count = result.getOk().orElseThrow().asU32();
        assertThat(count).isEqualTo(3L);
        assertThat(SinkRegistry.INSTANCE.sink("alpha").orElseThrow().quadCount())
                .isEqualTo(3);
    }

    @Test
    public void emitQuadsEmptyBatchReturnsZeroCount() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkEmitQuads().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.list(new ArrayList<>())
        });
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
        assertThat(result.getOk().orElseThrow().asU32()).isEqualTo(0L);
    }

    @Test
    public void emitQuadsUnknownSinkReturnsNoSuchSink() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.sinkEmitQuads().execute(new Object[] {
                ComponentVal.string("gamma"),
                ComponentVal.list(new ArrayList<>())
        });
        assertThat(((ComponentVal) out[0]).asResult().getErr().orElseThrow().asVariant().getCaseName())
                .isEqualTo("no-such-sink");
    }

    @Test
    public void capabilityDenialShortCircuitsBeforeDispatch() {
        // With capability on and sink-callbacks denied, enforceCapability
        // throws PerCallDenied BEFORE the handler runs — proves the gate
        // fires ahead of the real impl the same way it did ahead of the
        // former not-permitted stub.
        CapabilityEnforcer.install(CapabilityEnforcer.create());
        CapabilityEnforcer.setEnabled(true);
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(grantWithInterfaces(Set.of("graph-callbacks")));
        final Throwable thrown = catchThrowable(() ->
                HostCallbacks.sinkEmitQuad().execute(new Object[] {
                        ComponentVal.string("alpha"),
                        quad("s", "p", "o")
                }));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        assertThat(((WfCapabilityError.PerCallDenied) thrown).interfaceName())
                .isEqualTo("sink-callbacks");
        // Sanity: handler did NOT run, so no quad accumulated.
        assertThat(SinkRegistry.INSTANCE.sink("alpha").orElseThrow().quadCount())
                .isEqualTo(0);
    }

    static ComponentVal quad(final String s, final String p, final String o) {
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("subject", ComponentVal.variant("named-node", ComponentVal.string(s)));
        fields.put("predicate", ComponentVal.variant("named-node", ComponentVal.string(p)));
        fields.put("object", ComponentVal.variant("named-node", ComponentVal.string(o)));
        fields.put("graph", ComponentVal.none());
        return ComponentVal.record(fields);
    }

    static CapabilityGrant grantWithInterfaces(final Set<String> allowedInterfaces) {
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
