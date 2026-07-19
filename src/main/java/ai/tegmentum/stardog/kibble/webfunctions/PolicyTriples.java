package ai.tegmentum.stardog.kibble.webfunctions;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Carrier for the projected result of the capability-policy SELECT the
 * {@link CapabilityPolicyStore} runs for a given extension URL.
 *
 * <p>Immutable snapshot of the five axes the store returns:
 * <ul>
 *   <li>{@link #allowedInterfaces} — wire-format interface names (e.g.
 *       {@code "graph-callbacks"}) the extension is trusted to import.</li>
 *   <li>{@link #allowedMethods} — {@code "interface/method"} tuples the
 *       policy admits (empty means "every method on the interface" once
 *       the interface itself is allowed, matching
 *       {@link MethodPolicy}'s allowlist-empty-means-all semantics).</li>
 *   <li>{@link #allowedHosts} — hostname patterns for HTTP callbacks
 *       ({@link HostAllowlist} grammar: exact and leading-{@code *.}
 *       glob).</li>
 *   <li>{@link #allowedHttpPaths} — Phase 5 host+path prefix patterns
 *       ({@link HttpPathAllowlist} grammar).</li>
 *   <li>{@link #allowedWasmCallees} — Phase 5 exact IRI patterns for
 *       {@code wasm-callbacks/*} callee URLs
 *       ({@link WasmCalleeAllowlist} grammar).</li>
 * </ul>
 *
 * <p>{@link #isEmpty()} discriminates the "unknown extension" case (no
 * policy triples at all) from the "known but tightly-scoped extension"
 * case (some rows, but only for a small surface). The resolver uses this
 * to route through {@code webfunctions.capability.unknown-extension-policy}
 * for the empty case only.
 */
public record PolicyTriples(
        Set<String> allowedInterfaces,
        Set<String> allowedMethods,
        Set<String> allowedHosts,
        Set<String> allowedHttpPaths,
        Set<String> allowedWasmCallees
) {

    /**
     * Empty snapshot — the store returned no rows for the extension. The
     * resolver treats this as "unknown extension" and consults
     * {@code webfunctions.capability.unknown-extension-policy}.
     */
    public static final PolicyTriples EMPTY = new PolicyTriples(
            Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

    public PolicyTriples {
        Objects.requireNonNull(allowedInterfaces, "allowedInterfaces");
        Objects.requireNonNull(allowedMethods, "allowedMethods");
        Objects.requireNonNull(allowedHosts, "allowedHosts");
        Objects.requireNonNull(allowedHttpPaths, "allowedHttpPaths");
        Objects.requireNonNull(allowedWasmCallees, "allowedWasmCallees");
        allowedInterfaces  = Collections.unmodifiableSet(new LinkedHashSet<>(allowedInterfaces));
        allowedMethods     = Collections.unmodifiableSet(new LinkedHashSet<>(allowedMethods));
        allowedHosts       = Collections.unmodifiableSet(new LinkedHashSet<>(allowedHosts));
        allowedHttpPaths   = Collections.unmodifiableSet(new LinkedHashSet<>(allowedHttpPaths));
        allowedWasmCallees = Collections.unmodifiableSet(new LinkedHashSet<>(allowedWasmCallees));
    }

    /**
     * Backward-compat convenience constructor — matches the pre-Phase-5
     * three-axis signature so existing test fixtures and any caller not
     * yet aware of the fine-grained allowlists continue to compile.
     * Populates the two Phase 5 axes with empty sets (unrestricted).
     */
    public PolicyTriples(final Set<String> allowedInterfaces,
                         final Set<String> allowedMethods,
                         final Set<String> allowedHosts) {
        this(allowedInterfaces, allowedMethods, allowedHosts, Set.of(), Set.of());
    }

    /**
     * True when the store returned no rows at all — the extension is
     * unknown to the policy store. The resolver routes through the
     * unknown-extension policy on this branch.
     */
    public boolean isEmpty() {
        return allowedInterfaces.isEmpty()
                && allowedMethods.isEmpty()
                && allowedHosts.isEmpty()
                && allowedHttpPaths.isEmpty()
                && allowedWasmCallees.isEmpty();
    }
}
