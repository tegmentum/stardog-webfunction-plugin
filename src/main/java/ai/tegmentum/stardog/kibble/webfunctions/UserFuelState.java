package ai.tegmentum.stardog.kibble.webfunctions;

import java.time.Instant;

/**
 * Per-user commercial fuel-quota state for the Phase 2 metering layer.
 *
 * <p>Data-only carrier; behavior lives in {@link FuelStateStore} implementations
 * and in the pre/post-invocation hook sites in {@link Call} and
 * {@link WebFunctionServiceOperator}. Records are the durable persistence
 * schema — any change to this shape is a persistence-layer migration
 * (see {@code fuel-implementation.md} §3).
 *
 * <p>Field semantics per the memo:
 * <ul>
 *   <li>{@code userId} — Shiro-authenticated principal from the outer query's
 *       subject. Empty string when the invocation ran outside an authenticated
 *       context (skipped-quota path).</li>
 *   <li>{@code orgId} — Phase-3 field, empty string in Phase 2 so records
 *       persist forward without a schema migration. Persistence backends
 *       serialize a non-null discriminator more predictably than null.</li>
 *   <li>{@code monthlyBudget} — commercial cap in fuel units;
 *       {@code 0} is the "unlimited" sentinel (defensive-only tier — the
 *       per-invocation trap still fires but no monthly quota is enforced).</li>
 *   <li>{@code monthlyUsed} — running counter, incremented post-invocation
 *       by the observed {@code fuelConsumed}.</li>
 *   <li>{@code billingAnniversary} — subject-scoped monthly-reset instant;
 *       set on the first fuel-consuming invocation per §11 open question
 *       "Anniversary source" resolution (b).</li>
 * </ul>
 */
public record UserFuelState(
        String userId,
        String orgId,
        long monthlyBudget,
        long monthlyUsed,
        Instant billingAnniversary) {

    /**
     * Fresh state for a first-invocation user with the given budget. Uses
     * {@link Instant#now()} as the initial billing anniversary — the
     * "first fuel invocation" anniversary source per {@code
     * fuel-implementation.md} §11.
     */
    public static UserFuelState fresh(final String userId, final long monthlyBudget) {
        return new UserFuelState(
                userId == null ? "" : userId,
                "",
                monthlyBudget,
                0L,
                Instant.now());
    }

    /** Return a copy of this state with {@code monthlyUsed} advanced by delta. */
    public UserFuelState addUsed(final long delta) {
        return new UserFuelState(userId, orgId, monthlyBudget, monthlyUsed + delta, billingAnniversary);
    }

    /**
     * Return a copy of this state with usage reset to zero and anniversary
     * advanced to {@code newAnniversary}. Called by the pre-invocation
     * anniversary-reset check when {@code now > billingAnniversary + 30 days}.
     */
    public UserFuelState reset(final Instant newAnniversary) {
        return new UserFuelState(userId, orgId, monthlyBudget, 0L, newAnniversary);
    }
}
