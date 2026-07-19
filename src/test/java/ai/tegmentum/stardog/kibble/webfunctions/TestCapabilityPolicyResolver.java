package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.webassembly4j.api.Component;
import ai.tegmentum.webassembly4j.api.ComponentInstance;
import ai.tegmentum.webassembly4j.api.LinkingContext;
import org.apache.shiro.subject.Subject;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Capability wave Phase 1d — grant resolver.
 *
 * <p>Covers the algorithm on {@link CapabilityPolicyResolver}: happy
 * path, required-denied path, all three anonymous policies, and the
 * fully-qualified WIT path stripping helper.
 *
 * <p>Uses {@link Proxy} to fake the Shiro {@link Subject} interface —
 * we only need {@code getPrincipal()} out of Subject's 30-plus methods,
 * and no Mockito dep sits in this repo's test scope.
 */
public class TestCapabilityPolicyResolver {

    private static final String EXT_URI = "file:///ext.wasm";

    @After
    public void resetAnonymousPolicy() {
        CapabilityPolicyResolver.setAnonymousPolicy(
                CapabilityPolicyResolver.DEFAULT_ANONYMOUS_POLICY);
    }

    @Test
    public void happyPathGrantEqualsManifestRequestWhenComponentDeclares() {
        final ExtensionManifest manifest = new ExtensionManifest(
                "ext", "1.0.0", "",
                Set.of("graph-callbacks"),
                Set.of("http-callbacks"),
                Map.of("graph-callbacks",
                        MethodPolicy.allowOnly("graph-callbacks", Set.of("execute-query"))),
                new HostAllowlist(List.of("api.acme.com")),
                CapabilityModel.INVOKER_SUBJECT);
        final Component component = fakeComponent(List.of(
                "tegmentum:webfunction/graph-callbacks@0.1.0",
                "tegmentum:webfunction/http-callbacks@0.1.0",
                // Extra declared import the manifest doesn't request — it
                // must not leak into the grant.
                "tegmentum:webfunction/fulltext-callbacks@0.1.0"));
        final Subject subject = fakeSubject("alice");

        final CapabilityGrant g = CapabilityPolicyResolver.resolve(
                EXT_URI, component, manifest, subject);

        assertThat(g.extensionUri()).isEqualTo(EXT_URI);
        assertThat(g.grantedInterfaces())
                .containsExactly("graph-callbacks", "http-callbacks");
        // Extra fulltext-callbacks import stays out — not requested.
        assertThat(g.allowsInterface("fulltext-callbacks")).isFalse();
        assertThat(g.methodPolicies()).containsKey("graph-callbacks");
        assertThat(g.invokerPrincipal()).isEqualTo("alice");
        assertThat(g.model()).isEqualTo(CapabilityModel.INVOKER_SUBJECT);
    }

    @Test
    public void requiredInterfaceMissingFromComponentThrowsLoadTimeDenied() {
        final ExtensionManifest manifest = new ExtensionManifest(
                "ext", "1.0.0", "",
                Set.of("graph-callbacks"),   // required
                Set.of(),
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                CapabilityModel.AMBIENT);
        // Component doesn't declare graph-callbacks.
        final Component component = fakeComponent(List.of(
                "tegmentum:webfunction/http-callbacks@0.1.0"));

        final Throwable thrown = catchThrowable(() -> CapabilityPolicyResolver.resolve(
                EXT_URI, component, manifest, fakeSubject("alice")));
        assertThat(thrown).isInstanceOf(WfCapabilityError.LoadTimeDenied.class);
        final WfCapabilityError.LoadTimeDenied err = (WfCapabilityError.LoadTimeDenied) thrown;
        assertThat(err.missingInterface()).isEqualTo("graph-callbacks");
        assertThat(err.invoker()).isEqualTo("alice");
        assertThat(err.policySource()).contains("component");
    }

    @Test
    public void optionalInterfaceMissingDoesNotFail() {
        // Required present but optional absent — must not throw.
        final ExtensionManifest manifest = new ExtensionManifest(
                "ext", "1.0.0", "",
                Set.of("graph-callbacks"),
                Set.of("http-callbacks"),   // optional, not declared
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                CapabilityModel.AMBIENT);
        final Component component = fakeComponent(List.of(
                "tegmentum:webfunction/graph-callbacks@0.1.0"));

        final CapabilityGrant g = CapabilityPolicyResolver.resolve(
                EXT_URI, component, manifest, fakeSubject("alice"));
        assertThat(g.grantedInterfaces()).containsExactly("graph-callbacks");
        assertThat(g.allowsInterface("http-callbacks")).isFalse();
    }

    @Test
    public void anonymousDenyPolicyThrowsWhenSubjectAbsent() {
        CapabilityPolicyResolver.setAnonymousPolicy(
                CapabilityPolicyResolver.AnonymousPolicy.DENY);
        final ExtensionManifest manifest = new ExtensionManifest(
                "ext", "1.0.0", "",
                Set.of("graph-callbacks"),
                Set.of(),
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                CapabilityModel.AMBIENT);

        final Throwable thrown = catchThrowable(() -> CapabilityPolicyResolver.resolve(
                EXT_URI, null, manifest, null));
        assertThat(thrown).isInstanceOf(WfCapabilityError.LoadTimeDenied.class);
        final WfCapabilityError.LoadTimeDenied err = (WfCapabilityError.LoadTimeDenied) thrown;
        assertThat(err.resolutionStage()).isEqualTo("shiro");
        assertThat(err.policySource()).contains("anonymous-policy=deny");
        assertThat(err.invoker()).isEmpty();
    }

