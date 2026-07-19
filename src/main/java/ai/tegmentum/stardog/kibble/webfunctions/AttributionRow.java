package ai.tegmentum.stardog.kibble.webfunctions;

import java.time.Instant;
import java.util.Objects;

/**
 * One append-only row in the fuel-metering attribution ring.
 *
 * <p>Shape matches {@code fuel-implementation.md} §3 exactly so a Phase 6
 * disk-backed serialization can be added without a schema migration.
 * Phase 1 populates {@link #timestamp}, {@link #extensionUri},
 * {@link #fuelConsumed}, and {@link #outcome}. {@link #userId} /
 * {@link #orgId} are always empty in Phase 1 — Phase 2 wires Shiro
 * identity resolution through {@link CallbackContext}. {@link #queryId}
 * is populated on the SERVICE {@code wf:call} path where an
 * {@link com.complexible.stardog.plan.eval.ExecutionMonitor} is in scope;
 * empty on the filter-function BIND path since
 * {@link com.complexible.stardog.plan.filter.functions.UserDefinedFunction}
 * does not receive an {@link com.complexible.stardog.plan.eval.ExecutionContext}.
 *
 * <p>All fields are non-null. Empty strings are used for "unknown" so the
 * downstream disk serialization has a stable, discriminator-friendly shape.
 *
 * @param timestamp     Wall-clock instant the invocation completed.
 * @param userId        Shiro principal or "" when unknown (Phase 1: always "").
 * @param orgId         Shiro org attribute or "" when unknown (Phase 1: always "").
 * @param extensionUri  The wasm URI invoked (ipfs://, file://, ...).
 * @param fuelConsumed  Cycles charged for this invocation. Best-available
 *                      approximation on the Java side — Phase 1 reports the
 *                      per-invocation cap on trap (upper bound; the guest
 *                      necessarily hit the cap) and the running toll for
 *                      success. Phase 2+ threads real fuelConsumed from
 *                      wasmtime4j once that provider surfaces the store hook.
 * @param outcome       Terminal disposition — see {@link Outcome}.
 * @param queryId       Stardog QueryId ({@code ExecutionMonitor.getQueryId()})
 *                      or "" when unavailable (filter-function {@code wf:call}
 *                      always).
 */
public record AttributionRow(
        Instant timestamp,
        String userId,
        String orgId,
        String extensionUri,
        long fuelConsumed,
        Outcome outcome,
        String queryId
) {

    public AttributionRow {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(extensionUri, "extensionUri");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(queryId, "queryId");
    }

    /**
     * Terminal outcome of a {@code wf:call} invocation.
     *
     * <p>Phase 1 populates {@link #SUCCESS}, {@link #PER_INVOCATION_TRAP},
     * and {@link #HOST_CALLBACK_TOLL_EXHAUSTED}. The remaining variants are
     * reserved for Phase 2 (per-user quota), Phase 3 (per-org rollup), and
     * Phase 4 (rate limiting) — see {@code fuel-implementation.md} §8. They
     * live here so downstream tooling that switches on the enum does not
     * need a schema bump when those phases land.
     */
    public enum Outcome {
        SUCCESS,
        PER_INVOCATION_TRAP,
        HOST_CALLBACK_TOLL_EXHAUSTED,
        // Phase 2+ additions — reserved but unused in Phase 1.
        USER_QUOTA_EXHAUSTED,
        ORG_QUOTA_EXHAUSTED,
        USER_RATE_LIMITED,
        ORG_RATE_LIMITED
    }
}
