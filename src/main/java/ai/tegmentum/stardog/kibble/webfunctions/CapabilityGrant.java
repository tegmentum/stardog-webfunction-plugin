package ai.tegmentum.stardog.kibble.webfunctions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Effective per-invocation capability grant produced by
 * {@link CapabilityPolicyResolver}. Shape from
 * {@code capability-implementation.md} §2 (+ §14 Phase 5 fine-grained
 * per-argument allowlists).
 *
 * <p>Read-only for the invocation lifetime. Populated once at extension
 * instantiation by the resolver and stashed on the {@code CallbackContext}
 * (Phase 1b wiring); consulted on every host-callback dispatch by
 * {@link CapabilityEnforcer#perCallback}.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #grantedInterfaces} is the <em>intersection</em> of
 *       (extension declared) ∩ (policy allows) ∩ (invoker Shiro
 *       permissions) per implementation memo §5.2, not the extension's
 *       raw request.</li>
 *   <li>{@link #methodPolicies} is keyed by bare interface name so
 *       {@code grant.methodPolicies().get("graph-callbacks")} looks up
 *       cleanly.</li>
 *   <li>{@link #httpAllowlist} may be {@link HostAllowlist#ALLOW_NONE}
 *       when the extension didn't declare HTTP hosts — treated as
 *       "no host restriction" by the enforcer (empty-allowlist means
 *       unrestricted, matching {@link HttpPathAllowlist} and
 *       {@link WasmCalleeAllowlist} semantics).</li>
 *   <li>{@link #httpPathAllowlist} Phase 5 — path-prefix allowlist on
 *       {@code host+path} strings. Empty ⇒ no path restriction.</li>
 *   <li>{@link #wasmCalleeAllowlist} Phase 5 — exact-IRI allowlist on
 *       {@code wasm-callbacks/*} callee URLs. Empty ⇒ no callee
 *       restriction.</li>
 *   <li>{@link #invokerPrincipal} is the Shiro principal string, or
 *       {@code ""} for the anonymous case ({@link FuelContext#ANONYMOUS}
 *       shape).</li>
 *   <li>{@link #model} is the effective model after any admin override.
 *       Phase 4 wires enforcement per implementation memo §14.</li>
 * </ul>
 */
public record CapabilityGrant(
        String extensionUri,
        Set<String> grantedInterfaces,
        Map<String, MethodPolicy> methodPolicies,
        HostAllowlist httpAllowlist,
        HttpPathAllowlist httpPathAllowlist,
        WasmCalleeAllowlist wasmCalleeAllowlist,
        String invokerPrincipal,
        CapabilityModel model
) {

    public CapabilityGrant {
        Objects.requireNonNull(extensionUri, "extensionUri");
        Objects.requireNonNull(grantedInterfaces, "grantedInterfaces");
        Objects.requireNonNull(methodPolicies, "methodPolicies");
        Objects.requireNonNull(httpAllowlist, "httpAllowlist");
        Objects.requireNonNull(httpPathAllowlist, "httpPathAllowlist");
        Objects.requireNonNull(wasmCalleeAllowlist, "wasmCalleeAllowlist");
        Objects.requireNonNull(invokerPrincipal, "invokerPrincipal");
        Objects.requireNonNull(model, "model");
        grantedInterfaces = Collections.unmodifiableSet(new LinkedHashSet<>(grantedInterfaces));
        methodPolicies    = Collections.unmodifiableMap(new LinkedHashMap<>(methodPolicies));
    }

    /**
     * Backward-compat convenience constructor — matches the pre-Phase-5
     * six-arg signature (no fine-grained http-path / wasm-callee
     * allowlists). Populates the two new allowlists with their
     * {@code ALLOW_NONE} empty singletons — enforcer treats those as
     * "no restriction", so an existing caller sees unchanged behavior.
     */
    public CapabilityGrant(final String extensionUri,
                           final Set<String> grantedInterfaces,
                           final Map<String, MethodPolicy> methodPolicies,
                           final HostAllowlist httpAllowlist,
                           final String invokerPrincipal,
                           final CapabilityModel model) {
        this(extensionUri, grantedInterfaces, methodPolicies, httpAllowlist,
                HttpPathAllowlist.ALLOW_NONE, WasmCalleeAllowlist.ALLOW_NONE,
                invokerPrincipal, model);
    }

    /**
     * Whether the interface is present in the effective grant. Cheap;
     * host-callback dispatch calls this on every fire, and the check is
     * defensive with respect to the linker-level default-deny (§7 of
     * the implementation memo).
     */
    public boolean allowsInterface(final String interfaceName) {
        return interfaceName != null && grantedInterfaces.contains(interfaceName);
    }

    /**
     * Whether the {@code (interfaceName, method)} tuple is allowed under
     * the interface's method policy. Interfaces without a per-method
     * policy default to allowing every method (interface-level grant
     * subsumes method-level absence per implementation memo §2 note).
     */
    public boolean allowsMethod(final String interfaceName, final String method) {
        if (!allowsInterface(interfaceName)) return false;
        final MethodPolicy policy = methodPolicies.get(interfaceName);
        if (policy == null) return true;
        return policy.allows(method);
    }
}
