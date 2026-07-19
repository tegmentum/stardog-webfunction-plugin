package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.webassembly4j.api.Component;
import ai.tegmentum.webassembly4j.api.ComponentInstance;
import ai.tegmentum.webassembly4j.api.LinkingContext;
import org.apache.shiro.subject.Subject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Capability refactor R3 — grant resolver refactored to consume a
 * {@link CapabilityPolicyStore} instead of a TOML manifest.
 *
 * <p>Covers the algorithm on {@link CapabilityPolicyResolver}: happy
 * path, unknown-extension policy branches (deny/permit/inherit), all
 * three anonymous policies, the fully-qualified WIT path stripping
 * helper, and the "policy store not installed" surface.
 */
public class TestCapabilityPolicyResolver {

    private static final URL EXT_URL = url("file:///ext.wasm");
    private static final String EXT_URI = EXT_URL.toString();

    @Before
    public void installStubStore() {
        CapabilityPolicyResolver.setPolicyStore(new StubStore());
    }

    @After
    public void reset() {
        CapabilityPolicyResolver.setPolicyStore(null);
        CapabilityPolicyResolver.setAnonymousPolicy(
                CapabilityPolicyResolver.DEFAULT_ANONYMOUS_POLICY);
        CapabilityPolicyResolver.setUnknownExtensionPolicy(
                CapabilityPolicyResolver.DEFAULT_UNKNOWN_EXTENSION_POLICY);
    }

    @Test
    public void happyPathGrantEqualsStoreAllowsWhenComponentDeclares() {
        stubStore().set(new PolicyTriples(
                Set.of("graph-callbacks", "http-callbacks"),
                Set.of("graph-callbacks/execute-query"),
                Set.of("api.acme.com")));
        final Component component = fakeComponent(List.of(
                "tegmentum:webfunction/graph-callbacks@0.1.0",
                "tegmentum:webfunction/http-callbacks@0.1.0",
                // Declared but not policy-allowed — must not leak into grant.
                "tegmentum:webfunction/fulltext-callbacks@0.1.0"));
        final Subject subject = fakeSubject("alice");

        final CapabilityGrant g = CapabilityPolicyResolver.resolve(
                EXT_URL, component, subject);

        assertThat(g.extensionUri()).isEqualTo(EXT_URI);
        assertThat(g.grantedInterfaces())
                .containsExactlyInAnyOrder("graph-callbacks", "http-callbacks");
        assertThat(g.allowsInterface("fulltext-callbacks")).isFalse();
        assertThat(g.methodPolicies()).containsKey("graph-callbacks");
        assertThat(g.methodPolicies().get("graph-callbacks").allows("execute-query")).isTrue();
        assertThat(g.methodPolicies().get("graph-callbacks").allows("execute-update")).isFalse();
        assertThat(g.httpAllowlist().matches("api.acme.com")).isTrue();
        assertThat(g.invokerPrincipal()).isEqualTo("alice");
    }

    @Test
    public void policyAllowsInterfaceNotDeclaredByComponentDropsIt() {
        // Store trusts graph-callbacks but component doesn't import it.
        // Intersection produces an empty grant — no throw (no "required"
        // concept anymore; the store just names what's allowed).
        stubStore().set(new PolicyTriples(
                Set.of("graph-callbacks"),
                Set.of(),
                Set.of()));
        final Component component = fakeComponent(List.of(
                "tegmentum:webfunction/http-callbacks@0.1.0"));

        final CapabilityGrant g = CapabilityPolicyResolver.resolve(
                EXT_URL, component, fakeSubject("alice"));
        assertThat(g.grantedInterfaces()).isEmpty();
    }

    @Test
    public void unknownExtensionDenyThrowsUnknownExtensionError() {
        stubStore().set(PolicyTriples.EMPTY);
        CapabilityPolicyResolver.setUnknownExtensionPolicy(
                CapabilityPolicyResolver.UnknownExtensionPolicy.DENY);

        final Throwable thrown = catchThrowable(() -> CapabilityPolicyResolver.resolve(
                EXT_URL, fakeComponent(List.of()), fakeSubject("alice")));
        assertThat(thrown).isInstanceOf(WfCapabilityError.UnknownExtension.class);
        final WfCapabilityError.UnknownExtension err = (WfCapabilityError.UnknownExtension) thrown;
        assertThat(err.invoker()).isEqualTo("alice");
        assertThat(err.policySource()).contains("deny");
    }

