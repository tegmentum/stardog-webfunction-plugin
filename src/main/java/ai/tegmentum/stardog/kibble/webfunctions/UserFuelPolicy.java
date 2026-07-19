package ai.tegmentum.stardog.kibble.webfunctions;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Encapsulates the per-user commercial-quota pre/post-invocation flow
 * per {@code fuel-implementation.md} §4 steps 3-6, 11.
 *
 * <p>Owned by {@link WebFunctionServiceModule}-scope singleton lifetime;
 * a fresh reference is fetched at each call site via
 * {@link #activePolicy()}, which returns {@link Optional#empty()} when
 * fuel is disabled or the per-user monthly quota is unset (Phase-1
 * back-compat). The returned instance carries the shared
 * {@link FuelStateStore}, so a caller can:
 *
 * <pre>
 *     final UserFuelPolicy policy = UserFuelPolicy.activePolicy().orElse(null);
 *     if (policy != null) policy.preInvocation(fuelCtx);
 *     ... invoke wasm ...
 *     if (policy != null) policy.postInvocation(fuelCtx, fuelConsumed);
 * </pre>
 *
 * <p>Isolating the flow behind this class keeps the invocation hot-path
 * classes ({@link Call}, {@link WebFunctionServiceOperator}) narrow and
 * gives Phase-3 org rollup an obvious extension point.
 */
public final class UserFuelPolicy {

    /**
     * Monthly reset window — 30 days from the anniversary instant.
     * Simple day-based reset per §11 "Anniversary source" resolution (b);
     * {@code Duration} is calendar-agnostic which suits the "N days after
     * first invocation" semantics better than a calendar-month arithmetic.
     */
    static final Duration RESET_WINDOW = Duration.ofDays(30);

    private static volatile UserFuelPolicy INSTANCE;

    private final FuelStateStore store;
    private final long perUserMonthly;
    private final long perInvocationMax;

    UserFuelPolicy(final FuelStateStore store,
                   final long perUserMonthly,
                   final long perInvocationMax) {
        this.store = store;
        this.perUserMonthly = perUserMonthly;
        this.perInvocationMax = perInvocationMax;
    }

    /**
     * Install a policy. Called by the module wiring layer once the
     * {@link FuelStateStore} is chosen (KernelBacked in production,
     * InMemory in tests). Passing null clears the installation.
     */
    public static void install(final UserFuelPolicy policy) {
        INSTANCE = policy;
    }

    /** Package-visible accessor for tests. */
    static UserFuelPolicy currentForTesting() { return INSTANCE; }

    /**
     * Return the currently-installed policy, or empty when fuel metering
     * is disabled or the per-user quota is 0 (unlimited). Callers on the
     * hot path use this to skip all Phase-2 work in the common
     * unconfigured case.
     */
    public static Optional<UserFuelPolicy> activePolicy() {
        if (!WebFunctionConfig.fuelEnabled()) return Optional.empty();
        if (WebFunctionConfig.fuelPerUserMonthly() <= 0L) return Optional.empty();
        final UserFuelPolicy p = INSTANCE;
        return Optional.ofNullable(p);
    }

    /**
     * Pre-invocation quota check. Loads (or materializes) the caller's
     * {@link UserFuelState}, handles anniversary reset, and throws
     * {@link WfBudgetError.UserQuotaExhausted} when the monthly budget
     * is exhausted. No-op for anonymous {@link FuelContext} — the
     * hook site has no billing subject to check.
     *
     * @return the loaded/materialized state, so the caller can pass it
     *         to {@link #postInvocation} for accurate increment
     *         semantics without a second store round-trip.
     */
    public UserFuelState preInvocation(final FuelContext ctx) {
        if (ctx == null || ctx.isAnonymous()) return null;
        UserFuelState state = store.loadUser(ctx.userId())
                .orElseGet(() -> UserFuelState.fresh(ctx.userId(), perUserMonthly));

        // Anniversary reset — memo §4 step 4. If the store contains a
        // stale row from before the plugin was reconfigured with a
        // different quota, honor the current config on reset (so admins
        // raising the cap take effect at the next boundary without a
        // separate migration).
        final Instant now = Instant.now();
        if (state.billingAnniversary() != null
                && now.isAfter(state.billingAnniversary().plus(RESET_WINDOW))) {
            state = new UserFuelState(state.userId(), state.orgId(),
                    perUserMonthly, 0L, now);
            store.saveUser(state);
        }

        // Snap the budget forward if config has changed. Only take effect
        // when the current row is fresh (0 used) — mid-window budget
        // changes are a policy-migration concern outside Phase 2 scope.
        if (state.monthlyUsed() == 0L && state.monthlyBudget() != perUserMonthly) {
            state = new UserFuelState(state.userId(), state.orgId(),
                    perUserMonthly, 0L, state.billingAnniversary());
            store.saveUser(state);
        }

        if (state.monthlyBudget() > 0L && state.monthlyUsed() >= state.monthlyBudget()) {
            final Instant resetAt = state.billingAnniversary() == null
                    ? now.plus(RESET_WINDOW)
                    : state.billingAnniversary().plus(RESET_WINDOW);
            throw new WfBudgetError.UserQuotaExhausted(
                    ctx.userId(),
                    ctx.orgId(),
                    ctx.extensionUri(),
                    state.monthlyUsed(),
                    state.monthlyBudget(),
                    resetAt);
        }
        return state;
    }

    /**
     * Compute the per-invocation fuel budget: min of the configured
     * per-invocation cap and the user's remaining monthly budget. When
     * {@code state == null} (anonymous / no quota) returns the raw
     * per-invocation cap.
     */
    public long invocationBudget(final UserFuelState state) {
        final long perInv = perInvocationMax > 0L ? perInvocationMax : Long.MAX_VALUE;
        if (state == null) return perInv;
        if (state.monthlyBudget() <= 0L) return perInv;
        return Math.max(0L, Math.min(perInv, state.monthlyBudget() - state.monthlyUsed()));
    }

    /**
     * Post-invocation counter increment. Runs on both success and trap
     * paths per memo §4 step 12 fairness principle: charge the observed
     * fuel_consumed regardless of outcome. When the store's write-behind
     * cache is active (KernelBacked), the persistence round-trip happens
     * on the next flush window.
     */
    public void postInvocation(final FuelContext ctx, final long fuelConsumed) {
        if (ctx == null || ctx.isAnonymous() || fuelConsumed <= 0L) return;
        final UserFuelState existing = store.loadUser(ctx.userId())
                .orElseGet(() -> UserFuelState.fresh(ctx.userId(), perUserMonthly));
        store.saveUser(existing.addUsed(fuelConsumed));
    }

    /**
     * Post-invocation charge that prefers the store's real
     * {@code fuel_consumed} reading through {@link CallbackContext#fuelConsumed()}
     * when the provider supports it (wasmtime as of wasmtime4j 1.4.7 /
     * webassembly4j 2.4.3), falling back to {@code fallback} when the
     * sentinel {@code -1} comes back (module mode, non-wasmtime provider).
     *
     * <p>The fallback is caller-supplied so success paths can pass the
     * observed host-callback tolls ({@code Math.max(1, cbCtx.tollUsed())})
     * and trap paths can pass the per-invocation cap — the same shape the
     * original Phase-1/2 landing used, now upgraded to real accounting
     * when available.
     */
    public void postInvocation(final FuelContext ctx,
                               final CallbackContext cbCtx,
                               final long fallback) {
        final long consumed = cbCtx == null ? -1L : cbCtx.fuelConsumed();
        final long charge = consumed >= 0L ? consumed : fallback;
        postInvocation(ctx, charge);
    }
}
