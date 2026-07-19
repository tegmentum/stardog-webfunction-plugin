package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Capability wave Phase 1a — per-interface method policy shape.
 *
 * <p>Covers the allow/deny algebra documented on {@link MethodPolicy}:
 * empty-allow means all; deny-wins-over-allow; whitelist mode; convenience
 * factories; defensive copy of the input Sets.
 */
public class TestMethodPolicy {

    @Test
    public void allowAllPolicyAcceptsAnyMethod() {
        final MethodPolicy p = MethodPolicy.allowAll("graph-callbacks");
        assertThat(p.interfaceName()).isEqualTo("graph-callbacks");
        assertThat(p.allowedMethods()).isEmpty();
        assertThat(p.deniedMethods()).isEmpty();
        assertThat(p.allows("execute-query")).isTrue();
        assertThat(p.allows("execute-update")).isTrue();
        assertThat(p.allows("anything")).isTrue();
    }

    @Test
    public void allowOnlyPolicyRestrictsToNamedMethods() {
        final MethodPolicy p = MethodPolicy.allowOnly("graph-callbacks", Set.of("execute-query"));
        assertThat(p.allows("execute-query")).isTrue();
        assertThat(p.allows("execute-update")).isFalse();
    }

    @Test
    public void denyWinsOverAllow() {
        final MethodPolicy p = new MethodPolicy(
                "graph-callbacks",
                Set.of("execute-query", "execute-update"),
                Set.of("execute-update"));
        assertThat(p.allows("execute-query")).isTrue();
        assertThat(p.allows("execute-update")).isFalse();
    }

    @Test
    public void denyWinsEvenWithEmptyAllow() {
        final MethodPolicy p = new MethodPolicy(
                "graph-callbacks", Set.of(), Set.of("execute-update"));
        assertThat(p.allows("execute-query")).isTrue();
        assertThat(p.allows("execute-update")).isFalse();
    }

    @Test
    public void allowsRejectsNullMethod() {
        final MethodPolicy p = MethodPolicy.allowAll("graph-callbacks");
        assertThat(p.allows(null)).isFalse();
    }

    @Test
    public void nullFieldsRejectedAtConstruction() {
        assertThat(catchThrowable(() -> new MethodPolicy(null, Set.of(), Set.of())))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new MethodPolicy("iface", null, Set.of())))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new MethodPolicy("iface", Set.of(), null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void constructorDefensiveCopyIsolatesFromCaller() {
        final Set<String> mutable = new HashSet<>();
        mutable.add("execute-query");
        final MethodPolicy p = new MethodPolicy("graph-callbacks", mutable, Set.of());
        // Mutating the caller's Set must not affect the policy.
        mutable.add("execute-update");
        assertThat(p.allows("execute-update")).isFalse();
        // And the stored set is unmodifiable.
        assertThat(catchThrowable(() -> p.allowedMethods().add("anything")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void iterationOrderIsPreserved() {
        // Insertion-order preserved via LinkedHashSet copy — useful for
        // audit-row summarization that includes the allowlist.
        final Set<String> ordered = new LinkedHashSet<>();
        ordered.add("alpha");
        ordered.add("beta");
        ordered.add("gamma");
        final MethodPolicy p = new MethodPolicy("iface", ordered, Set.of());
        assertThat(p.allowedMethods()).containsExactly("alpha", "beta", "gamma");
    }
}
