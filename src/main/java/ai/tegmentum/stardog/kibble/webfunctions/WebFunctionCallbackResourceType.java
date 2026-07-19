package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.security.SecurityResourceType;

/**
 * Second Stardog {@link SecurityResourceType} the plugin registers —
 * gates per-interface, per-method capability invocation on the invoking
 * user's Shiro subject.
 *
 * <p>Distinct from {@link WebFunctionResourceType} (id
 * {@code "web-function"}), which gates {@code EXECUTE} on the wasm URL
 * itself. This resource type gates the finer-grained capability
 * surface — {@code web-function-callback:invoke:<interface>/<method>}
 * per {@code capability-implementation.md} §8. Two resource types,
 * two axes of permission: "can this user load this extension" vs.
 * "can this extension, under this user's authority, reach this
 * callback."
 *
 * <p>Singleton by convention (mirrors {@link WebFunctionResourceType#INSTANCE})
 * so Phase 1b's Guice registration can pass the constant instead of
 * instantiating.
 *
 * <p>{@code isDatabaseType() = false} — callback grants are process-wide,
 * not per-database. An admin who grants a Shiro role
 * {@code "web-function-callback:invoke:http-callbacks/*"} authorizes
 * the role's holders across every database; database ACLs apply
 * independently through Stardog's native machinery when the wrapped
 * operation actually runs (Phase 4 invoker-subject work).
 */
public final class WebFunctionCallbackResourceType implements SecurityResourceType {

    public static final WebFunctionCallbackResourceType INSTANCE = new WebFunctionCallbackResourceType();

    /**
     * Stardog resource-type id string; forms the prefix of every
     * {@code WebFunctionCapability.*} permission constant per
     * implementation memo §8.
     */
    public static final String ID = "web-function-callback";

    private WebFunctionCallbackResourceType() {}

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isDatabaseType() {
        return false;
    }
}
