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
