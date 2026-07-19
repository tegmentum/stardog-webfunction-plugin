package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Capability wave Phase 1a — effective grant record and the
 * {@code allowsInterface} / {@code allowsMethod} helpers dispatch consults.
 */
public class TestCapabilityGrant {

    @Test
    public void allowsInterfaceReturnsTrueWhenInGranted() {
        final CapabilityGrant g = new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("graph-callbacks", "http-callbacks"),
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                "alice",
                CapabilityModel.INVOKER_SUBJECT);
        assertThat(g.allowsInterface("graph-callbacks")).isTrue();
        assertThat(g.allowsInterface("http-callbacks")).isTrue();
        assertThat(g.allowsInterface("wasm-callbacks")).isFalse();
        assertThat(g.allowsInterface(null)).isFalse();
    }

    @Test
    public void allowsMethodRequiresInterfaceInGrant() {
        final CapabilityGrant g = new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("graph-callbacks"),
                Map.of("graph-callbacks",
                        MethodPolicy.allowOnly("graph-callbacks", Set.of("execute-query"))),
                HostAllowlist.ALLOW_NONE,
                "alice",
                CapabilityModel.INVOKER_SUBJECT);
        assertThat(g.allowsMethod("graph-callbacks", "execute-query")).isTrue();
        assertThat(g.allowsMethod("graph-callbacks", "execute-update")).isFalse();
        // Interface not granted → any method denied even if the policy would allow.
        assertThat(g.allowsMethod("http-callbacks", "http-get")).isFalse();
    }

    @Test
    public void allowsMethodDefaultsToAllWhenNoMethodPolicy() {
        // Interface granted but no per-method policy — interface-level grant
        // implies method-level allow-all per implementation memo §2 note.
        final CapabilityGrant g = new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("http-callbacks"),
                Map.of(),                   // no method policies at all
                HostAllowlist.ALLOW_NONE,
                "alice",
                CapabilityModel.INVOKER_SUBJECT);
        assertThat(g.allowsMethod("http-callbacks", "http-get")).isTrue();
        assertThat(g.allowsMethod("http-callbacks", "http-post-json")).isTrue();
    }

    @Test
    public void anonymousInvokerPrincipalIsEmpty() {
        final CapabilityGrant g = new CapabilityGrant(
                "file:///ext.wasm", Set.of(), Map.of(), HostAllowlist.ALLOW_NONE,
                "",
                CapabilityModel.AMBIENT);
        assertThat(g.invokerPrincipal()).isEmpty();
    }

    @Test
    public void anyNullFieldRejected() {
        assertThat(catchThrowable(() -> new CapabilityGrant(
                null, Set.of(), Map.of(), HostAllowlist.ALLOW_NONE, "", CapabilityModel.AMBIENT)))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new CapabilityGrant(
                "u", null, Map.of(), HostAllowlist.ALLOW_NONE, "", CapabilityModel.AMBIENT)))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new CapabilityGrant(
                "u", Set.of(), null, HostAllowlist.ALLOW_NONE, "", CapabilityModel.AMBIENT)))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new CapabilityGrant(
                "u", Set.of(), Map.of(), null, "", CapabilityModel.AMBIENT)))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new CapabilityGrant(
                "u", Set.of(), Map.of(), HostAllowlist.ALLOW_NONE, null, CapabilityModel.AMBIENT)))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new CapabilityGrant(
                "u", Set.of(), Map.of(), HostAllowlist.ALLOW_NONE, "", null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void constructorDefensiveCopyOfInterfaceSet() {
        final Map<String, MethodPolicy> mutable = new LinkedHashMap<>();
        mutable.put("graph-callbacks", MethodPolicy.allowAll("graph-callbacks"));
        final CapabilityGrant g = new CapabilityGrant(
                "file:///ext.wasm",
                new java.util.HashSet<>(Set.of("graph-callbacks")),
                mutable,
                HostAllowlist.ALLOW_NONE,
                "alice",
                CapabilityModel.AMBIENT);
        // Mutating original inputs must not leak into the grant.
        mutable.remove("graph-callbacks");
        assertThat(g.methodPolicies()).containsKey("graph-callbacks");
        assertThat(catchThrowable(() -> g.grantedInterfaces().add("http-callbacks")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
