package ai.tegmentum.stardog.kibble.webfunctions;

/**
 * Effective execution model an extension runs under when its host callbacks
 * dispatch back into Stardog services.
 *
 * <p>Defined in the capability-policy Phase 1 data-model landing per
 * {@code capability-implementation.md} §2 so downstream schema (audit rows,
 * grant records, manifest parsing) can carry the enum without a follow-up
 * shape migration. The <em>enforcement</em> of the two models — wrapping
 * every host-callback dispatch in {@code Subject.execute(...)} when
 * {@link #INVOKER_SUBJECT} — is Phase 4 work per the same memo §14 and
 * strategy memo §8. Defining the enum in Phase 1 gives the manifest loader
 * and the grant resolver a stable target.
 *
 * <p>Absent-manifest extensions default to {@link #AMBIENT} for Phase 1
 * back-compat (existing extensions may have been authored against the
 * plugin's ambient credential and would break if flipped to
 * {@link #INVOKER_SUBJECT} without warning).
 */
public enum CapabilityModel {

    /**
     * Every host-callback dispatch runs its underlying Stardog operation
     * (SPARQL SELECT, UPDATE, HTTP GET/POST, ...) under the invoking
     * user's Shiro subject. Stardog's native database ACL, named-graph
     * ACL, and role permissions apply automatically. Preferred for
     * third-party extensions where the invoker's authority — not the
     * plugin's ambient credential — should gate what the guest reaches.
     */
    INVOKER_SUBJECT,

    /**
     * Every host-callback dispatch runs under the plugin's ambient
     * credential. Preserves pre-capability substrate behavior for
     * compatibility with existing extensions authored before the
     * capability landing. Absent-manifest extensions default here.
     */
    AMBIENT
}
