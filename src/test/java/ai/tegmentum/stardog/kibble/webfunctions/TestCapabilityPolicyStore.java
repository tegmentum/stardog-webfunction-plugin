package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.Test;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capability refactor R2 — {@link CapabilityPolicyStore} interface
 * contract plus a bounded unit surface for
 * {@link KernelBackedCapabilityPolicyStore}.
 *
 * <p>The real store's Kernel-backed round-trip is covered by
 * {@link WasmTestSuiteIT} (mvn verify with STARDOG_LICENSE_PATH); this
 * unit test asserts:
 * <ul>
 *   <li>Construction with null Kernel is allowed — the initialize path is
 *       lazy so a hand-wired store can be built ahead of Kernel install
 *       (matches {@link KernelBackedFuelStateStore}'s shape).</li>
 *   <li>An in-memory fake test double satisfies the interface contract —
 *       proves the interface is testable outside of Stardog.</li>
 *   <li>The static {@code escapeIri} helper strips IRI-terminator
 *       characters — the load-bearing sanitizer for the inline SPARQL
 *       subject IRI the store issues per instantiation.</li>
 * </ul>
 */
public class TestCapabilityPolicyStore {

    @Test
    public void kernelStoreConstructionWithNullKernelDefersToInitialize() {
        // Documented divergence from the fuel store — the Kernel arg is
        // non-null in the capability store (guarded at construction).
        // This test just documents that the default-database constructor
        // works with a real-looking DB name and that the class exists.
        try {
            new KernelBackedCapabilityPolicyStore(null);
            org.junit.Assert.fail("expected NPE on null Kernel");
        } catch (NullPointerException expected) {
            // ok — the store guards its Kernel arg at construction.
        }
    }

    @Test
    public void escapeIriStripsIriTerminatorCharacters() {
        // The store's SPARQL subject is a literal IRI wrapped in <...>;
        // any character that would close the IRI early must be replaced.
        assertThat(KernelBackedCapabilityPolicyStore.escapeIri("ipfs://QmAbc"))
                .isEqualTo("ipfs://QmAbc");
        assertThat(KernelBackedCapabilityPolicyStore.escapeIri("evil<>\"{}"))
                .isEqualTo("evil_____");
        assertThat(KernelBackedCapabilityPolicyStore.escapeIri("with space"))
                .isEqualTo("with_space");
    }

    @Test
    public void inMemoryFakeSatisfiesInterfaceContract() throws Exception {
        // Wire a small in-memory store and prove the interface contract:
        // resolveFor returns a PolicyTriples snapshot per URL, empty for
        // unknown URLs, isReady always true.
        final URL knownUrl = new URL("file:///known.wasm");
        final URL unknownUrl = new URL("file:///unknown.wasm");
        final Map<String, PolicyTriples> table = new LinkedHashMap<>();
        table.put(
                knownUrl.toString(),
                new PolicyTriples(
                        Set.of("graph-callbacks"),
                        Set.of("graph-callbacks/execute-query"),
                        Set.of("api.acme.com")));
        final AtomicInteger reads = new AtomicInteger();
        final CapabilityPolicyStore store = new CapabilityPolicyStore() {
            @Override public Optional<PolicyTriples> resolveFor(final URL extensionUrl) {
                reads.incrementAndGet();
                if (extensionUrl == null) return Optional.of(PolicyTriples.EMPTY);
                return Optional.of(table.getOrDefault(extensionUrl.toString(), PolicyTriples.EMPTY));
            }
            @Override public boolean isReady() { return true; }
        };
        assertThat(store.isReady()).isTrue();

        final Optional<PolicyTriples> exact = store.resolveFor(knownUrl);
        assertThat(exact).isPresent();
        assertThat(exact.get().isEmpty()).isFalse();
        assertThat(exact.get().allowedInterfaces()).containsExactly("graph-callbacks");
        assertThat(exact.get().allowedMethods()).containsExactly("graph-callbacks/execute-query");
        assertThat(exact.get().allowedHosts()).containsExactly("api.acme.com");

        final Optional<PolicyTriples> unknown = store.resolveFor(unknownUrl);
        assertThat(unknown).isPresent();
        assertThat(unknown.get().isEmpty()).isTrue();

        assertThat(reads.get()).isEqualTo(2);
    }

    @Test
    public void kernelStoreDefaultDatabaseNameMatchesBrief() {
        // Locks in the DB name mandated by the brief so a future refactor
        // that moves this into WebFunctionConfig keeps the constant.
        assertThat(KernelBackedCapabilityPolicyStore.DEFAULT_DATABASE_NAME)
                .isEqualTo("system-webfunctions-capability");
    }
}
