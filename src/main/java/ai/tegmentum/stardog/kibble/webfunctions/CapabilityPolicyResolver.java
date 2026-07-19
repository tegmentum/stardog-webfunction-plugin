package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.webassembly4j.api.Component;
import org.apache.shiro.subject.Subject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves an {@link ExtensionManifest} + declared component imports +
 * (optional) invoker {@link Subject} into an effective
 * {@link CapabilityGrant}. Shape from
 * {@code capability-implementation.md} §5.
 *
 * <p>Phase 1 algorithm (no per-extension policy overrides, no signature
 * verification, no per-argument allowlists):
 *
 * <ol>
 *   <li>Read the component's declared imports via
 *       {@link Component#importedInterfaces()} — webassembly4j 2.4.3
 *       exposes this per the substrate probe finding.</li>
 *   <li>Compute the requested set from the manifest as
 *       {@code required ∪ optional}. Every requested interface must
 *       appear in the component's declared imports; a manifest-vs-
 *       component mismatch surfaces as a grant that omits the
 *       nonexistent interface.</li>
 *   <li>Grant = {@code declared ∩ requested}. Phase 3 will intersect a
 *       third factor (per-extension policy override) at this step; Phase
 *       1 grants everything the manifest requests that the component
 *       actually declares.</li>
 *   <li>Verify {@code manifest.required ⊆ grant}. Any missing required
 *       interface throws {@link WfCapabilityError.LoadTimeDenied}
 *       naming the missing interface, the resolution stage that dropped
 *       it, the invoker principal, and a short policy-source tag admins
 *       can grep audit logs against.</li>
 *   <li>Optional interfaces that dropped out do not fail resolution;
 *       Phase 1b wires the linker to stub them with
 *       {@code not-permitted} trampolines.</li>
 *   <li>Return the {@link CapabilityGrant} — immutable for the
 *       invocation lifetime.</li>
 * </ol>
 *
 * <p>Anonymous invocation ({@code Subject == null} or unauthenticated)
 * consults {@link #anonymousPolicy()}:
 * <ul>
 *   <li>{@link AnonymousPolicy#DENY} — resolution throws
 *       {@link WfCapabilityError.LoadTimeDenied} against the first
 *       required interface (or a synthetic {@code "<any>"} when the
 *       manifest declares no required interfaces).</li>
 *   <li>{@link AnonymousPolicy#PERMIT} — skip the anonymous denial and
 *       treat the invocation as if a subject had granted every requested
 *       interface. Useful for CI without a configured user database.</li>
 *   <li>{@link AnonymousPolicy#INHERIT} — pre-capability behavior;
 *       resolution proceeds as if the plugin's ambient credential were
 *       the invoker (no additional filtering beyond declared ∩
 *       requested).</li>
 * </ul>
 *
 * <p>Phase 1b wires {@link #setAnonymousPolicy(AnonymousPolicy)} from
 * {@code webfunctions.capability.anonymous-policy} at
 * {@code WebFunctionServiceModule} startup. Until then, the default
 * is {@link AnonymousPolicy#DENY} per implementation memo §12 (prod
 * default).
 */
public final class CapabilityPolicyResolver {

    /** Enum wiring options for the anonymous-subject case. */
    public enum AnonymousPolicy { DENY, PERMIT, INHERIT }

    /** Prod default per implementation memo §12. */
    static final AnonymousPolicy DEFAULT_ANONYMOUS_POLICY = AnonymousPolicy.DENY;

    private static final AtomicReference<AnonymousPolicy> ANONYMOUS_POLICY =
            new AtomicReference<>(DEFAULT_ANONYMOUS_POLICY);

    private CapabilityPolicyResolver() {}

    /**
     * Install the anonymous-subject behavior. Phase 1b's
     * {@code WebFunctionServiceModule} calls this at startup from the
     * {@code webfunctions.capability.anonymous-policy} config value.
     * Null resets to the {@link #DEFAULT_ANONYMOUS_POLICY} prod default.
     */
    public static void setAnonymousPolicy(final AnonymousPolicy policy) {
        ANONYMOUS_POLICY.set(policy == null ? DEFAULT_ANONYMOUS_POLICY : policy);
    }

    /** Current anonymous-subject policy — for tests and audit surfaces. */
    public static AnonymousPolicy anonymousPolicy() {
        return ANONYMOUS_POLICY.get();
    }

    /**
     * Main entry point. Resolves the grant per the algorithm on the
     * class doc. Throws {@link WfCapabilityError.LoadTimeDenied} on
     * required-interface denial (or on {@link AnonymousPolicy#DENY}
     * anonymous case).
     *
     * @param extensionUri wasm URI — for the {@link CapabilityGrant} and
     *                     the error payload's {@code extension} field.
     * @param component    the loaded wasm component; the resolver reads
     *                     {@link Component#importedInterfaces()} on it.
     *                     May be null in test paths that construct a
     *                     manifest-only grant.
     * @param manifest     the parsed manifest (from
     *                     {@link ExtensionManifestLoader}). Never null;
     *                     absent-manifest callers pass
     *                     {@link ExtensionManifest#ABSENT}.
     * @param subject      Shiro subject of the invoker, or {@code null}
     *                     for the anonymous / test path.
     */
    public static CapabilityGrant resolve(final String extensionUri,
                                          final Component component,
                                          final ExtensionManifest manifest,
                                          final Subject subject) {
        if (manifest == null) {
            throw new WfCapabilityError.LoadTimeDenied(
                    extensionUri,
                    "<any>",
                    "policy",
                    principalOf(subject),
                    "manifest-required");
        }

        // Anonymous handling per class doc.
        if (subject == null || subject.getPrincipal() == null) {
            final AnonymousPolicy pol = anonymousPolicy();
            if (pol == AnonymousPolicy.DENY) {
                final String missing = manifest.requiredInterfaces().isEmpty()
                        ? "<any>"
                        : manifest.requiredInterfaces().iterator().next();
                throw new WfCapabilityError.LoadTimeDenied(
                        extensionUri,
                        missing,
                        "shiro",
                        "",
                        "anonymous-policy=deny");
            }
            // PERMIT / INHERIT — both bypass the Shiro-intersection step
            // (no subject to intersect against). The remaining resolution
            // is declared ∩ requested.
            return resolveWithoutSubject(extensionUri, component, manifest, "");
        }

        return resolveWithoutSubject(extensionUri, component, manifest,
                stringPrincipal(subject));
    }

    private static CapabilityGrant resolveWithoutSubject(final String extensionUri,
                                                         final Component component,
                                                         final ExtensionManifest manifest,
                                                         final String invokerPrincipal) {
        final Set<String> declared = declaredInterfaces(component);
        final Set<String> requested = new LinkedHashSet<>();
        requested.addAll(manifest.requiredInterfaces());
        requested.addAll(manifest.optionalInterfaces());

        final Set<String> granted = new LinkedHashSet<>();
        for (final String iface : requested) {
            // Phase 1: no per-extension policy override — declared ∩
            // requested is the full intersection. When the component
            // reference is null (test path), skip the declared-imports
            // gate and trust the manifest.
            if (component == null || declared.contains(iface) || declared.isEmpty()) {
                granted.add(iface);
            }
        }

        // Verify required subset.
        for (final String req : manifest.requiredInterfaces()) {
            if (!granted.contains(req)) {
                throw new WfCapabilityError.LoadTimeDenied(
                        extensionUri,
                        req,
                        "policy",
                        invokerPrincipal,
                        component == null || declared.isEmpty()
                                ? "manifest-declares-required-not-granted"
                                : "component-does-not-declare-import");
            }
        }

        // Copy across method-policy entries for granted interfaces. An
        // interface in the manifest's methodPolicies map but not in the
        // grant carries no policy (the interface itself is denied).
        final Map<String, MethodPolicy> methodPolicies = new LinkedHashMap<>();
        for (final Map.Entry<String, MethodPolicy> e : manifest.methodPolicies().entrySet()) {
            if (granted.contains(e.getKey())) {
                methodPolicies.put(e.getKey(), e.getValue());
            }
        }

        return new CapabilityGrant(
                extensionUri == null ? "" : extensionUri,
                granted,
                methodPolicies,
                manifest.httpAllowlist(),
                invokerPrincipal,
                manifest.model());
    }

    private static Set<String> declaredInterfaces(final Component component) {
        if (component == null) return Set.of();
        try {
            final List<String> paths = component.importedInterfaces();
            if (paths == null) return Set.of();
            // The substrate returns fully-qualified paths (e.g.
            // "tegmentum:webfunction/graph-callbacks@0.1.0"). The
            // manifest keys are the bare interface names; strip the
            // package + version wrapping so the intersection compares
            // like-for-like. Interface identity is stable across WIT
            // package versions per implementation memo §2.
            final Set<String> bare = new LinkedHashSet<>();
            for (final String path : paths) {
                bare.add(bareInterfaceName(path));
            }
            return bare;
        } catch (RuntimeException ignore) {
            // Provider without importedInterfaces support (non-wasmtime)
            // falls through to "declared unknown" — the resolver trusts
            // the manifest at that point per implementation memo §5.1
            // OPEN item resolution.
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

    private static String principalOf(final Subject subject) {
        if (subject == null) return "";
        return stringPrincipal(subject);
    }
}
