package ai.tegmentum.stardog.kibble.webfunctions;

import java.time.Instant;
import java.util.Objects;

/**
 * One append-only row in the capability-audit ring — Phase 1 diagnostic
 * surface for host-callback dispatch. Shape from
 * {@code capability-implementation.md} §2.
 *
 * <p>Distinct from {@link AttributionRow} (fuel-attribution row shape)
 * per implementation memo §11's two-ring decision: capability rows land
 * in {@link CapabilityAttributionRing}, fuel rows in
 * {@link AttributionRing}. Two rings, two row types, one Phase 6 disk
 * backing.
 *
 * <p>Rows are append-only; the ring never rewrites. Retention and rollup
 * ship in Phase 6 alongside fuel-attribution disk backing.
 *
 * @param timestamp        Wall-clock instant the dispatch decision was made.
 * @param userId           Shiro principal or {@code ""} when unknown
 *                         (anonymous or Phase 1 pre-wiring).
 * @param orgId            Shiro org attribute or {@code ""} until Phase 3
 *                         org rollup wires it.
 * @param extensionUri     Wasm URI whose grant this row belongs to.
 * @param interfaceName    Bare WIT interface name (e.g. {@code
 *                         "graph-callbacks"}). Also carries the
 *                         synthetic {@code "capability"} tag for the
 *                         instantiation-time row {@link CapabilityEnforcer#preInvocation}
 *                         writes.
 * @param method           WIT function name (e.g. {@code "execute-query"}),
 *                         or the synthetic {@code "instantiation"} tag
 *                         for the preInvocation row.
 * @param argumentsSummary Short stable digest of the dispatch arguments
 *                         (hostname for HTTP, empty for the
 *                         instantiation row). Full arguments never
 *                         appear in audit — they'd blow the ring's
 *                         capacity and leak potentially-sensitive
 *                         payloads to audit readers per implementation
 *                         memo §2 note.
 * @param outcome          Terminal outcome of the dispatch.
 * @param denyReason       Short discriminator when {@link Outcome#DENIED} —
 *                         mirrors {@link WfCapabilityError.PerCallDenied#REASON_HOST_DENIED}
 *                         etc. Empty when {@link Outcome#GRANTED}.
 */
public record CapabilityAuditRow(
        Instant timestamp,
        String userId,
        String orgId,
        String extensionUri,
        String interfaceName,
        String method,
        String argumentsSummary,
        Outcome outcome,
        String denyReason
) {

    /**
     * Terminal disposition of a host-callback dispatch decision.
     *
     * <p>Phase 1 emits {@link #GRANTED} on allow-through and {@link #DENIED}
     * on any capability-denial path (policy, host allowlist, or Shiro
     * permission). The single {@link #DENIED} variant intentionally
     * collapses the three denial reasons per implementation memo §2 —
     * the {@link #denyReason()} field discriminates without needing
     * separate enum values downstream tooling has to switch on.
     *
     * <p>{@link #GRANTED_UNDECLARED} is the capability-ask
     * warn-on-undeclared signal ({@code capability-ask.md} §8): the
     * grant permitted the dispatch, but the extension's ask did not
     * declare the invoked (interface, method) tuple. Dispatch still
     * proceeds — this is diagnostic, not authorization — and the
     * operator can filter compliance queries by this variant to catch
     * malicious understated asks and buggy drift.
     */
    public enum Outcome {
        GRANTED,
        DENIED,
        GRANTED_UNDECLARED
    }

    /**
     * Synthetic tag written by {@link CapabilityEnforcer#preInvocation}
     * as the row's {@code method} field so a compliance query can
     * distinguish the instantiation record from per-callback records.
     */
    public static final String CALLBACK_INSTANTIATION = "instantiation";

    /**
     * Companion tag for the {@code interfaceName} field on the
     * instantiation row.
     */
    public static final String INTERFACE_TAG_CAPABILITY = "capability";

    public CapabilityAuditRow {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(extensionUri, "extensionUri");
        Objects.requireNonNull(interfaceName, "interfaceName");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(argumentsSummary, "argumentsSummary");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(denyReason, "denyReason");
    }
}
