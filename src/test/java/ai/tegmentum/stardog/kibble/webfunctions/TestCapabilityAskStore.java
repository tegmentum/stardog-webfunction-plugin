package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.Test;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capability-ask CA3 — vocabulary constants + store surface for the
 * ask path ({@link CapabilityPolicyStore#recordAsk},
 * {@link CapabilityPolicyStore#loadAskFor}).
 *
 * <p>Kernel-backed round-trip is covered by {@link WasmTestSuiteIT}
 * (mvn verify with STARDOG_LICENSE_PATH); this unit test covers:
 * <ul>
 *   <li>New vocabulary constants and named-graph IRI match the memo.</li>
 *   <li>{@link CapabilityPolicyStore}'s default methods are no-ops so a
 *       hand-wired test double works without implementing ask.</li>
 *   <li>An in-memory fake honors the overwrite-on-reload contract and
 *       returns empty for unknown extensions.</li>
 * </ul>
 */
public class TestCapabilityAskStore {

    @Test
    public void askPredicateIrisMatchMemoVocabulary() {
        assertThat(CapabilityVocabulary.CAP_ASKS_INTERFACE)
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "asksInterface");
        assertThat(CapabilityVocabulary.CAP_ASKS_METHOD)
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "asksMethod");
        assertThat(CapabilityVocabulary.CAP_ASKS_HOST)
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "asksHost");
        assertThat(CapabilityVocabulary.CAP_ASKS_HTTP_PATH)
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "asksHttpPath");
        assertThat(CapabilityVocabulary.CAP_ASKS_WASM_CALLEE)
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "asksWasmCallee");
        assertThat(CapabilityVocabulary.CAP_ASKS_RATIONALE)
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "asksRationale");
        assertThat(CapabilityVocabulary.CAP_HAS_ASK)
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "hasAsk");
        assertThat(CapabilityVocabulary.CAP_CAPABILITY_ASK)
                .isEqualTo(CapabilityVocabulary.NAMESPACE + "CapabilityAsk");
    }

    @Test
    public void askNamedGraphMatchesLockedInIri() {
        // Locked in per capability-ask.md §6 + §13 recommendation (a).
        // Any change here breaks admin CONSTRUCT queries built against
        // this fixed IRI, so this test guards against churn.
        assertThat(CapabilityVocabulary.CAP_ASKS_NAMED_GRAPH)
                .isEqualTo("urn:stardog:webfunction:capability:asks");
    }

    @Test
    public void defaultStoreMethodsAreNoOps() throws Exception {
        // A store impl that doesn't override the default methods should
        // still satisfy the interface — recordAsk no-ops, loadAskFor
        // returns empty. Proves the interface is safe to add to an
        // in-memory test double without an ask implementation.
        final CapabilityPolicyStore bareStore = new CapabilityPolicyStore() {
            @Override public Optional<PolicyTriples> resolveFor(final URL u) {
                return Optional.of(PolicyTriples.EMPTY);
            }
            @Override public boolean isReady() { return true; }
        };
        final URL url = new URL("file:///ext.wasm");
        // Should NOT throw.
        bareStore.recordAsk(url, CapabilityAsk.EMPTY);
        assertThat(bareStore.loadAskFor(url)).isEmpty();
    }

    @Test
    public void inMemoryAskStoreRoundTripsAsk() throws Exception {
        // In-memory implementation covers the overwrite-on-reload
        // semantics without needing a Stardog kernel.
        final Map<String, CapabilityAsk> memory = new HashMap<>();
        final CapabilityPolicyStore store = new CapabilityPolicyStore() {
            @Override public Optional<PolicyTriples> resolveFor(final URL u) {
                return Optional.of(PolicyTriples.EMPTY);
            }
            @Override public boolean isReady() { return true; }
            @Override public void recordAsk(final URL u, final CapabilityAsk a) {
                memory.put(u.toString(), a);
            }
            @Override public Optional<CapabilityAsk> loadAskFor(final URL u) {
                return Optional.ofNullable(memory.get(u.toString()));
            }
        };

        final URL url = new URL("file:///ext.wasm");
        final CapabilityAsk first = new CapabilityAsk(
                Set.of("http-callbacks"),
                Set.of("http-callbacks/get"),
                Set.of("api.acme.com"),
                Set.of(), Set.of(),
                Optional.of("v1 rationale"));
        store.recordAsk(url, first);
        assertThat(store.loadAskFor(url)).contains(first);

        // Overwrite semantics: recording a new ask replaces the first.
        final CapabilityAsk second = new CapabilityAsk(
                Set.of("graph-callbacks"),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Optional.of("v2 rationale"));
        store.recordAsk(url, second);
        final Optional<CapabilityAsk> after = store.loadAskFor(url);
        assertThat(after).isPresent();
        assertThat(after.get().asksInterfaces()).containsExactly("graph-callbacks");
        assertThat(after.get().rationale()).contains("v2 rationale");
    }

    @Test
    public void loadAskForUnknownExtensionReturnsEmpty() throws Exception {
        final CapabilityPolicyStore store = new CapabilityPolicyStore() {
            @Override public Optional<PolicyTriples> resolveFor(final URL u) {
                return Optional.of(PolicyTriples.EMPTY);
            }
            @Override public boolean isReady() { return true; }
        };
        assertThat(store.loadAskFor(new URL("file:///never-loaded.wasm"))).isEmpty();
    }

    @Test
    public void kernelStoreEscapeLiteralPreservesAskRationale() {
        // The rationale string is inline-embedded in the INSERT DATA
        // payload; escapeLiteral must round-trip common problem chars
        // (backslash, quote, newline) without breaking the SPARQL parse.
        assertThat(KernelBackedCapabilityPolicyStore.escapeLiteral("hello"))
                .isEqualTo("hello");
        assertThat(KernelBackedCapabilityPolicyStore.escapeLiteral("a \"quoted\" b"))
                .isEqualTo("a \\\"quoted\\\" b");
        assertThat(KernelBackedCapabilityPolicyStore.escapeLiteral("line\nbreak"))
                .isEqualTo("line\\nbreak");
        assertThat(KernelBackedCapabilityPolicyStore.escapeLiteral("back\\slash"))
                .isEqualTo("back\\\\slash");
    }
}