    @Test
    public void unknownExtensionPermitTreatsAsPreCapability() {
        stubStore().set(PolicyTriples.EMPTY);
        CapabilityPolicyResolver.setUnknownExtensionPolicy(
                CapabilityPolicyResolver.UnknownExtensionPolicy.PERMIT);

        final Component component = fakeComponent(List.of(
                "tegmentum:webfunction/graph-callbacks@0.1.0"));
        final CapabilityGrant g = CapabilityPolicyResolver.resolve(
                EXT_URL, component, fakeSubject("alice"));
        // Under permit, the intersection with the extension's declared
        // imports lets graph-callbacks through even without an explicit
        // store row.
        assertThat(g.grantedInterfaces()).contains("graph-callbacks");
    }

    @Test
    public void unknownExtensionInheritTreatsAsPreCapability() {
        stubStore().set(PolicyTriples.EMPTY);
        CapabilityPolicyResolver.setUnknownExtensionPolicy(
                CapabilityPolicyResolver.UnknownExtensionPolicy.INHERIT);

        final Component component = fakeComponent(List.of(
                "tegmentum:webfunction/http-callbacks@0.1.0"));
        final CapabilityGrant g = CapabilityPolicyResolver.resolve(
                EXT_URL, component, fakeSubject("alice"));
        assertThat(g.grantedInterfaces()).contains("http-callbacks");
    }

    @Test
    public void anonymousDenyPolicyThrowsWhenSubjectAbsent() {
        CapabilityPolicyResolver.setAnonymousPolicy(
                CapabilityPolicyResolver.AnonymousPolicy.DENY);

        final Throwable thrown = catchThrowable(() -> CapabilityPolicyResolver.resolve(
                EXT_URL, null, null));
        assertThat(thrown).isInstanceOf(WfCapabilityError.LoadTimeDenied.class);
        final WfCapabilityError.LoadTimeDenied err = (WfCapabilityError.LoadTimeDenied) thrown;
        assertThat(err.resolutionStage()).isEqualTo("shiro");
        assertThat(err.policySource()).contains("anonymous-policy=deny");
        assertThat(err.invoker()).isEmpty();
    }

    @Test
    public void anonymousPermitPolicyGrantsPolicyAllowedInterfaces() {
        CapabilityPolicyResolver.setAnonymousPolicy(
                CapabilityPolicyResolver.AnonymousPolicy.PERMIT);
        stubStore().set(new PolicyTriples(
                Set.of("graph-callbacks"), Set.of(), Set.of()));

        final CapabilityGrant g = CapabilityPolicyResolver.resolve(
                EXT_URL, null, null);
        assertThat(g.grantedInterfaces()).containsExactly("graph-callbacks");
        assertThat(g.invokerPrincipal()).isEmpty();
    }

    @Test
    public void anonymousInheritPolicyGrantsPolicyAllowedInterfaces() {
        CapabilityPolicyResolver.setAnonymousPolicy(
                CapabilityPolicyResolver.AnonymousPolicy.INHERIT);
        stubStore().set(new PolicyTriples(
                Set.of("graph-callbacks"), Set.of(), Set.of()));

        final CapabilityGrant g = CapabilityPolicyResolver.resolve(
                EXT_URL, null, null);
        assertThat(g.grantedInterfaces()).containsExactly("graph-callbacks");
    }

