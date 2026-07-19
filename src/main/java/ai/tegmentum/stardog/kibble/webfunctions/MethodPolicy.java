package ai.tegmentum.stardog.kibble.webfunctions;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Per-interface method allow/deny policy. Shape from
 * {@code capability-implementation.md} §2.
 *
 * <p>Semantics for {@link #allows(String)}:
 * <ul>
 *   <li>{@link #deniedMethods()} always wins — a method listed there is
 *       rejected even if it also appears in {@link #allowedMethods()}.
 *       Hard deny is the escape hatch admins reach for when they need
 *       to strip a single method off an otherwise-broad allow.</li>
 *   <li>Empty {@link #allowedMethods()} means "allow every method on
 *       this interface" per memo §2 note ("empty means all methods on
 *       the interface"). Absent method-level policy on an interface
 *       reduces to interface-level grant.</li>
 *   <li>Non-empty {@link #allowedMethods()} is a whitelist — only
 *       explicitly-listed methods are allowed.</li>
 * </ul>
 *
 * <p>Method identity is the bare WIT function name (e.g. {@code
 * "execute-query"}), not the fully-qualified WIT path — memo §2's
 * "interface identity is stable across WIT package versions" argument
 * applies to method names for the same reason.
 *
 * <p>All fields are non-null; the record's compact constructor
 * defensive-copies the sets and rejects nulls so a policy handed off
 * to the resolver cannot be mutated by the caller after construction.
 */
public record MethodPolicy(
        String interfaceName,
        Set<String> allowedMethods,
        Set<String> deniedMethods
) {

    public MethodPolicy {
        Objects.requireNonNull(interfaceName, "interfaceName");
        Objects.requireNonNull(allowedMethods, "allowedMethods");
        Objects.requireNonNull(deniedMethods, "deniedMethods");
        // Defensive copy to unmodifiable snapshots so a caller mutating
        // the input Set later cannot mutate the stored policy.
        allowedMethods = Collections.unmodifiableSet(new LinkedHashSet<>(allowedMethods));
        deniedMethods  = Collections.unmodifiableSet(new LinkedHashSet<>(deniedMethods));
    }

    /**
     * Convenience — policy that allows every method on the interface and
     * denies none. Matches the "empty allowlist means all" semantics.
     */
    public static MethodPolicy allowAll(final String interfaceName) {
        return new MethodPolicy(interfaceName, Set.of(), Set.of());
    }

    /**
     * Convenience — policy that allows only the named methods, denies none.
     */
    public static MethodPolicy allowOnly(final String interfaceName,
                                         final Set<String> methods) {
        return new MethodPolicy(interfaceName, methods, Set.of());
    }

    /**
     * Decide whether the named method is allowed under this policy per
     * the ordering documented on the class.
     */
    public boolean allows(final String method) {
        if (method == null) return false;
        if (deniedMethods.contains(method)) return false;
        if (allowedMethods.isEmpty()) return true;
        return allowedMethods.contains(method);
    }
}
