package ai.tegmentum.stardog.kibble.webfunctions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Parsed extension manifest — the declared capability contract shipped
 * alongside a wasm extension. Shape from
 * {@code capability-implementation.md} §2 / strategy memo §6.
 *
 * <p>Written by the extension author, reviewed by the plugin admin, and
 * loaded at extension instantiation by {@link ExtensionManifestLoader}
 * (§3 of the implementation memo). Fetched from IPFS/HTTP/file alongside
 * the {@code .wasm} artifact.
 *
 * <p>Fields carry the raw declarations. The resolver
 * ({@link CapabilityPolicyResolver}) intersects declared with policy and
 * invoker permissions to produce the effective {@link CapabilityGrant}.
 *
 * <p>Semantic notes:
 * <ul>
 *   <li>{@link #requiredInterfaces} — interfaces the extension cannot
 *       function without. Missing from grant → instantiation trap
 *       ({@link WfCapabilityError.LoadTimeDenied}).</li>
 *   <li>{@link #optionalInterfaces} — the extension can degrade if the
 *       host stubs them. Missing from grant → the linker installs a
 *       {@code not-permitted} trampoline (strategy memo §5).</li>
 *   <li>{@link #methodPolicies} — keyed by bare interface name (e.g.
 *       {@code "graph-callbacks"}). Interface-level allow with method
 *       exceptions per memo §2 note.</li>
 *   <li>{@link #httpAllowlist} — coarse callee-argument grant for the
 *       {@code http-callbacks} surface. Deny-all
 *       ({@link HostAllowlist#ALLOW_NONE}) when the manifest omits
 *       the section.</li>
 *   <li>{@link #model} — declared capability model. Phase 1 stashes
 *       the field; Phase 4 wires enforcement.</li>
 * </ul>
 *
 * <p>Composition, delegation, temporal scoping, conditional grants are
 * <em>not</em> in the MVP shape — strategy memo §6 explicitly names all
 * four as non-goals.
 */
public record ExtensionManifest(
        String name,
        String version,
        String signer,
        Set<String> requiredInterfaces,
        Set<String> optionalInterfaces,
        Map<String, MethodPolicy> methodPolicies,
        HostAllowlist httpAllowlist,
        CapabilityModel model
) {

    /**
     * Sentinel used when {@code webfunctions.capability.require-manifest = false}
     * and the sidecar is absent — the extension loads with an empty declared
     * surface (interface-level grants land through the substrate's natural
     * default-deny), signer is empty, model defaults to {@link CapabilityModel#AMBIENT}
     * for back-compat per implementation memo §5.
     */
    public static final ExtensionManifest ABSENT = new ExtensionManifest(
            "", "", "",
            Set.of(), Set.of(),
            Map.of(),
            HostAllowlist.ALLOW_NONE,
            CapabilityModel.AMBIENT);

    public ExtensionManifest {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(signer, "signer");
        Objects.requireNonNull(requiredInterfaces, "requiredInterfaces");
        Objects.requireNonNull(optionalInterfaces, "optionalInterfaces");
        Objects.requireNonNull(methodPolicies, "methodPolicies");
        Objects.requireNonNull(httpAllowlist, "httpAllowlist");
        Objects.requireNonNull(model, "model");
        // Defensive copy to unmodifiable snapshots — the manifest is a
        // durable schema handed off to the resolver + audit ring; a
        // caller mutating the input Set/Map after construction cannot
        // mutate the stored declaration.
        requiredInterfaces = Collections.unmodifiableSet(new LinkedHashSet<>(requiredInterfaces));
        optionalInterfaces = Collections.unmodifiableSet(new LinkedHashSet<>(optionalInterfaces));
        methodPolicies     = Collections.unmodifiableMap(new LinkedHashMap<>(methodPolicies));
    }
}
