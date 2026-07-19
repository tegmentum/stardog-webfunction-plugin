package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capability refactor R1 — RDF vocabulary constants.
 *
 * <p>Locks in the {@code cap:} namespace as a fragment sub-vocabulary of
 * the stable {@code wf:} root (per {@code CLAUDE.md}'s identifier-
 * stability rule), the enumerated interface + method IRIs, and the
 * wire-name ↔ IRI bridging helpers admin tooling and the store
 * implementation rely on.
 */
public class TestCapabilityVocabulary {

    @Test
    public void namespaceRootedAtStableWfIri() {
        assertThat(CapabilityVocabulary.NAMESPACE)
                .isEqualTo("http://semantalytics.com/2021/03/ns/stardog/webfunction/capability#");
    }

    @Test
    public void predicateIrisEndWithExpectedLocalNames() {
        assertThat(CapabilityVocabulary.CAP_TRUSTED)
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "trusted");
        assertThat(CapabilityVocabulary.CAP_ALLOW_INTERFACE)
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "allowInterface");
        assertThat(CapabilityVocabulary.CAP_ALLOW_METHOD)
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "allowMethod");
        assertThat(CapabilityVocabulary.CAP_ALLOW_HOST)
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "allowHost");
    }

    @Test
    public void interfaceIriFromWireNameRoundTrips() {
        assertThat(CapabilityVocabulary.interfaceIriFor("graph-callbacks"))
                .isEqualTo(CapabilityVocabulary.IFACE_GRAPH_CALLBACKS);
        assertThat(CapabilityVocabulary.wireNameFor(CapabilityVocabulary.IFACE_GRAPH_CALLBACKS))
                .isEqualTo("graph-callbacks");

        assertThat(CapabilityVocabulary.interfaceIriFor("http-callbacks"))
                .isEqualTo(CapabilityVocabulary.IFACE_HTTP_CALLBACKS);
        assertThat(CapabilityVocabulary.wireNameFor(CapabilityVocabulary.IFACE_HTTP_CALLBACKS))
                .isEqualTo("http-callbacks");
    }

    @Test
    public void unknownWireNameReturnsNull() {
        assertThat(CapabilityVocabulary.interfaceIriFor("bogus-callbacks")).isNull();
        assertThat(CapabilityVocabulary.interfaceIriFor(null)).isNull();
        assertThat(CapabilityVocabulary.wireNameFor("http://example/Bogus")).isNull();
        assertThat(CapabilityVocabulary.wireNameFor(null)).isNull();
    }

    @Test
    public void knownInterfacesEnumeratesAllElevenSurfaces() {
        // Locks in that the vocabulary carries the 11 host-callback
        // surfaces the brief calls out.
        final Map<String, String> known = CapabilityVocabulary.knownInterfaces();
        assertThat(known)
                .hasSize(11)
                .containsKeys(
                        "graph-callbacks",
                        "http-callbacks",
                        "wasm-callbacks",
                        "fulltext-callbacks",
                        "sink-callbacks",
                        "tracker-sink-callbacks",
                        "prepared-query-callbacks",
                        "observability-callbacks",
                        "document-sink-callbacks",
                        "sink-query-callbacks",
                        "fast-path-callbacks");
    }

    @Test
    public void syntheticInterfaceIriKebabToPascal() {
        assertThat(CapabilityVocabulary.syntheticInterfaceIri("new-callbacks"))
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "NewCallbacks");
        assertThat(CapabilityVocabulary.syntheticInterfaceIri("some-long-name"))
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "SomeLongName");
        assertThat(CapabilityVocabulary.syntheticInterfaceIri(null)).isNull();
        assertThat(CapabilityVocabulary.syntheticInterfaceIri("")).isNull();
    }

    @Test
    public void methodIrisFollowInterfaceUnderscoreMethodConvention() {
        assertThat(CapabilityVocabulary.METHOD_GRAPH_CALLBACKS_EXECUTE_QUERY)
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "GraphCallbacks_ExecuteQuery");
        assertThat(CapabilityVocabulary.METHOD_HTTP_CALLBACKS_GET)
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "HttpCallbacks_Get");
        assertThat(CapabilityVocabulary.METHOD_WASM_CALLBACKS_INVOKE)
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "WasmCallbacks_Invoke");
    }

    @Test
    public void knownInterfacesReturnsUnmodifiableSnapshot() {
        final Map<String, String> known = CapabilityVocabulary.knownInterfaces();
        assertThatThrows(() -> known.put("evil", "evil"));
    }

    private static void assertThatThrows(Runnable r) {
        try {
            r.run();
            org.junit.Assert.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
