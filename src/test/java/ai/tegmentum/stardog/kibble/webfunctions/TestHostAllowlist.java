package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Capability wave Phase 1a — HTTP host allowlist matcher.
 *
 * <p>Covers exact match (case-insensitive), leading-{@code *.} glob
 * (RFC 6125 §6.4.3 convention), null/empty rejection, and the
 * {@link HostAllowlist#ALLOW_NONE} sentinel.
 */
public class TestHostAllowlist {

    @Test
    public void exactMatchAcceptsSameHostname() {
        final HostAllowlist a = new HostAllowlist(List.of("api.acme.com"));
        assertThat(a.matches("api.acme.com")).isTrue();
        assertThat(a.matches("api.other.com")).isFalse();
        assertThat(a.matches("acme.com")).isFalse();
    }

    @Test
    public void exactMatchIsCaseInsensitive() {
        final HostAllowlist a = new HostAllowlist(List.of("api.acme.com"));
        assertThat(a.matches("API.ACME.COM")).isTrue();
        assertThat(a.matches("Api.Acme.Com")).isTrue();
    }

    @Test
    public void patternCaseInsensitiveNormalized() {
        // Pattern in upper case still matches lower-case hostname.
        final HostAllowlist a = new HostAllowlist(List.of("API.ACME.COM"));
        assertThat(a.matches("api.acme.com")).isTrue();
    }

    @Test
    public void wildcardMatchesSingleLabel() {
        final HostAllowlist a = new HostAllowlist(List.of("*.example.org"));
        assertThat(a.matches("api.example.org")).isTrue();
    }

    @Test
    public void wildcardMatchesMultipleLabels() {
        // Deliberate deviation from strict RFC 6125 — a permissive
        // "endswith" match covers both api.example.org and
        // v1.api.example.org, which admins expect from a "*.example.org"
        // rule per docs on HostAllowlist.
        final HostAllowlist a = new HostAllowlist(List.of("*.example.org"));
        assertThat(a.matches("v1.api.example.org")).isTrue();
    }

    @Test
    public void wildcardDoesNotMatchBareDomain() {
        final HostAllowlist a = new HostAllowlist(List.of("*.example.org"));
        assertThat(a.matches("example.org")).isFalse();
    }

    @Test
    public void wildcardDoesNotMatchDifferentDomain() {
        final HostAllowlist a = new HostAllowlist(List.of("*.example.org"));
        assertThat(a.matches("api.other.org")).isFalse();
    }

    @Test
    public void multiplePatternsAllChecked() {
        final HostAllowlist a = new HostAllowlist(List.of(
                "api.acme.com", "*.example.org", "cdn.company.io"));
        assertThat(a.matches("api.acme.com")).isTrue();
        assertThat(a.matches("api.example.org")).isTrue();
        assertThat(a.matches("cdn.company.io")).isTrue();
        assertThat(a.matches("random.host")).isFalse();
    }

    @Test
    public void allowNoneMatchesNothing() {
        assertThat(HostAllowlist.ALLOW_NONE.matches("api.acme.com")).isFalse();
        assertThat(HostAllowlist.ALLOW_NONE.matches("*.anything")).isFalse();
        assertThat(HostAllowlist.ALLOW_NONE.size()).isZero();
        assertThat(HostAllowlist.ALLOW_NONE.isEmpty()).isTrue();
    }

    @Test
    public void isEmptyDiscriminatesConfiguredFromEmpty() {
        assertThat(new HostAllowlist(List.of()).isEmpty()).isTrue();
        assertThat(new HostAllowlist(List.of("api.acme.com")).isEmpty()).isFalse();
    }

    @Test
    public void nullAndEmptyHostnameNeverMatch() {
        final HostAllowlist a = new HostAllowlist(List.of("api.acme.com"));
        assertThat(a.matches(null)).isFalse();
        assertThat(a.matches("")).isFalse();
    }

    @Test
    public void nullPatternsRejected() {
        assertThat(catchThrowable(() -> new HostAllowlist(null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void nullPatternInListRejected() {
        assertThat(catchThrowable(
                () -> new HostAllowlist(java.util.Arrays.asList("ok.host", null))))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void storedListIsUnmodifiable() {
        final HostAllowlist a = new HostAllowlist(List.of("api.acme.com"));
        assertThat(catchThrowable(() -> a.patterns().add("other.host")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
