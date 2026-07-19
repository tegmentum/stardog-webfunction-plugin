package ai.tegmentum.stardog.kibble.webfunctions;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Carrier for the projected result of the capability-policy SELECT the
 * {@link CapabilityPolicyStore} runs for a given extension URL.
 *
 * <p>Immutable snapshot of the three axes the store returns:
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
        Set<String> allowedHosts
) {

    /**
     * Empty snapshot — the store returned no rows for the extension. The
     * resolver treats this as "unknown extension" and consults
     * {@code webfunctions.capability.unknown-extension-policy}.
     */
    public static final PolicyTriples EMPTY = new PolicyTriples(
            Set.of(), Set.of(), Set.of());

    public PolicyTriples {
        Objects.requireNonNull(allowedInterfaces, "allowedInterfaces");
        Objects.requireNonNull(allowedMethods, "allowedMethods");
        Objects.requireNonNull(allowedHosts, "allowedHosts");
        allowedInterfaces = Collections.unmodifiableSet(new LinkedHashSet<>(allowedInterfaces));
        allowedMethods    = Collections.unmodifiableSet(new LinkedHashSet<>(allowedMethods));
        allowedHosts      = Collections.unmodifiableSet(new LinkedHashSet<>(allowedHosts));
    }

    /**
     * True when the store returned no rows at all — the extension is
     * unknown to the policy store. The resolver routes through the
     * unknown-extension policy on this branch.
     */
    public boolean isEmpty() {
        return allowedInterfaces.isEmpty()
                && allowedMethods.isEmpty()
                && allowedHosts.isEmpty();
    }
}
