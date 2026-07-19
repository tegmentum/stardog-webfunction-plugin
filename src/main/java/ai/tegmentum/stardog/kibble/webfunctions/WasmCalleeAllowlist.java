package ai.tegmentum.stardog.kibble.webfunctions;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Fine-grained callee-URL allowlist for {@code wasm-callbacks/*} dispatch
 * — Phase 5 companion to {@link HostAllowlist} +
 * {@link HttpPathAllowlist}. Shape from
 * {@code capability-implementation.md} §14 (Phase 5 rollout note).
 *
 * <p>Each pattern is a full callee-URL string, compared for exact
 * equality (case-sensitive on the path, host portion normalized by
 * {@link URL#toString()} to whatever the platform hands back — the
 * comparison is on the raw string, so admins should register the
 * canonical form of the callee URL).
 *
 * <p>URL-scheme-agnostic: extensions can be loaded from IPFS, HTTP,
 * file, or any URL scheme. {@code cap:allowWasmCallee} values are IRIs
 * (any URL scheme); this allowlist does not assume IPFS.
 *
 * <p><b>Empty-allowlist semantics.</b> An {@link #isEmpty()} allowlist
 * imposes no restriction — the coarser interface / method check still
 * applies. Only NON-EMPTY allowlists cause a call to be denied, so a
 * policy that grants {@code cap:allowInterface cap:WasmCallbacks}
 * without any {@code cap:allowWasmCallee} triples still passes the
 * callee check. {@link #ALLOW_NONE} is the empty-singleton for grants
 * that name no callees.
 */
public record WasmCalleeAllowlist(
        Set<String> patterns
) {

    /** Empty-singleton — imposes no callee restriction (see class doc). */
    public static final WasmCalleeAllowlist ALLOW_NONE = new WasmCalleeAllowlist(Set.of());

    public WasmCalleeAllowlist {
        Objects.requireNonNull(patterns, "patterns");
        // Defensive copy — no case-folding, callee URLs are compared as-is.
        final Set<String> copy = new LinkedHashSet<>(patterns.size());
        for (final String p : patterns) {
            Objects.requireNonNull(p, "pattern in patterns set");
            copy.add(p);
        }
        patterns = Collections.unmodifiableSet(copy);
    }

    /** Convenience constructor from a list — LinkedHashSet preserves order. */
    public static WasmCalleeAllowlist of(final List<String> patterns) {
        return new WasmCalleeAllowlist(new LinkedHashSet<>(patterns));
    }

    /**
     * True iff {@code callee}'s string form equals any configured pattern.
     * Null input never matches.
     */
    public boolean matches(final URL callee) {
        if (callee == null) return false;
        return matches(callee.toString());
    }

    /**
     * True iff {@code callee} equals any configured pattern. Null and
     * empty inputs never match.
     */
    public boolean matches(final String callee) {
        if (callee == null || callee.isEmpty()) return false;
        return patterns.contains(callee);
    }

    /**
     * True when no patterns are configured. Phase 5 enforcement treats an
     * empty allowlist as "no restriction beyond the coarser interface/
     * method check".
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

    /**
     * Snapshot of configured patterns — deterministic iteration order
     * (insertion order) for stable audit output.
     */
    public List<String> patternsList() {
        return new ArrayList<>(patterns);
    }
}
