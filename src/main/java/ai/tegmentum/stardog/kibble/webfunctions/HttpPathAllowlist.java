package ai.tegmentum.stardog.kibble.webfunctions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Fine-grained path-prefix allowlist for HTTP host-callback dispatch —
 * Phase 5 companion to {@link HostAllowlist}. Shape from
 * {@code capability-implementation.md} §14 (Phase 5 rollout note).
 *
 * <p>Each pattern is a plain string prefix compared against a caller-
 * supplied {@code host+path} value (e.g. {@code "api.acme.com/public/"}
 * matches {@code "api.acme.com/public/orders"} but not
 * {@code "api.acme.com/private/orders"} nor
 * {@code "other.host/public/orders"}). Case-insensitive on the host
 * portion by normalizing both pattern and input to lower-case — the
 * scheme is stripped by the caller before comparison, so the leading
 * character is the hostname's first character.
 *
 * <p>Two match modes are intentionally omitted:
 * <ul>
 *   <li>No wildcard on the host component — combine with
 *       {@link HostAllowlist} which owns that axis.</li>
 *   <li>No regex — every rejected pattern shape produces silently-broader
 *       matches when mis-configured, so leaving them out is safer than
 *       shipping a half-baked matcher (matches {@link HostAllowlist}'s
 *       conservative stance).</li>
 * </ul>
 *
 * <p><b>Empty-allowlist semantics.</b> An {@link #isEmpty()} allowlist
 * imposes no restriction — the coarser {@link HostAllowlist} + interface
 * / method checks still apply. Only NON-EMPTY allowlists cause a call to
 * be denied, so a policy that grants
 * {@code cap:allowInterface cap:HttpCallbacks} without any
 * {@code cap:allowHttpPath} triples still passes the path check.
 * {@link #ALLOW_NONE} is the empty-singleton for grants that name no
 * paths.
 */
public record HttpPathAllowlist(
        List<String> patterns
) {

    /** Empty-singleton — imposes no path restriction (see class doc). */
    public static final HttpPathAllowlist ALLOW_NONE = new HttpPathAllowlist(List.of());

    public HttpPathAllowlist {
        Objects.requireNonNull(patterns, "patterns");
        // Defensive copy + lowercase normalize so pattern-side case can't
        // sneak past the matcher. Same normalization the input side does
        // in matches(...).
        final List<String> copy = new ArrayList<>(patterns.size());
        for (final String p : patterns) {
            Objects.requireNonNull(p, "pattern in patterns list");
            copy.add(p.toLowerCase(Locale.ROOT));
        }
        patterns = Collections.unmodifiableList(copy);
    }

    /**
     * True iff {@code hostAndPath} starts with any configured pattern.
     * Null and empty inputs never match — a caller with no URL to check
     * has no authorization to reach anywhere.
     *
     * <p>The caller is expected to strip the URL scheme + optional
     * userinfo/port and pass {@code host + path} (e.g.
     * {@code "api.acme.com/public/orders"}), so patterns can be written
     * as {@code "api.acme.com/public/"} without needing to think about
     * {@code https://} vs {@code http://}.
     */
    public boolean matches(final String hostAndPath) {
        if (hostAndPath == null || hostAndPath.isEmpty()) return false;
        final String input = hostAndPath.toLowerCase(Locale.ROOT);
        for (final String pattern : patterns) {
            if (input.startsWith(pattern)) return true;
        }
        return false;
    }

    /**
     * True when no patterns are configured. Phase 5 enforcement treats an
     * empty allowlist as "no restriction beyond the coarser interface/
     * method + host checks".
     */
    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    /**
     * Number of configured patterns — for audit-row summarization and
     * diagnostic printing. O(1).
     */
    public int size() {
        return patterns.size();
    }
}
