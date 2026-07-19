package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Capability wave Phase 1a — parsed extension manifest shape.
 *
 * <p>Covers the record contract (non-null fields, defensive copy) and
 * the {@link ExtensionManifest#ABSENT} sentinel used when the sidecar
 * is missing and {@code require-manifest=false}.
 */
public class TestExtensionManifest {

    @Test
    public void happyPathManifestConstructsWithAllFields() {
        final Map<String, MethodPolicy> methods = new LinkedHashMap<>();
        methods.put("graph-callbacks",
                MethodPolicy.allowOnly("graph-callbacks", Set.of("execute-query")));
        final ExtensionManifest m = new ExtensionManifest(
                "my-extension",
                "0.3.0",
                "did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK",
                Set.of("graph-callbacks"),
                Set.of("http-callbacks"),
                methods,
                new HostAllowlist(List.of("api.acme.com")),
                CapabilityModel.INVOKER_SUBJECT);
        assertThat(m.name()).isEqualTo("my-extension");
        assertThat(m.version()).isEqualTo("0.3.0");
        assertThat(m.signer()).startsWith("did:key:");
        assertThat(m.requiredInterfaces()).containsExactly("graph-callbacks");
        assertThat(m.optionalInterfaces()).containsExactly("http-callbacks");
        assertThat(m.methodPolicies()).containsKey("graph-callbacks");
        assertThat(m.httpAllowlist().matches("api.acme.com")).isTrue();
        assertThat(m.model()).isEqualTo(CapabilityModel.INVOKER_SUBJECT);
    }

    @Test
    public void absentSentinelHasEmptyDeclarationAndAmbientModel() {
        final ExtensionManifest m = ExtensionManifest.ABSENT;
        assertThat(m.name()).isEmpty();
        assertThat(m.version()).isEmpty();
        assertThat(m.signer()).isEmpty();
        assertThat(m.requiredInterfaces()).isEmpty();
        assertThat(m.optionalInterfaces()).isEmpty();
        assertThat(m.methodPolicies()).isEmpty();
        // Deny-all HTTP by default when the sidecar is missing.
        assertThat(m.httpAllowlist().matches("api.acme.com")).isFalse();
        // Back-compat default.
        assertThat(m.model()).isEqualTo(CapabilityModel.AMBIENT);
    }

    @Test
    public void anyNullFieldRejected() {
        // Just a few of the eight — the pattern is uniform.
        assertThat(catchThrowable(() -> new ExtensionManifest(
                null, "", "", Set.of(), Set.of(), Map.of(),
                HostAllowlist.ALLOW_NONE, CapabilityModel.AMBIENT)))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new ExtensionManifest(
                "", "", "", Set.of(), Set.of(), null,
                HostAllowlist.ALLOW_NONE, CapabilityModel.AMBIENT)))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new ExtensionManifest(
                "", "", "", Set.of(), Set.of(), Map.of(),
                null, CapabilityModel.AMBIENT)))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new ExtensionManifest(
                "", "", "", Set.of(), Set.of(), Map.of(),
                HostAllowlist.ALLOW_NONE, null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void constructorDefensiveCopyIsolatesFromCaller() {
        final Set<String> required = new HashSet<>();
        required.add("graph-callbacks");
        final ExtensionManifest m = new ExtensionManifest(
                "n", "1", "", required, Set.of(), Map.of(),
                HostAllowlist.ALLOW_NONE, CapabilityModel.AMBIENT);
        // Mutating the caller's set must not leak into the manifest.
        required.add("http-callbacks");
        assertThat(m.requiredInterfaces()).containsExactly("graph-callbacks");
        assertThat(catchThrowable(() -> m.requiredInterfaces().add("http-callbacks")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
