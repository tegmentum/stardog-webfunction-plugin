package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.Test;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Capability wave Phase 5a — wasm callee URL allowlist matcher.
 *
 * <p>Covers exact-URL match on both {@link URL} and {@link String}
 * overloads, cross-URL-scheme parity (ipfs://, https://, file://),
 * mismatch, null rejection, the {@link WasmCalleeAllowlist#ALLOW_NONE}
 * sentinel, and the {@link WasmCalleeAllowlist#isEmpty()} discriminator
 * the {@link CapabilityEnforcer} uses to short-circuit the check when
 * an allowlist imposes no restriction.
 */
public class TestWasmCalleeAllowlist {

    @Test
    public void exactMatchAcceptsSameUrl() {
        final WasmCalleeAllowlist a = WasmCalleeAllowlist.of(List.of(
                "ipfs://QmCallee"));
        assertThat(a.matches("ipfs://QmCallee")).isTrue();
        assertThat(a.matches("ipfs://QmOther")).isFalse();
    }

    @Test
    public void exactMatchIsCaseSensitiveOnPath() {
        // Callee URLs are compared as-is — the path portion is
        // case-sensitive (IPFS CIDs and HTTP paths both are).
        final WasmCalleeAllowlist a = WasmCalleeAllowlist.of(List.of(
                "ipfs://QmCallee"));
        assertThat(a.matches("ipfs://qmcallee")).isFalse();
    }

    @Test
    public void urlOverloadAcceptsExactMatch() throws MalformedURLException {
        final WasmCalleeAllowlist a = WasmCalleeAllowlist.of(List.of(
                "https://registry.example.org/plugin.wasm"));
        assertThat(a.matches(new URL("https://registry.example.org/plugin.wasm"))).isTrue();
        assertThat(a.matches(new URL("https://registry.example.org/other.wasm"))).isFalse();
    }

    @Test
    public void schemeAgnosticParityAcrossIpfsHttpsFile() {
        final WasmCalleeAllowlist a = WasmCalleeAllowlist.of(List.of(
                "ipfs://QmCallee",
                "https://registry.example.org/plugin.wasm",
                "file:///opt/wasm/local.wasm"));
        assertThat(a.matches("ipfs://QmCallee")).isTrue();
        assertThat(a.matches("https://registry.example.org/plugin.wasm")).isTrue();
        assertThat(a.matches("file:///opt/wasm/local.wasm")).isTrue();
        assertThat(a.matches("http://registry.example.org/plugin.wasm")).isFalse();
    }

    @Test
    public void multiplePatternsAllChecked() {
        final WasmCalleeAllowlist a = WasmCalleeAllowlist.of(List.of(
                "ipfs://QmOne",
                "ipfs://QmTwo",
                "https://example.org/p.wasm"));
        assertThat(a.matches("ipfs://QmOne")).isTrue();
        assertThat(a.matches("ipfs://QmTwo")).isTrue();
        assertThat(a.matches("https://example.org/p.wasm")).isTrue();
        assertThat(a.matches("ipfs://QmThree")).isFalse();
    }

    @Test
    public void allowNoneMatchesNothing() {
        assertThat(WasmCalleeAllowlist.ALLOW_NONE.matches("ipfs://QmAny")).isFalse();
        assertThat(WasmCalleeAllowlist.ALLOW_NONE.matches((URL) null)).isFalse();
        assertThat(WasmCalleeAllowlist.ALLOW_NONE.size()).isZero();
        assertThat(WasmCalleeAllowlist.ALLOW_NONE.isEmpty()).isTrue();
    }

    @Test
    public void isEmptyDiscriminatesConfiguredFromEmpty() {
        assertThat(WasmCalleeAllowlist.of(List.of()).isEmpty()).isTrue();
        assertThat(WasmCalleeAllowlist.of(List.of("ipfs://Qm")).isEmpty()).isFalse();
    }

    @Test
    public void nullAndEmptyInputNeverMatch() {
        final WasmCalleeAllowlist a = WasmCalleeAllowlist.of(List.of("ipfs://Qm"));
        assertThat(a.matches((String) null)).isFalse();
        assertThat(a.matches((URL) null)).isFalse();
        assertThat(a.matches("")).isFalse();
    }

    @Test
    public void nullPatternsSetRejected() {
        assertThat(catchThrowable(() -> new WasmCalleeAllowlist((Set<String>) null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void nullPatternInSetRejected() {
        final Set<String> withNull = new java.util.LinkedHashSet<>();
        withNull.add("ipfs://QmOk");
        withNull.add(null);
        assertThat(catchThrowable(() -> new WasmCalleeAllowlist(withNull)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void storedSetIsUnmodifiable() {
        final WasmCalleeAllowlist a = WasmCalleeAllowlist.of(List.of("ipfs://Qm"));
        assertThat(catchThrowable(() -> a.patterns().add("ipfs://QmEvil")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void patternsListPreservesInsertionOrder() {
        final WasmCalleeAllowlist a = WasmCalleeAllowlist.of(List.of(
                "ipfs://QmA", "ipfs://QmB", "ipfs://QmC"));
        assertThat(a.patternsList())
                .containsExactly("ipfs://QmA", "ipfs://QmB", "ipfs://QmC");
    }

    @Test
    public void sizeReflectsConfiguredCount() {
        assertThat(WasmCalleeAllowlist.of(List.of(
                "ipfs://a", "ipfs://b", "ipfs://c")).size()).isEqualTo(3);
    }
}
