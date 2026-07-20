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
 * Wave C coverage for {@code fulltext-callbacks}. Drives the
 * {@link HostCallbacks} lambdas directly against the in-memory
 * {@link InMemoryFulltextRegistry} pre-populated by {@link #setUp()}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code insert-documents} against a registered index accepts the
 *       batch and returns the accepted count.</li>
 *   <li>{@code insert-documents} against an unknown name returns
 *       {@code no-such-index}.</li>
 *   <li>{@code delete-documents} returns the count actually removed
 *       (missing ids do not contribute).</li>
 *   <li>{@code search-index} does case-insensitive substring match and
 *       returns hits with subject / score / snippet=none.</li>
 *   <li>{@code search-index} with {@code option::some(limit)}
 *       truncates.</li>
 *   <li>Capability denial short-circuits BEFORE the handler dispatches
 *       — a policy that denies the {@code fulltext-callbacks} interface
 *       raises {@link WfCapabilityError.PerCallDenied} whether the
 *       index exists or not.</li>
 * </ul>
 *
 * <p>Mirrors {@link TestSinkCallbacks}'s shape. Pure JVM, no wasmtime
 * engine dependency — the handler lambdas are exercised through
 * {@code WitHostFunction#execute}.
 */
public class TestFulltextCallbacks {

    @Before
    public void setUp() {
        CapabilityAttributionRing.INSTANCE.clear();
        CapabilityAttributionRing.INSTANCE.setEnabled(false);
        CapabilityEnforcer.install(null);
        CapabilityEnforcer.setEnabled(false);
        ThreadContext.unbindSubject();
        InMemoryFulltextRegistry.INSTANCE.reset();
        InMemoryFulltextRegistry.INSTANCE.register("alpha");
        InMemoryFulltextRegistry.INSTANCE.register("beta");
    }

    @After
    public void tearDown() {
        CapabilityEnforcer.install(null);
        CapabilityEnforcer.setEnabled(false);
        CapabilityAttributionRing.INSTANCE.clear();
        ThreadContext.unbindSubject();
        InMemoryFulltextRegistry.INSTANCE.reset();
        final CallbackContext ctx = CallbackContext.current();
        if (ctx != null) CallbackContext.unbindIfOutermost(ctx);
    }

    // ---- insert-documents --------------------------------------------

    @Test
    public void insertDocumentsAcceptsBatchAndReturnsCount() {
        CallbackContext.bind();
        final List<ComponentVal> batch = new ArrayList<>();
        batch.add(doc("doc1", List.of(pair("p", "hello world")), null));
        batch.add(doc("doc2", List.of(pair("p", "goodbye world")), "en"));

        final Object[] out = HostCallbacks.fulltextInsertDocuments().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.list(batch)
        });

        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
        assertThat(result.getOk().orElseThrow().asU32()).isEqualTo(2L);
        assertThat(InMemoryFulltextRegistry.INSTANCE.index("alpha").orElseThrow()
                .documentCount()).isEqualTo(2);
    }

    @Test
    public void insertDocumentsUnknownIndexReturnsNoSuchIndex() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.fulltextInsertDocuments().execute(new Object[] {
                ComponentVal.string("gamma"),
                ComponentVal.list(new ArrayList<>())
        });
        final ComponentVariant err = ((ComponentVal) out[0]).asResult()
                .getErr().orElseThrow().asVariant();
        assertThat(err.getCaseName()).isEqualTo("no-such-index");
        assertThat(err.getPayload().orElseThrow().asString()).contains("gamma");
    }

    @Test
    public void insertDocumentsEmptyBatchReturnsZeroCount() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.fulltextInsertDocuments().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.list(new ArrayList<>())
        });
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
        assertThat(result.getOk().orElseThrow().asU32()).isEqualTo(0L);
    }

    // ---- delete-documents --------------------------------------------

    @Test
    public void deleteDocumentsReturnsRemovedCount() {
        CallbackContext.bind();
        final FulltextIndex idx = InMemoryFulltextRegistry.INSTANCE.index("alpha").orElseThrow();
        idx.insertDocument("doc1", List.of(new FulltextIndex.FieldPair("p", "x")), null);
        idx.insertDocument("doc2", List.of(new FulltextIndex.FieldPair("p", "x")), null);

        final List<ComponentVal> ids = new ArrayList<>();
        ids.add(ComponentVal.string("doc1"));
        ids.add(ComponentVal.string("doc2"));
        ids.add(ComponentVal.string("never-existed"));

        final Object[] out = HostCallbacks.fulltextDeleteDocuments().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.list(ids)
        });
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
        // Only doc1 and doc2 actually existed — the missing id does
        // not contribute to the count.
        assertThat(result.getOk().orElseThrow().asU32()).isEqualTo(2L);
        assertThat(idx.documentCount()).isEqualTo(0);
    }

    @Test
    public void deleteDocumentsUnknownIndexReturnsNoSuchIndex() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.fulltextDeleteDocuments().execute(new Object[] {
                ComponentVal.string("gamma"),
                ComponentVal.list(new ArrayList<>())
        });
        assertThat(((ComponentVal) out[0]).asResult()
                .getErr().orElseThrow().asVariant().getCaseName())
                .isEqualTo("no-such-index");
    }

    // ---- search-index -------------------------------------------------

    @Test
    public void searchIndexReturnsHitsWithSubjectAndScore() {
        CallbackContext.bind();
        final FulltextIndex idx = InMemoryFulltextRegistry.INSTANCE.index("alpha").orElseThrow();
        idx.insertDocument("doc1",
                List.of(new FulltextIndex.FieldPair("p", "Hello World")), null);
        idx.insertDocument("doc2",
                List.of(new FulltextIndex.FieldPair("p", "unrelated")), null);

        final Object[] out = HostCallbacks.fulltextSearchIndex().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.string("hello"),
                ComponentVal.none()
        });
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
        final List<ComponentVal> hits = result.getOk().orElseThrow().asList();
        assertThat(hits).hasSize(1);
        final Map<String, ComponentVal> hit = hits.get(0).asRecord();
        // subject encoded as named-node variant carrying the doc id.
        final ComponentVariant subj = hit.get("subject").asVariant();
        assertThat(subj.getCaseName()).isEqualTo("named-node");
        assertThat(subj.getPayload().orElseThrow().asString()).isEqualTo("doc1");
        assertThat(hit.get("score").asF64()).isEqualTo(1.0);
        assertThat(hit.get("snippet").asSome()).isEmpty();
    }

    @Test
    public void searchIndexRespectsLimit() {
        CallbackContext.bind();
        final FulltextIndex idx = InMemoryFulltextRegistry.INSTANCE.index("alpha").orElseThrow();
        for (int i = 0; i < 5; i++) {
            idx.insertDocument("d" + i,
                    List.of(new FulltextIndex.FieldPair("p", "match")), null);
        }
        final Object[] out = HostCallbacks.fulltextSearchIndex().execute(new Object[] {
                ComponentVal.string("alpha"),
                ComponentVal.string("match"),
                ComponentVal.some(ComponentVal.u32(2L))
        });
        final ComponentResult result = ((ComponentVal) out[0]).asResult();
        assertThat(result.isOk()).isTrue();
        assertThat(result.getOk().orElseThrow().asList()).hasSize(2);
    }

    @Test
    public void searchIndexUnknownIndexReturnsNoSuchIndex() {
        CallbackContext.bind();
        final Object[] out = HostCallbacks.fulltextSearchIndex().execute(new Object[] {
                ComponentVal.string("gamma"),
                ComponentVal.string("q"),
                ComponentVal.none()
        });
        assertThat(((ComponentVal) out[0]).asResult()
                .getErr().orElseThrow().asVariant().getCaseName())
                .isEqualTo("no-such-index");
    }

    // ---- capability enforcement --------------------------------------

    @Test
    public void insertCapabilityDenialShortCircuitsBeforeDispatch() {
        // With capability on and fulltext-callbacks denied,
        // enforceCapability throws PerCallDenied BEFORE the handler
        // runs — proves the gate fires ahead of the real impl the same
        // way it did ahead of the former not-permitted stub.
        CapabilityEnforcer.install(CapabilityEnforcer.create());
        CapabilityEnforcer.setEnabled(true);
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(grantWithInterfaces(Set.of("graph-callbacks")));

        final Throwable thrown = catchThrowable(() ->
                HostCallbacks.fulltextInsertDocuments().execute(new Object[] {
                        ComponentVal.string("alpha"),
                        ComponentVal.list(new ArrayList<>())
                }));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        assertThat(((WfCapabilityError.PerCallDenied) thrown).interfaceName())
                .isEqualTo("fulltext-callbacks");
        // Sanity: handler did NOT run, index has no docs.
        assertThat(InMemoryFulltextRegistry.INSTANCE.index("alpha").orElseThrow()
                .documentCount()).isEqualTo(0);
    }

    @Test
    public void deleteCapabilityDenialShortCircuitsBeforeDispatch() {
        CapabilityEnforcer.install(CapabilityEnforcer.create());
        CapabilityEnforcer.setEnabled(true);
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(grantWithInterfaces(Set.of("graph-callbacks")));

        final Throwable thrown = catchThrowable(() ->
                HostCallbacks.fulltextDeleteDocuments().execute(new Object[] {
                        ComponentVal.string("alpha"),
                        ComponentVal.list(new ArrayList<>())
                }));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        assertThat(((WfCapabilityError.PerCallDenied) thrown).interfaceName())
                .isEqualTo("fulltext-callbacks");
    }

    @Test
    public void searchCapabilityDenialShortCircuitsBeforeDispatch() {
        CapabilityEnforcer.install(CapabilityEnforcer.create());
        CapabilityEnforcer.setEnabled(true);
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setCapabilityGrant(grantWithInterfaces(Set.of("graph-callbacks")));

        final Throwable thrown = catchThrowable(() ->
                HostCallbacks.fulltextSearchIndex().execute(new Object[] {
                        ComponentVal.string("alpha"),
                        ComponentVal.string("q"),
                        ComponentVal.none()
                }));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        assertThat(((WfCapabilityError.PerCallDenied) thrown).interfaceName())
                .isEqualTo("fulltext-callbacks");
    }

    // ---- helpers ------------------------------------------------------

    /** Build a WIT {@code fulltext-document} record. */
    static ComponentVal doc(final String id,
                            final List<ComponentVal> fields,
                            final String lang) {
        final Map<String, ComponentVal> record = new LinkedHashMap<>();
        record.put("id", ComponentVal.string(id));
        record.put("fields", ComponentVal.list(fields));
        record.put("lang", lang == null ? ComponentVal.none()
                : ComponentVal.some(ComponentVal.string(lang)));
        return ComponentVal.record(record);
    }

    /** Build a WIT {@code tuple<string, string>} field pair. */
    static ComponentVal pair(final String predicate, final String value) {
        return ComponentVal.tuple(ComponentVal.string(predicate), ComponentVal.string(value));
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
