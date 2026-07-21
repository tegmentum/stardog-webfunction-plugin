package ai.tegmentum.stardog.kibble.webfunctions;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
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
 * @param callChain        wasm-callbacks invocation chain snapshot at
 *                         the moment this row was appended, ordered
 *                         root → deepest. Empty for non-wasm-callbacks
 *                         dispatches and for the pre-multi-level shape
 *                         retained via the 9-arg convenience
 *                         constructor. See
 *                         {@link CallbackContext#wasmCallChainSnapshot}
 *                         for the source-of-truth.
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
        String denyReason,
        List<String> callChain
) implements AuditRow {

    /**
     * Backward-compatible 9-arg constructor — pre-multi-level call
     * sites that don't carry a chain default {@link #callChain} to
     * the empty list. Additive to the multi-level canonical
     * constructor so existing tests and callers keep compiling.
     */
    public CapabilityAuditRow(
            final Instant timestamp,
            final String userId,
            final String orgId,
            final String extensionUri,
            final String interfaceName,
            final String method,
            final String argumentsSummary,
            final Outcome outcome,
            final String denyReason) {
        this(timestamp, userId, orgId, extensionUri, interfaceName, method,
             argumentsSummary, outcome, denyReason, Collections.emptyList());
    }

    /**
     * NDJSON serialization for the Phase 6 disk sink. Field order is stable
     * so downstream tooling that diffs sink output across releases sees a
     * predictable shape; the discriminator {@code "type":"capability"} lets
     * an operator mux the two audit files into one stream later without
     * losing per-row-type identity.
     */
    @Override
    public String toNdjsonLine() {
        final StringBuilder b = new StringBuilder(256);
        b.append("{\"type\":\"capability\",\"timestamp\":");
        AuditRow.escapeJson(timestamp.toString(), b);
        b.append(",\"userId\":");
        AuditRow.escapeJson(userId, b);
        b.append(",\"orgId\":");
        AuditRow.escapeJson(orgId, b);
        b.append(",\"extensionUri\":");
        AuditRow.escapeJson(extensionUri, b);
        b.append(",\"interfaceName\":");
        AuditRow.escapeJson(interfaceName, b);
        b.append(",\"method\":");
        AuditRow.escapeJson(method, b);
        b.append(",\"argumentsSummary\":");
        AuditRow.escapeJson(argumentsSummary, b);
        b.append(",\"outcome\":");
        AuditRow.escapeJson(outcome.name(), b);
        b.append(",\"denyReason\":");
        AuditRow.escapeJson(denyReason, b);
        // callChain lands as a JSON array so downstream tooling can
        // scan it structurally. Always emitted (even when empty) to
        // keep the field set uniform across rows — operator alerting
        // that keys on "callChain":[...] presence otherwise has to
        // special-case the pre-multi-level shape.
        b.append(",\"callChain\":[");
        for (int i = 0; i < callChain.size(); i++) {
            if (i > 0) b.append(',');
            AuditRow.escapeJson(callChain.get(i), b);
        }
        b.append("]}");
        return b.toString();
    }

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
        // callChain treated as optional in the shape; null coerced to
        // an empty list rather than rejected so 9-arg call sites keep
        // working uniformly with the 10-arg canonical form. Non-null
        // input is defensively copied to an unmodifiable list so a
        // caller mutating the list post-append cannot mutate the row.
        callChain = callChain == null ? Collections.emptyList() : List.copyOf(callChain);
    }
}
