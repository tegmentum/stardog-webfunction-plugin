package ai.tegmentum.stardog.kibble.webfunctions;

import java.util.Optional;

/**
 * Storage abstraction for the Phase 2 fuel-metering commercial layer.
 *
 * <p>Two implementations land in this wave:
 * <ul>
 *   <li>{@link InMemoryFuelStateStore} — a {@link java.util.concurrent.ConcurrentHashMap}-backed
 *       store used by unit tests and available as an MVP fallback when the
 *       Kernel-backed impl is not wired (deployment prints a startup warning
 *       under that regime — quota resets on restart).</li>
 *   <li>{@link KernelBackedFuelStateStore} — the production impl following
 *       the QueryLog persistence pattern per {@code fuel-implementation.md}
 *       §7a. Materializes a dedicated Stardog database on first construction,
 *       writes state as RDF, keeps a write-behind cache for hot-path
 *       enforcement between flushes.</li>
 * </ul>
 *
 * <p>Phase 3 will extend the interface with {@code loadOrg}/{@code saveOrg};
 * Phase 6 with {@code appendAttribution}. The interface is deliberately
 * narrow so both impls stay auditable and the Phase-2 hot path takes at
 * most one call per invocation (read-through cache on the Kernel-backed
 * impl).
 */
public interface FuelStateStore {

    /**
     * Return the persisted state for {@code userId}, or empty when no row has
     * been materialized yet. Callers are expected to materialize a fresh
     * {@link UserFuelState#fresh} record and pass it back through
     * {@link #saveUser} on first invocation for a user.
     */
    Optional<UserFuelState> loadUser(String userId);

    /**
     * Persist the given state. Impls MAY batch this write (write-behind
     * cache); on the read side, the same call site will see-through the
     * cache so back-to-back load/save on the hot path is coherent.
     */
    void saveUser(UserFuelState state);

    // Phase 3 additions land here:
    //   Optional<OrgFuelState> loadOrg(String orgId);
    //   void saveOrg(OrgFuelState state);
    //
    // Phase 6:
    //   void appendAttribution(AttributionRow row);
}
