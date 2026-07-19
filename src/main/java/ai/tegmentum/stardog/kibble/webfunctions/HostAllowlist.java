package ai.tegmentum.stardog.kibble.webfunctions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Coarse hostname allowlist for HTTP host-callback dispatch. Shape from
 * {@code capability-implementation.md} §2 / strategy memo §6.
 *
 * <p>MVP grants the "coarse callee-argument" axis from strategy memo §4 —
 * hostname-only match, no path/header/method matching. Fine-grained forms
 * land in Phase 5 per implementation memo §14.
 *
 * <p>Two match modes:
 * <ul>
 *   <li><b>Exact.</b> Pattern {@code api.acme.com} matches only
 *       {@code api.acme.com}. Case-insensitive per RFC 1035 §2.3.3.</li>
 *   <li><b>Leading-{@code *.} glob.</b> Pattern {@code *.example.org}
 *       matches every hostname ending in {@code .example.org} — i.e.
 *       {@code api.example.org} and {@code v1.api.example.org} but not
 *       {@code example.org} itself. Follows the TLS certificate wildcard
 *       convention (RFC 6125 §6.4.3), which admins already know.</li>
 * </ul>
 *
 * <p>Other pattern shapes (embedded {@code *}, path patterns, port
 * matching) intentionally deferred — every rejected shape produces
 * silently-broader matches when mis-configured, so leaving them out is
 * safer than shipping a half-baked matcher. Phase 5 adds
 * {@code HttpPathAllowlist} for path patterns.
 *
 * <p>{@link #ALLOW_NONE} — deny-all singleton for grants that name no
 * hosts (interfaces where the manifest omits the {@code allow_hosts}
 * subsection). {@link #matches(String)} always returns false; callers
 * treat this as "no HTTP callback authorized".
 */
public record HostAllowlist(
        List<String> patterns
) {

    /** Deny-all — matches nothing. Use when the manifest omits {@code allow_hosts}. */
    public static final HostAllowlist ALLOW_NONE = new HostAllowlist(List.of());

    public HostAllowlist {
        Objects.requireNonNull(patterns, "patterns");
        // Defensive copy + lowercase normalize so pattern-side case can't
        // sneak past the matcher.
        final List<String> copy = new ArrayList<>(patterns.size());
        for (final String p : patterns) {
            Objects.requireNonNull(p, "pattern in patterns list");
            copy.add(p.toLowerCase(Locale.ROOT));
        }
        patterns = Collections.unmodifiableList(copy);
    }

    /**
     * True iff {@code hostname} matches any configured pattern. Null and
     * empty hostnames never match — a caller with no URL to check has
     * no authorization to reach anywhere.
     */
    public boolean matches(final String hostname) {
        if (hostname == null || hostname.isEmpty()) return false;
        final String hn = hostname.toLowerCase(Locale.ROOT);
        for (final String pattern : patterns) {
            if (pattern.startsWith("*.")) {
                // Wildcard glob: match on suffix beyond the leading "*.".
                // "*.example.org" matches "api.example.org" but not "example.org"
                // (per RFC 6125 §6.4.3 — wildcard covers one or more labels).
                final String suffix = pattern.substring(1); // ".example.org"
                if (hn.length() > suffix.length() && hn.endsWith(suffix)) {
                    return true;
                }
            } else if (pattern.equals(hn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Number of configured patterns — for audit-row summarization and
     * diagnostic printing. O(1).
     */
    public int size() {
        return patterns.size();
    }

    /**
     * True when no patterns are configured. Phase 5 enforcement treats an
     * empty allowlist as "no restriction beyond the coarser interface/
     * method checks" — only NON-EMPTY allowlists impose restrictions, so
     * a policy that grants {@code cap:allowInterface cap:HttpCallbacks}
     * without any {@code cap:allowHost} triples still passes the host
     * check.
     */
    public boolean isEmpty() {
        return patterns.isEmpty();
    }
}
