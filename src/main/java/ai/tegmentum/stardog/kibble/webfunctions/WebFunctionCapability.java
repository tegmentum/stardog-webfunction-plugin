package ai.tegmentum.stardog.kibble.webfunctions;

/**
 * Canonical permission strings for the capability-callback surface.
 *
 * <p>One constant per {@code (interface, method)} tuple in the Phase 1
 * covered interfaces ({@code graph-callbacks}, {@code http-callbacks},
 * {@code wasm-callbacks}). Naming follows Stardog's
 * {@code resource-type:action:resource-id} convention per
 * {@code capability-implementation.md} §8; the {@code resource-type}
 * prefix matches {@link WebFunctionCallbackResourceType#ID}.
 *
 * <p>The verb piece is {@code "execute"} — aligned with Stardog's
 * {@link com.complexible.stardog.security.ActionType#EXECUTE} enum so
 * grants provision through the idiomatic Java {@code Permission} API
 * ({@code new Permission(name, ActionType.EXECUTE,
 * WebFunctionCallbackResourceType.INSTANCE, [iface, method])}) rather
 * than falling back to the HTTP admin surface. Stardog already uses
 * {@code EXECUTE} against multiple resource types (DATABASE, QUERY,
 * {@link WebFunctionResourceType}) — the callback resource type just
 * adds one more axis.
 *
 * <p>Consumed by {@link CapabilityEnforcer#perCallback} in the form
 * {@code new WildcardPermission(WebFunctionCapability.GRAPH_QUERY)};
 * Stardog's native {@code AuthorizingSecurityManager} already
 * understands the wildcard-permission matcher, so admin grants like
 * <pre>
 *   stardog-admin role permission add wf-http-outbound \
 *       "web-function-callback:execute:http-callbacks/*"
 * </pre>
 * work through the same machinery.
 *
 * <p>Package-visible {@link #forExecute} helper lets the enforcer build
 * a permission string for interfaces / methods added post-MVP without a
 * fresh constant per pair — Phase 5+ will land more callback surfaces
 * ({@code sink-callbacks}, {@code fulltext-callbacks}), and their
 * permission strings compute uniformly through this helper.
 */
public final class WebFunctionCapability {

    /** Verb piece of the permission string — aligns with {@code ActionType.EXECUTE}. */
    public static final String ACTION_EXECUTE = "execute";

    public static final String GRAPH_QUERY  = "web-function-callback:execute:graph-callbacks/execute-query";
    public static final String GRAPH_UPDATE = "web-function-callback:execute:graph-callbacks/execute-update";
    public static final String HTTP_GET     = "web-function-callback:execute:http-callbacks/http-get";
    public static final String HTTP_POST    = "web-function-callback:execute:http-callbacks/http-post-json";
    public static final String WASM_INVOKE  = "web-function-callback:execute:wasm-callbacks/invoke-wasm";
    public static final String WASM_SERVICE = "web-function-callback:execute:wasm-callbacks/invoke-wasm-service";

    private WebFunctionCapability() {}

    /**
     * Build the canonical permission string for a
     * {@code (interfaceName, method)} tuple. Used by the enforcer to
     * synthesize a permission check without a per-tuple constant.
     *
     * <p>Returns strings that string-equal the {@code *} constants for the
     * Phase 1 covered tuples (verified in unit tests).
     */
    public static String forExecute(final String interfaceName, final String method) {
        return WebFunctionCallbackResourceType.ID
                + ":" + ACTION_EXECUTE + ":"
                + (interfaceName == null ? "" : interfaceName)
                + "/"
                + (method == null ? "" : method);
    }
}
