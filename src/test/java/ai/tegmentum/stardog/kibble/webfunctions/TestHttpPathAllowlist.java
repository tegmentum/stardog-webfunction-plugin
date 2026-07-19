package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Capability wave Phase 5a — HTTP path-prefix allowlist matcher.
 *
 * <p>Covers exact prefix match, case-insensitive normalization,
 * non-matching input, null/empty rejection, the {@link HttpPathAllowlist#ALLOW_NONE}
 * sentinel, and the {@link HttpPathAllowlist#isEmpty()} discriminator
 * the {@link CapabilityEnforcer} uses to short-circuit the check when
 * an allowlist imposes no restriction.
 */
public class TestHttpPathAllowlist {

    @Test
    public void prefixMatchAcceptsExactStart() {
        final HttpPathAllowlist a = new HttpPathAllowlist(List.of("api.acme.com/public/"));
        assertThat(a.matches("api.acme.com/public/")).isTrue();
        assertThat(a.matches("api.acme.com/public/orders")).isTrue();
        assertThat(a.matches("api.acme.com/public/orders/42")).isTrue();
    }

    @Test
    public void prefixMatchRejectsWrongPath() {
        final HttpPathAllowlist a = new HttpPathAllowlist(List.of("api.acme.com/public/"));
        assertThat(a.matches("api.acme.com/private/orders")).isFalse();
        assertThat(a.matches("api.acme.com/publi")).isFalse();
    }

    @Test
    public void prefixMatchRejectsWrongHost() {
        final HttpPathAllowlist a = new HttpPathAllowlist(List.of("api.acme.com/public/"));
        assertThat(a.matches("other.host/public/orders")).isFalse();
    }

    @Test
    public void prefixMatchIsCaseInsensitive() {
        final HttpPathAllowlist a = new HttpPathAllowlist(List.of("api.acme.com/public/"));
        assertThat(a.matches("API.ACME.COM/PUBLIC/orders")).isTrue();
        assertThat(a.matches("Api.Acme.Com/Public/orders")).isTrue();
    }

    @Test
    public void patternCaseInsensitiveNormalized() {
        // Pattern in upper case still matches lower-case input.
        final HttpPathAllowlist a = new HttpPathAllowlist(List.of("API.ACME.COM/PUBLIC/"));
        assertThat(a.matches("api.acme.com/public/orders")).isTrue();
    }

    @Test
    public void multiplePatternsAllChecked() {
        final HttpPathAllowlist a = new HttpPathAllowlist(List.of(
                "api.acme.com/public/",
                "docs.example.org/v2/",
                "cdn.company.io/assets/"));
        assertThat(a.matches("api.acme.com/public/orders")).isTrue();
        assertThat(a.matches("docs.example.org/v2/manual")).isTrue();
        assertThat(a.matches("cdn.company.io/assets/logo.png")).isTrue();
        assertThat(a.matches("random.host/anything")).isFalse();
    }

    @Test
    public void allowNoneMatchesNothing() {
        assertThat(HttpPathAllowlist.ALLOW_NONE.matches("api.acme.com/public/orders")).isFalse();
        assertThat(HttpPathAllowlist.ALLOW_NONE.size()).isZero();
        assertThat(HttpPathAllowlist.ALLOW_NONE.isEmpty()).isTrue();
    }

    @Test
    public void isEmptyDiscriminatesConfiguredFromEmpty() {
        assertThat(new HttpPathAllowlist(List.of()).isEmpty()).isTrue();
        assertThat(new HttpPathAllowlist(List.of("host/")).isEmpty()).isFalse();
    }

    @Test
    public void nullAndEmptyInputNeverMatch() {
        final HttpPathAllowlist a = new HttpPathAllowlist(List.of("api.acme.com/public/"));
        assertThat(a.matches(null)).isFalse();
        assertThat(a.matches("")).isFalse();
    }

    @Test
    public void nullPatternsRejected() {
        assertThat(catchThrowable(() -> new HttpPathAllowlist(null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void nullPatternInListRejected() {
        assertThat(catchThrowable(
                () -> new HttpPathAllowlist(java.util.Arrays.asList("ok/path/", null))))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void storedListIsUnmodifiable() {
        final HttpPathAllowlist a = new HttpPathAllowlist(List.of("api.acme.com/public/"));
        assertThat(catchThrowable(() -> a.patterns().add("other/path/")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void sizeReflectsConfiguredCount() {
        assertThat(new HttpPathAllowlist(List.of("a/", "b/", "c/")).size()).isEqualTo(3);
    }
}
