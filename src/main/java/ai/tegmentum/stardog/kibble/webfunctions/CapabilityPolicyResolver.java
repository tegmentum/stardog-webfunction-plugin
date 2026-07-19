package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.webassembly4j.api.Component;
import org.apache.shiro.subject.Subject;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves an extension URL + declared component imports + (optional)
 * invoker {@link Subject} into an effective {@link CapabilityGrant} by
 * consulting a {@link CapabilityPolicyStore}.
 *
 * <p>Refactored from the Phase 1 sidecar-manifest shape (TOML manifest
 * loader) — Stardog is an RDF store, so policy lives in a dedicated
 * {@code system-webfunctions-capability} database, keyed by extension URL,
 * per the capability-policy refactor brief.
 *
 * <p>Algorithm:
 *
 * <ol>
 *   <li>Read the component's declared imports via
 *       {@link Component#importedInterfaces()}.</li>
 *   <li>Query the {@link CapabilityPolicyStore} for the extension URL
 *       → {@link PolicyTriples}. Empty result → unknown extension →
 *       consult {@link UnknownExtensionPolicy}.</li>
 *   <li>Grant = {@code declared ∩ policy.allowedInterfaces}. Method
 *       policies are built from {@code policy.allowedMethods}
 *       ({@code "interface/method"} tuples). HTTP host allowlist is built
 *       from {@code policy.allowedHosts}.</li>
 *   <li>Return the {@link CapabilityGrant} — immutable for the invocation
 *       lifetime.</li>
 * </ol>
 *
 * <p>Anonymous invocation ({@code Subject == null} or unauthenticated)
 * consults {@link #anonymousPolicy()}:
 * <ul>
 *   <li>{@link AnonymousPolicy#DENY} — resolution throws
 *       {@link WfCapabilityError.LoadTimeDenied} with a synthetic
 *       {@code "<any>"} interface and {@code "anonymous-policy=deny"}
 *       source tag.</li>
 *   <li>{@link AnonymousPolicy#PERMIT} — treat as if a subject were bound;
 *       the store-derived intersection still applies.</li>
 *   <li>{@link AnonymousPolicy#INHERIT} — pre-capability behavior; the
 *       intersection still applies but no principal is stamped.</li>
 * </ul>
 */
public final class CapabilityPolicyResolver {

    /** Enum wiring options for the anonymous-subject case. */
    public enum AnonymousPolicy { DENY, PERMIT, INHERIT }

    /** Prod default per implementation memo §12. */
    static final AnonymousPolicy DEFAULT_ANONYMOUS_POLICY = AnonymousPolicy.DENY;

    /**
     * Behavior when {@link CapabilityPolicyStore#resolveFor(URL)} returns
     * empty (or an {@link PolicyTriples#EMPTY} result) — the extension is
     * unknown to the policy store.
     */
    public enum UnknownExtensionPolicy { DENY, PERMIT, INHERIT }

    /** Prod default for the unknown-extension case per the refactor brief. */
    public static final UnknownExtensionPolicy DEFAULT_UNKNOWN_EXTENSION_POLICY =
            UnknownExtensionPolicy.DENY;

    private static final AtomicReference<AnonymousPolicy> ANONYMOUS_POLICY =
            new AtomicReference<>(DEFAULT_ANONYMOUS_POLICY);

    private static final AtomicReference<UnknownExtensionPolicy> UNKNOWN_EXTENSION_POLICY =
            new AtomicReference<>(DEFAULT_UNKNOWN_EXTENSION_POLICY);

    private static final AtomicReference<CapabilityPolicyStore> POLICY_STORE =
            new AtomicReference<>();

    private CapabilityPolicyResolver() {}

    /**
     * Install the anonymous-subject behavior. Wired at plugin startup
     * from {@code webfunctions.capability.anonymous-policy}. Null resets
     * to {@link #DEFAULT_ANONYMOUS_POLICY}.
     */
    public static void setAnonymousPolicy(final AnonymousPolicy policy) {
        ANONYMOUS_POLICY.set(policy == null ? DEFAULT_ANONYMOUS_POLICY : policy);
    }

    /** Current anonymous-subject policy — for tests and audit surfaces. */
    public static AnonymousPolicy anonymousPolicy() {
        return ANONYMOUS_POLICY.get();
    }

    /**
     * Install the unknown-extension behavior. Wired at plugin startup
     * from {@code webfunctions.capability.unknown-extension-policy}. Null
     * resets to {@link #DEFAULT_UNKNOWN_EXTENSION_POLICY}.
     */
    public static void setUnknownExtensionPolicy(final UnknownExtensionPolicy policy) {
        UNKNOWN_EXTENSION_POLICY.set(
                policy == null ? DEFAULT_UNKNOWN_EXTENSION_POLICY : policy);
    }

    /** Current unknown-extension policy — for tests and audit surfaces. */
    public static UnknownExtensionPolicy unknownExtensionPolicy() {
        return UNKNOWN_EXTENSION_POLICY.get();
    }

    /**
     * Install the capability policy store. Wired at plugin startup by
     * {@code WebFunctionServiceModule.CapabilityPolicyStarter} after the
     * Kernel-backed store has bootstrapped its management database. Null
     * clears the installation for test isolation.
     */
    public static void setPolicyStore(final CapabilityPolicyStore store) {
        POLICY_STORE.set(store);
    }

    /** The currently-installed policy store, or empty when not wired. */
    public static Optional<CapabilityPolicyStore> policyStore() {
        return Optional.ofNullable(POLICY_STORE.get());
    }

    /**
     * Main entry point. Resolves the grant per the algorithm on the
     * class doc. Throws {@link WfCapabilityError.LoadTimeDenied} on
     * required-interface denial or {@link WfCapabilityError.UnknownExtension}
     * when the store is up but has no policy and the unknown-extension
     * policy is DENY.
     *
     * @param extensionUrl wasm URL — for the {@link CapabilityGrant} and
     *                     the store's SELECT subject.
     * @param component    the loaded wasm component; the resolver reads
     *                     {@link Component#importedInterfaces()} on it.
     *                     May be null in test paths that construct a
     *                     policy-only grant.
     * @param subject      Shiro subject of the invoker, or {@code null}
     *                     for the anonymous / test path.
     */
    public static CapabilityGrant resolve(final URL extensionUrl,
                                          final Component component,
                                          final Subject subject) {
        final String extensionUri = extensionUrl == null ? "" : extensionUrl.toString();

        // Anonymous handling.
        if (subject == null || subject.getPrincipal() == null) {
            final AnonymousPolicy anon = anonymousPolicy();
            if (anon == AnonymousPolicy.DENY) {
                throw new WfCapabilityError.LoadTimeDenied(
                        extensionUri,
                        "<any>",
                        "shiro",
                        "",
                        "anonymous-policy=deny");
            }
            return resolveInternal(extensionUrl, extensionUri, component, "");
        }

        return resolveInternal(extensionUrl, extensionUri, component,
                stringPrincipal(subject));
    }

    private static CapabilityGrant resolveInternal(final URL extensionUrl,
                                                   final String extensionUri,
                                                   final Component component,
                                                   final String invokerPrincipal) {
        final PolicyTriples triples = fetchPolicyTriples(extensionUrl, extensionUri,
                invokerPrincipal);

        final Set<String> declared = declaredInterfaces(component);

        // Grant = declared ∩ policy.allowedInterfaces (intersection).
        // When the component reference is null (test path), skip the
        // declared-imports gate and trust the store.
        final Set<String> granted = new LinkedHashSet<>();
        for (final String iface : triples.allowedInterfaces()) {
            if (component == null || declared.isEmpty() || declared.contains(iface)) {
                granted.add(iface);
            }
        }

        final Map<String, MethodPolicy> methodPolicies =
                methodPoliciesFromTriples(triples.allowedMethods(), granted);

        final HostAllowlist httpAllowlist = triples.allowedHosts().isEmpty()
                ? HostAllowlist.ALLOW_NONE
                : new HostAllowlist(new ArrayList<>(triples.allowedHosts()));

        return new CapabilityGrant(
                extensionUri,
                granted,
                methodPolicies,
                httpAllowlist,
                invokerPrincipal,
                CapabilityModel.AMBIENT);
    }

    /**
     * Query the store, or route through the unknown-extension policy on
     * empty. Throws {@link WfCapabilityError.PolicyStoreUnavailable} if
     * the store is not installed, or {@link WfCapabilityError.UnknownExtension}
     * if the store returned empty and the unknown policy is DENY.
     */
    private static PolicyTriples fetchPolicyTriples(final URL extensionUrl,
                                                    final String extensionUri,
                                                    final String invokerPrincipal) {
        final Optional<CapabilityPolicyStore> storeRef = policyStore();
        if (storeRef.isEmpty()) {
            throw new WfCapabilityError.PolicyStoreUnavailable(
                    extensionUri, "policy store not installed");
        }
        final Optional<PolicyTriples> triplesOpt = storeRef.get().resolveFor(extensionUrl);
        if (triplesOpt.isEmpty()) {
            throw new WfCapabilityError.PolicyStoreUnavailable(
                    extensionUri, "policy store not ready");
        }
        final PolicyTriples triples = triplesOpt.get();
        if (triples.isEmpty()) {
            switch (unknownExtensionPolicy()) {
                case DENY:
                    throw new WfCapabilityError.UnknownExtension(
                            extensionUri, invokerPrincipal,
                            "unknown-extension-policy=deny");
                case PERMIT:
                case INHERIT:
                    // Pre-capability behavior: no policy means no
                    // constraints. Build a triples snapshot that allows
                    // every known-callback interface so the intersection
                    // with the component's declared imports lets
                    // everything through.
                    return new PolicyTriples(
                            new LinkedHashSet<>(CapabilityVocabulary.knownInterfaceWireNames()),
                            Set.of(),
                            Set.of());
            }
        }
        return triples;
    }

    /**
     * Build method policies from {@code "interface/method"} tuples in the
     * policy triples. Only interfaces in the {@code granted} set earn an
     * entry — a method allow for an interface the extension isn't allowed
     * to import contributes nothing.
     */
    private static Map<String, MethodPolicy> methodPoliciesFromTriples(
            final Set<String> allowedMethods, final Set<String> granted) {
        // Group methods per interface.
        final Map<String, Set<String>> byInterface = new LinkedHashMap<>();
        for (final String tuple : allowedMethods) {
            final int slash = tuple.indexOf('/');
            if (slash <= 0 || slash == tuple.length() - 1) continue;
            final String iface = tuple.substring(0, slash);
            final String method = tuple.substring(slash + 1);
            if (!granted.contains(iface)) continue;
            byInterface.computeIfAbsent(iface, k -> new LinkedHashSet<>()).add(method);
        }
        final Map<String, MethodPolicy> out = new LinkedHashMap<>();
        for (final Map.Entry<String, Set<String>> e : byInterface.entrySet()) {
            out.put(e.getKey(),
                    MethodPolicy.allowOnly(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static Set<String> declaredInterfaces(final Component component) {
        if (component == null) return Set.of();
        try {
            final List<String> paths = component.importedInterfaces();
            if (paths == null) return Set.of();
            // The substrate returns fully-qualified paths (e.g.
            // "tegmentum:webfunction/graph-callbacks@0.1.0"). Strip the
            // package + version wrapping so the intersection compares
            // like-for-like against the store's wire-format names.
            final Set<String> bare = new LinkedHashSet<>();
            for (final String path : paths) {
                bare.add(bareInterfaceName(path));
            }
            return bare;
        } catch (RuntimeException ignore) {
            // Provider without importedInterfaces support (non-wasmtime)
            // falls through to "declared unknown" — the resolver trusts
            // the store at that point.
            return Set.of();
        }
    }

    /**
     * Strip the WIT package + version wrapping from a fully-qualified
     * interface path. {@code "tegmentum:webfunction/graph-callbacks@0.1.0"}
     * → {@code "graph-callbacks"}. Handles the four shapes the substrate
     * emits: bare, {@code pkg/name}, {@code pkg/name@version},
     * {@code name@version}.
     */
    static String bareInterfaceName(final String path) {
        if (path == null || path.isEmpty()) return "";
        int start = 0;
        final int slash = path.lastIndexOf('/');
        if (slash >= 0) start = slash + 1;
        final int at = path.indexOf('@', start);
        final int end = at >= 0 ? at : path.length();
        return path.substring(start, end);
    }

    private static String stringPrincipal(final Subject subject) {
        final Object p = subject.getPrincipal();
        return p == null ? "" : p.toString();
    }
}