    @Test
    public void anonymousPermitPolicyGrantsRequestedInterfaces() {
        CapabilityPolicyResolver.setAnonymousPolicy(
                CapabilityPolicyResolver.AnonymousPolicy.PERMIT);
        final ExtensionManifest manifest = new ExtensionManifest(
                "ext", "1.0.0", "",
                Set.of("graph-callbacks"),
                Set.of(),
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                CapabilityModel.AMBIENT);

        // Null component signals "test path — skip declared-imports gate";
        // the resolver trusts the manifest.
        final CapabilityGrant g = CapabilityPolicyResolver.resolve(
                EXT_URI, null, manifest, null);
        assertThat(g.grantedInterfaces()).containsExactly("graph-callbacks");
        assertThat(g.invokerPrincipal()).isEmpty();
    }

    @Test
    public void anonymousInheritPolicyGrantsRequestedInterfaces() {
        CapabilityPolicyResolver.setAnonymousPolicy(
                CapabilityPolicyResolver.AnonymousPolicy.INHERIT);
        final ExtensionManifest manifest = new ExtensionManifest(
                "ext", "1.0.0", "",
                Set.of("graph-callbacks"),
                Set.of(),
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                CapabilityModel.AMBIENT);

        final CapabilityGrant g = CapabilityPolicyResolver.resolve(
                EXT_URI, null, manifest, null);
        assertThat(g.grantedInterfaces()).containsExactly("graph-callbacks");
    }

    @Test
    public void nullManifestFailsCleanly() {
        final Throwable thrown = catchThrowable(() -> CapabilityPolicyResolver.resolve(
                EXT_URI, null, null, fakeSubject("alice")));
        assertThat(thrown).isInstanceOf(WfCapabilityError.LoadTimeDenied.class);
        assertThat(((WfCapabilityError.LoadTimeDenied) thrown).policySource())
                .contains("manifest-required");
    }

    @Test
    public void methodPoliciesForDeniedInterfacesAreDropped() {
        // Manifest declares a method policy for an interface the
        // component doesn't import — the grant must not carry a policy
        // for an interface it also doesn't grant.
        final ExtensionManifest manifest = new ExtensionManifest(
                "ext", "1.0.0", "",
                Set.of(),
                Set.of("graph-callbacks", "http-callbacks"),   // both optional
                Map.of("graph-callbacks",
                        MethodPolicy.allowOnly("graph-callbacks", Set.of("execute-query"))),
                HostAllowlist.ALLOW_NONE,
                CapabilityModel.AMBIENT);
        final Component component = fakeComponent(List.of(
                "tegmentum:webfunction/http-callbacks@0.1.0"));   // only http

        final CapabilityGrant g = CapabilityPolicyResolver.resolve(
                EXT_URI, component, manifest, fakeSubject("alice"));
        assertThat(g.grantedInterfaces()).containsExactly("http-callbacks");
        assertThat(g.methodPolicies()).doesNotContainKey("graph-callbacks");
    }

    @Test
    public void bareInterfaceNameStripsPackageAndVersion() {
        assertThat(CapabilityPolicyResolver.bareInterfaceName(
                "tegmentum:webfunction/graph-callbacks@0.1.0"))
                .isEqualTo("graph-callbacks");
        assertThat(CapabilityPolicyResolver.bareInterfaceName("graph-callbacks"))
                .isEqualTo("graph-callbacks");
        assertThat(CapabilityPolicyResolver.bareInterfaceName("pkg/graph-callbacks"))
                .isEqualTo("graph-callbacks");
        assertThat(CapabilityPolicyResolver.bareInterfaceName("graph-callbacks@0.1.0"))
                .isEqualTo("graph-callbacks");
        assertThat(CapabilityPolicyResolver.bareInterfaceName("")).isEmpty();
        assertThat(CapabilityPolicyResolver.bareInterfaceName(null)).isEmpty();
    }

    @Test
    public void setAnonymousPolicyNullRestoresDefault() {
        CapabilityPolicyResolver.setAnonymousPolicy(
                CapabilityPolicyResolver.AnonymousPolicy.PERMIT);
        assertThat(CapabilityPolicyResolver.anonymousPolicy())
                .isEqualTo(CapabilityPolicyResolver.AnonymousPolicy.PERMIT);
        CapabilityPolicyResolver.setAnonymousPolicy(null);
        assertThat(CapabilityPolicyResolver.anonymousPolicy())
                .isEqualTo(CapabilityPolicyResolver.DEFAULT_ANONYMOUS_POLICY);
    }

    // Minimal Component stub that only supports the importedInterfaces()
    // method the resolver reads. Other methods throw so any accidental
    // consumer surfaces loudly.
    private static Component fakeComponent(final List<String> paths) {
        return new Component() {
            @Override public List<String> importedInterfaces() { return paths; }
            @Override public ComponentInstance instantiate() {
                throw new UnsupportedOperationException("test stub"); }
            @Override public ComponentInstance instantiate(final LinkingContext linkingContext) {
                throw new UnsupportedOperationException("test stub"); }
            @Override public void close() { }
        };
    }

    /**
     * Java-Proxy Subject stub covering just {@code getPrincipal()}.
     * Every other method returns a JDK default (null / false / 0) — the
     * resolver only reads the principal, so any other consumer surfaces
     * as a null the caller can spot.
     */
    private static Subject fakeSubject(final String principal) {
        return (Subject) Proxy.newProxyInstance(
                Subject.class.getClassLoader(),
                new Class[]{Subject.class},
                (proxy, method, args) -> {
                    if ("getPrincipal".equals(method.getName())) return principal;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
    }
}
