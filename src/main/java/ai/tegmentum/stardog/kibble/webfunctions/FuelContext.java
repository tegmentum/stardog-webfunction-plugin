package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.security.StardogAuthorizationException;
import com.complexible.stardog.security.ShiroUtils;

/**
 * Carrier for per-invocation billing identity — Shiro-authenticated user,
 * org attribution (Phase-3 field), and the extension URI being invoked.
 *
 * <p>Threaded through the invocation from {@link Call} and
 * {@link WebFunctionServiceOperator} into the pre/post-invocation fuel
 * hooks. Kept separate from {@link CallbackContext} because callback
 * context governs the wasm-guest-frame lifecycle (depth, tolls, etc.);
 * this record governs the billing-subject lifecycle (whose quota to
 * check and increment). Same site populates both.
 *
 * <p>{@link #extract} centralizes the Shiro lookup so both call sites
 * (filter-function BIND and SERVICE) share one code path. Returns
 * {@link #ANONYMOUS} when no authenticated subject is available (unit
 * tests, embedded direct-instantiation) — the pre-invocation hook
 * skips the quota check in that case.
 */
public final class FuelContext {

    /**
     * Sentinel for a missing/anonymous subject. userId="" tells the
     * pre-invocation hook to short-circuit the quota check per §7 open
     * question "FuelPolicy.resolveOrg implementation".
     */
    public static final FuelContext ANONYMOUS = new FuelContext("", "", "");

    private final String userId;
    private final String orgId;
    private final String extensionUri;

    public FuelContext(final String userId, final String orgId, final String extensionUri) {
        this.userId = userId == null ? "" : userId;
        this.orgId = orgId == null ? "" : orgId;
        this.extensionUri = extensionUri == null ? "" : extensionUri;
    }

    public String userId()       { return userId; }
    public String orgId()        { return orgId; }
    public String extensionUri() { return extensionUri; }

    /** True when there's no authenticated subject to bill. */
    public boolean isAnonymous() { return userId.isEmpty(); }

    /**
     * Extract the current Shiro subject's principal as a FuelContext,
     * stamping the extension URI for attribution. Returns
     * {@link #ANONYMOUS}-shaped context (userId="") when no subject is
     * authenticated — the invocation hook skips quota work in that case.
     *
     * <p>Phase-3 will populate {@code orgId} from a configured Shiro
     * attribute (see §7 open question "FuelPolicy.resolveOrg
     * implementation"). Phase 2 leaves it "" so persistence records
     * carry a stable non-null field.
     */
    public static FuelContext extract(final String extensionUri) {
        try {
            final String user = ShiroUtils.getAuthenticatedUsername();
            if (user == null || user.isEmpty()) {
                return new FuelContext("", "", extensionUri);
            }
            return new FuelContext(user, "", extensionUri);
        } catch (StardogAuthorizationException noSubject) {
            return new FuelContext("", "", extensionUri);
        } catch (RuntimeException other) {
            // Any other Shiro/Stardog error resolving the subject falls
            // back to anonymous so a broken auth path doesn't take down
            // the invocation.
            return new FuelContext("", "", extensionUri);
        }
    }
}
