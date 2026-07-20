package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Unit tests for the {@link SinkRegistry} singleton and its
 * {@link SinkEntry} carrier. Wave A — pure JVM, no Stardog / wasmtime
 * bootstrap needed. Every test resets the singleton so the suite runs
 * in any order.
 */
public class TestSinkRegistry {

    @Before
    public void setUp() {
        SinkRegistry.INSTANCE.reset();
    }

    @After
    public void tearDown() {
        SinkRegistry.INSTANCE.reset();
    }

    @Test
    public void registerThenLookupRoundTrips() {
        SinkRegistry.INSTANCE.register("alpha");
        SinkRegistry.INSTANCE.register("beta");

        assertThat(SinkRegistry.INSTANCE.sinkNames()).containsExactly("alpha", "beta");
        assertThat(SinkRegistry.INSTANCE.sink("alpha")).isPresent();
        assertThat(SinkRegistry.INSTANCE.sink("alpha").get().name()).isEqualTo("alpha");
        assertThat(SinkRegistry.INSTANCE.contains("beta")).isTrue();
    }

    @Test
    public void unknownSinkReturnsEmpty() {
        SinkRegistry.INSTANCE.register("alpha");

        assertThat(SinkRegistry.INSTANCE.sink("gamma")).isEmpty();
        assertThat(SinkRegistry.INSTANCE.contains("gamma")).isFalse();
    }

    @Test
    public void lookupOfNullReturnsEmpty() {
        assertThat(SinkRegistry.INSTANCE.sink(null)).isEmpty();
        assertThat(SinkRegistry.INSTANCE.contains(null)).isFalse();
    }

    @Test
    public void duplicateRegisterThrows() {
        SinkRegistry.INSTANCE.register("alpha");
        final Throwable thrown = catchThrowable(() ->
                SinkRegistry.INSTANCE.register("alpha"));

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alpha")
                .hasMessageContaining("already registered");
    }

    @Test
    public void blankOrNullNameRejected() {
        assertThat(catchThrowable(() -> SinkRegistry.INSTANCE.register(null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> SinkRegistry.INSTANCE.register("")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> SinkRegistry.INSTANCE.register("   ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void resetClearsEverything() {
        SinkRegistry.INSTANCE.register("alpha");
        SinkRegistry.INSTANCE.register("beta");
        SinkRegistry.INSTANCE.sink("alpha").orElseThrow()
                .putDocument("http://example/k", "v");
        SinkRegistry.INSTANCE.reset();

        assertThat(SinkRegistry.INSTANCE.sinkNames()).isEmpty();
        assertThat(SinkRegistry.INSTANCE.sink("alpha")).isEmpty();
    }

    @Test
    public void entryStoresQuadsInOrder() {
        final SinkEntry entry = SinkRegistry.INSTANCE.register("alpha");
        entry.addQuad(quadRecord("s1", "p", "o"));
        entry.addQuad(quadRecord("s2", "p", "o"));

        assertThat(entry.quadCount()).isEqualTo(2);
        final java.util.Iterator<ComponentVal> it = entry.iterateQuads();
        assertThat(it.next().asRecord().get("subject").asVariant().getPayload().get().asString())
                .isEqualTo("s1");
        assertThat(it.next().asRecord().get("subject").asVariant().getPayload().get().asString())
                .isEqualTo("s2");
    }

    @Test
    public void entryDocumentsPutGetDelete() {
        final SinkEntry entry = SinkRegistry.INSTANCE.register("alpha");
        entry.putDocument("http://example/k", "content-1");

        assertThat(entry.getDocument("http://example/k")).isEqualTo("content-1");
        // Overwrite semantics
        entry.putDocument("http://example/k", "content-2");
        assertThat(entry.getDocument("http://example/k")).isEqualTo("content-2");

        // Missing key returns null
        assertThat(entry.getDocument("http://example/missing")).isNull();

        // Delete is idempotent — first returns previous, second returns null
        assertThat(entry.removeDocument("http://example/k")).isEqualTo("content-2");
        assertThat(entry.removeDocument("http://example/k")).isNull();
    }

    @Test
    public void singletonAccessorReturnsSameInstance() {
        assertThat(SinkRegistry.getInstance()).isSameAs(SinkRegistry.INSTANCE);
    }

    /** Build a minimal quad ComponentVal record with named-node terms
     *  in every position. Sufficient for lookup / iteration tests. */
    static ComponentVal quadRecord(final String s, final String p, final String o) {
        final Map<String, ComponentVal> fields = new LinkedHashMap<>();
        fields.put("subject", ComponentVal.variant("named-node", ComponentVal.string(s)));
        fields.put("predicate", ComponentVal.variant("named-node", ComponentVal.string(p)));
        fields.put("object", ComponentVal.variant("named-node", ComponentVal.string(o)));
        fields.put("graph", ComponentVal.none());
        return ComponentVal.record(fields);
    }
}
