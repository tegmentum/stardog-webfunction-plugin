package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Capability refactor R1 — PolicyTriples carrier record.
 *
 * <p>Locks in the immutable-snapshot contract, the null-rejection safety
 * on the compact constructor, the {@link PolicyTriples#EMPTY} sentinel,
 * and the {@link PolicyTriples#isEmpty()} discriminator the resolver
 * uses to distinguish "unknown extension" from "known but constrained".
 */
public class TestPolicyTriples {

    @Test
    public void happyPathConstructsWithAllFields() {
        final PolicyTriples t = new PolicyTriples(
                Set.of("graph-callbacks", "http-callbacks"),
                Set.of("graph-callbacks/execute-query"),
                Set.of("api.acme.com"));
        assertThat(t.allowedInterfaces()).containsExactlyInAnyOrder("graph-callbacks", "http-callbacks");
        assertThat(t.allowedMethods()).containsExactly("graph-callbacks/execute-query");
        assertThat(t.allowedHosts()).containsExactly("api.acme.com");
        assertThat(t.isEmpty()).isFalse();
    }

    @Test
    public void emptySentinelIsEmpty() {
        assertThat(PolicyTriples.EMPTY.isEmpty()).isTrue();
        assertThat(PolicyTriples.EMPTY.allowedInterfaces()).isEmpty();
        assertThat(PolicyTriples.EMPTY.allowedMethods()).isEmpty();
        assertThat(PolicyTriples.EMPTY.allowedHosts()).isEmpty();
    }

    @Test
    public void isEmptyOnlyWhenAllThreeAxesAreEmpty() {
        assertThat(new PolicyTriples(Set.of("x"), Set.of(), Set.of()).isEmpty()).isFalse();
        assertThat(new PolicyTriples(Set.of(), Set.of("y"), Set.of()).isEmpty()).isFalse();
        assertThat(new PolicyTriples(Set.of(), Set.of(), Set.of("z")).isEmpty()).isFalse();
    }

    @Test
    public void anyNullFieldRejected() {
        assertThat(catchThrowable(() -> new PolicyTriples(null, Set.of(), Set.of())))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new PolicyTriples(Set.of(), null, Set.of())))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new PolicyTriples(Set.of(), Set.of(), null)))
                .isInstanceOf(NullPointerException.class);
        // Phase 5 axes — canonical five-arg ctor.
        assertThat(catchThrowable(() -> new PolicyTriples(
                Set.of(), Set.of(), Set.of(), null, Set.of())))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new PolicyTriples(
                Set.of(), Set.of(), Set.of(), Set.of(), null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void phase5AxesCarriedInCanonicalCtor() {
        final PolicyTriples t = new PolicyTriples(
                Set.of("http-callbacks", "wasm-callbacks"),
                Set.of(),
                Set.of("api.acme.com"),
                Set.of("api.acme.com/public/"),
                Set.of("ipfs://QmCallee", "https://reg.example.org/p.wasm"));
        assertThat(t.allowedHttpPaths()).containsExactly("api.acme.com/public/");
        assertThat(t.allowedWasmCallees()).containsExactlyInAnyOrder(
                "ipfs://QmCallee", "https://reg.example.org/p.wasm");
        assertThat(t.isEmpty()).isFalse();
    }

    @Test
    public void isEmptyIncludesPhase5Axes() {
        // A row with only a wasm-callee (no interface / method / host)
        // is still not the empty snapshot — the resolver treats it as
        // "known extension with a tight scope".
        assertThat(new PolicyTriples(
                Set.of(), Set.of(), Set.of(),
                Set.of("host/path/"), Set.of()).isEmpty()).isFalse();
        assertThat(new PolicyTriples(
                Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of("ipfs://QmX")).isEmpty()).isFalse();
    }

    @Test
    public void backwardCompatCtorDefaultsPhase5AxesEmpty() {
        // Three-arg convenience ctor — Phase 5 axes empty (unrestricted).
        final PolicyTriples t = new PolicyTriples(
                Set.of("http-callbacks"),
                Set.of(),
                Set.of("api.acme.com"));
        assertThat(t.allowedHttpPaths()).isEmpty();
        assertThat(t.allowedWasmCallees()).isEmpty();
    }

    @Test
    public void constructorDefensiveCopyIsolatesFromCaller() {
        final Set<String> mutable = new HashSet<>();
        mutable.add("graph-callbacks");
        final PolicyTriples t = new PolicyTriples(mutable, Set.of(), Set.of());
        // Mutate the caller's set after construction.
        mutable.add("http-callbacks");
        // Snapshot unchanged.
        assertThat(t.allowedInterfaces()).containsExactly("graph-callbacks");
        assertThat(catchThrowable(() -> t.allowedInterfaces().add("evil")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