    @Test
    public void missingPolicyStoreSurfacesAsPolicyStoreUnavailable() {
        CapabilityPolicyResolver.setPolicyStore(null);

        final Throwable thrown = catchThrowable(() -> CapabilityPolicyResolver.resolve(
                EXT_URL, null, fakeSubject("alice")));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PolicyStoreUnavailable.class);
        assertThat(((WfCapabilityError.PolicyStoreUnavailable) thrown).extensionUri())
                .isEqualTo(EXT_URI);
    }

    @Test
    public void notReadyPolicyStoreSurfacesAsPolicyStoreUnavailable() {
        CapabilityPolicyResolver.setPolicyStore(new CapabilityPolicyStore() {
            @Override public Optional<PolicyTriples> resolveFor(URL u) { return Optional.empty(); }
            @Override public boolean isReady() { return false; }
        });

        final Throwable thrown = catchThrowable(() -> CapabilityPolicyResolver.resolve(
                EXT_URL, null, fakeSubject("alice")));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PolicyStoreUnavailable.class);
    }

    @Test
    public void phase5AllowlistsPropagateFromTriplesToGrant() {
        // Store returns Phase 5 axes → resolver wraps them into the
        // grant's HttpPathAllowlist + WasmCalleeAllowlist. Empty
        // policy-axis → grant carries the ALLOW_NONE singleton.
        stubStore().set(new PolicyTriples(
                Set.of("http-callbacks", "wasm-callbacks"),
                Set.of(),
                Set.of(),
                Set.of("api.acme.com/public/"),
                Set.of("ipfs://QmCallee")));
        final Component component = fakeComponent(List.of(
                "tegmentum:webfunction/http-callbacks@0.1.0",
                "tegmentum:webfunction/wasm-callbacks@0.1.0"));
        final CapabilityGrant g = CapabilityPolicyResolver.resolve(
                EXT_URL, component, fakeSubject("alice"));
        assertThat(g.httpPathAllowlist().isEmpty()).isFalse();
        assertThat(g.httpPathAllowlist().matches("api.acme.com/public/orders")).isTrue();
        assertThat(g.wasmCalleeAllowlist().isEmpty()).isFalse();
        assertThat(g.wasmCalleeAllowlist().matches("ipfs://QmCallee")).isTrue();
        assertThat(g.wasmCalleeAllowlist().matches("ipfs://QmOther")).isFalse();
    }

    @Test
    public void phase5AllowlistsDefaultAllowNoneWhenAbsentInTriples() {
        // Store returns no Phase 5 axes → grant uses ALLOW_NONE singletons
        // — enforcer treats those as "no restriction beyond coarser
        // interface / method / host checks".
        stubStore().set(new PolicyTriples(
                Set.of("http-callbacks"),
                Set.of(),
                Set.of("api.acme.com")));
        final Component component = fakeComponent(List.of(
                "tegmentum:webfunction/http-callbacks@0.1.0"));
        final CapabilityGrant g = CapabilityPolicyResolver.resolve(
                EXT_URL, component, fakeSubject("alice"));
        assertThat(g.httpPathAllowlist()).isSameAs(HttpPathAllowlist.ALLOW_NONE);
        assertThat(g.wasmCalleeAllowlist()).isSameAs(WasmCalleeAllowlist.ALLOW_NONE);
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

    @Test
    public void setUnknownExtensionPolicyNullRestoresDefault() {
        CapabilityPolicyResolver.setUnknownExtensionPolicy(
                CapabilityPolicyResolver.UnknownExtensionPolicy.PERMIT);
        assertThat(CapabilityPolicyResolver.unknownExtensionPolicy())
                .isEqualTo(CapabilityPolicyResolver.UnknownExtensionPolicy.PERMIT);
        CapabilityPolicyResolver.setUnknownExtensionPolicy(null);
        assertThat(CapabilityPolicyResolver.unknownExtensionPolicy())
                .isEqualTo(CapabilityPolicyResolver.DEFAULT_UNKNOWN_EXTENSION_POLICY);
    }

    // ------------------------------------------------------------
    // Test doubles.
    // ------------------------------------------------------------

    private StubStore stubStore() {
        return (StubStore) CapabilityPolicyResolver.policyStore().orElseThrow();
    }

    /** Set-once-then-read stub — every resolve consults the same value. */
    private static final class StubStore implements CapabilityPolicyStore {
        private final AtomicReference<PolicyTriples> current =
                new AtomicReference<>(PolicyTriples.EMPTY);
        void set(final PolicyTriples t) { current.set(t); }
        @Override public Optional<PolicyTriples> resolveFor(URL u) {
            return Optional.of(current.get());
        }
        @Override public boolean isReady() { return true; }
    }

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

    private static URL url(final String s) {
        try {
            return new URL(s);
        } catch (java.net.MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
