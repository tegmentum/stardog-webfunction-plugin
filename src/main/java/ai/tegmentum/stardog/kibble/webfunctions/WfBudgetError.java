package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.StardogException;

import java.time.Instant;

/**
 * Typed SPARQL error surface for the fuel-metering Phase 1 defensive layer.
 *
 * <p>Sealed hierarchy — a client that programmatically catches these can
 * dispatch on the two variants without a chain of {@code getMessage()}
 * string-matching. Each variant carries a machine-readable JSON payload
 * (available via {@link #jsonPayload()}) alongside a human-readable
 * {@link #getMessage()}.
 *
 * <p>The JSON payload is also embedded in {@link #getMessage()} at a
 * well-known suffix ({@code " json="}), so downstream surfaces that only
 * carry {@code String message} (Stardog's SPARQL Results JSON error field
 * currently among them) still expose the structured data through a
 * documented parsing convention. Programmatic callers that hold the Java
 * object should prefer {@link #jsonPayload()} directly.
 *
 * <p>Both variants extend {@link StardogException} (a {@link RuntimeException})
 * so they propagate through Stardog's query-evaluation stack as any other
 * plugin error would; both are Phase-1 defensive-only per
 * {@code fuel-implementation.md} §5 and §8. Phase-2+ variants
 * (WF_USER_QUOTA_EXHAUSTED, WF_ORG_QUOTA_EXHAUSTED, WF_*_RATE_LIMITED)
 * are not landed in Phase 1 and will extend this same base when they are.
 *
 * <p>Not exposed here: fuel-consumed as reported by wasmtime's store. The
 * wasmtime4j provider does not expose a fuel-remaining/consumed hook to
 * component-mode callers today, so Phase 1 reports the configured cap as
 * {@code fuelConsumed} when a per-invocation trap fires (upper bound of
 * consumption is honest since the guest necessarily hit the cap). Phase 2+
 * lands the real accounting once wasmtime4j-provider surfaces the store.
 */
public abstract sealed class WfBudgetError extends StardogException
        permits WfBudgetError.PerInvocationTrap,
                WfBudgetError.HostCallbackTollExhausted,
                WfBudgetError.UserQuotaExhausted,
                WfBudgetError.DeadlineExceeded {

    private final String errorCode;
    private final String jsonPayload;

    private WfBudgetError(final String errorCode,
                          final String humanMessage,
                          final String jsonPayload) {
        super(humanMessage + " json=" + jsonPayload);
        this.errorCode = errorCode;
        this.jsonPayload = jsonPayload;
    }

    /** Stable identifier — {@code WF_PER_INVOCATION_TRAP} or {@code WF_HOST_CALLBACK_TOLL_EXHAUSTED}. */
    public final String errorCode() {
        return errorCode;
    }

    /** Machine-parseable JSON payload; see subclass docs for the schema. */
    public final String jsonPayload() {
        return jsonPayload;
    }

    /**
     * {@code WF_PER_INVOCATION_TRAP} — the guest exhausted its per-invocation
     * fuel cap. Extension-author-actionable: fix the extension (likely a
     * runaway loop) or raise {@code webfunctions.fuel.per-invocation.max}.
     *
     * <p>JSON payload schema:
     * <pre>
     * {
     *   "error_code": "WF_PER_INVOCATION_TRAP",
     *   "extension": "&lt;ipfs://... or file://... wasm URI&gt;",
     *   "fuel_consumed": &lt;long, upper bound = per_invocation_max&gt;,
     *   "per_invocation_max": &lt;long&gt;
     * }
     * </pre>
     */
    public static final class PerInvocationTrap extends WfBudgetError {

        private final String extensionUri;
        private final long fuelConsumed;
        private final long perInvocationMax;

        public PerInvocationTrap(final String extensionUri,
                                 final long fuelConsumed,
                                 final long perInvocationMax) {
            super("WF_PER_INVOCATION_TRAP",
                  humanMessage(extensionUri, perInvocationMax),
                  jsonOf(extensionUri, fuelConsumed, perInvocationMax));
            this.extensionUri = extensionUri;
            this.fuelConsumed = fuelConsumed;
            this.perInvocationMax = perInvocationMax;
        }

        public String extensionUri()     { return extensionUri; }
        public long   fuelConsumed()     { return fuelConsumed; }
        public long   perInvocationMax() { return perInvocationMax; }

        private static String humanMessage(final String extensionUri, final long cap) {
            return "Extension '" + extensionUri + "' exceeded per-invocation fuel limit ("
                    + cap + " units). This may indicate a runaway loop or malformed extension."
                    + " Fix the extension or raise webfunctions.fuel.per-invocation.max.";
        }

        private static String jsonOf(final String extensionUri,
                                     final long fuelConsumed,
                                     final long perInvocationMax) {
            return "{"
                    + "\"error_code\":\"WF_PER_INVOCATION_TRAP\","
                    + "\"extension\":\"" + jsonEscape(extensionUri) + "\","
                    + "\"fuel_consumed\":" + fuelConsumed + ","
                    + "\"per_invocation_max\":" + perInvocationMax
                    + "}";
        }
    }

    /**
     * {@code WF_HOST_CALLBACK_TOLL_EXHAUSTED} — the per-invocation toll
     * budget was exhausted while the host attempted to deduct the fixed
     * toll for a specific callback before dispatching it. A variant of
     * per-invocation trap; the callback name lets the extension author
     * identify which host-callback path is being spammed.
     *
     * <p>JSON payload schema:
     * <pre>
     * {
     *   "error_code": "WF_HOST_CALLBACK_TOLL_EXHAUSTED",
     *   "extension": "&lt;ipfs://... or file://... wasm URI&gt;",
     *   "callback_name": "&lt;e.g. graph-callbacks.execute-query&gt;",
     *   "fuel_consumed": &lt;long, toll cycles used to this point&gt;,
     *   "per_invocation_max": &lt;long&gt;,
     *   "host_callback_toll": &lt;long, per-callback toll amount&gt;
     * }
     * </pre>
     */
    public static final class HostCallbackTollExhausted extends WfBudgetError {

        private final String extensionUri;
        private final String callbackName;
        private final long fuelConsumed;
        private final long perInvocationMax;
        private final long hostCallbackToll;

        public HostCallbackTollExhausted(final String extensionUri,
                                         final String callbackName,
                                         final long fuelConsumed,
                                         final long perInvocationMax,
                                         final long hostCallbackToll) {
            super("WF_HOST_CALLBACK_TOLL_EXHAUSTED",
                  humanMessage(extensionUri, callbackName),
                  jsonOf(extensionUri, callbackName, fuelConsumed, perInvocationMax, hostCallbackToll));
            this.extensionUri = extensionUri;
            this.callbackName = callbackName;
            this.fuelConsumed = fuelConsumed;
            this.perInvocationMax = perInvocationMax;
            this.hostCallbackToll = hostCallbackToll;
        }

        public String extensionUri()      { return extensionUri; }
        public String callbackName()      { return callbackName; }
        public long   fuelConsumed()      { return fuelConsumed; }
        public long   perInvocationMax()  { return perInvocationMax; }
        public long   hostCallbackToll()  { return hostCallbackToll; }

        private static String humanMessage(final String extensionUri, final String callbackName) {
            return "Extension '" + extensionUri + "' exhausted its per-invocation fuel while"
                    + " paying the toll for host callback '" + callbackName + "'."
                    + " This is a variant of per-invocation trap; treat as runaway callback traffic.";
        }

        private static String jsonOf(final String extensionUri,
                                     final String callbackName,
                                     final long fuelConsumed,
                                     final long perInvocationMax,
                                     final long hostCallbackToll) {
            return "{"
                    + "\"error_code\":\"WF_HOST_CALLBACK_TOLL_EXHAUSTED\","
                    + "\"extension\":\"" + jsonEscape(extensionUri) + "\","
                    + "\"callback_name\":\"" + jsonEscape(callbackName) + "\","
                    + "\"fuel_consumed\":" + fuelConsumed + ","
                    + "\"per_invocation_max\":" + perInvocationMax + ","
                    + "\"host_callback_toll\":" + hostCallbackToll
                    + "}";
        }
    }

    /**
     * {@code WF_USER_QUOTA_EXHAUSTED} — the invoking user has exceeded their
     * monthly commercial fuel quota. Customer-actionable: contact the Stardog
     * administrator to raise the allocation, or wait until the monthly reset.
     *
     * <p>Distinct from the two per-invocation defensive traps (which are
     * extension-author-actionable) — this is the sales-signal error surface
     * from {@code fuel-metering.md} §9.
     *
     * <p>JSON payload schema:
     * <pre>
     * {
     *   "error_code": "WF_USER_QUOTA_EXHAUSTED",
     *   "user": "&lt;shiro principal or ''&gt;",
     *   "org": "&lt;shiro-attribute org or ''&gt;",
     *   "extension": "&lt;ipfs://... or file://... wasm URI&gt;",
     *   "monthly_used": &lt;long&gt;,
     *   "monthly_budget": &lt;long&gt;,
     *   "reset_at": "&lt;ISO-8601 UTC instant&gt;"
     * }
     * </pre>
     */
    public static final class UserQuotaExhausted extends WfBudgetError {

        private final String userId;
        private final String orgId;
        private final String extensionUri;
        private final long monthlyUsed;
        private final long monthlyBudget;
        private final Instant resetAt;

        public UserQuotaExhausted(final String userId,
                                  final String orgId,
                                  final String extensionUri,
                                  final long monthlyUsed,
                                  final long monthlyBudget,
                                  final Instant resetAt) {
            super("WF_USER_QUOTA_EXHAUSTED",
                  humanMessage(userId, monthlyUsed, monthlyBudget, resetAt),
                  jsonOf(userId, orgId, extensionUri, monthlyUsed, monthlyBudget, resetAt));
            this.userId = userId == null ? "" : userId;
            this.orgId = orgId == null ? "" : orgId;
            this.extensionUri = extensionUri == null ? "" : extensionUri;
            this.monthlyUsed = monthlyUsed;
            this.monthlyBudget = monthlyBudget;
            this.resetAt = resetAt;
        }

        public String  userId()        { return userId; }
        public String  orgId()         { return orgId; }
        public String  extensionUri()  { return extensionUri; }
        public long    monthlyUsed()   { return monthlyUsed; }
        public long    monthlyBudget() { return monthlyBudget; }
        public Instant resetAt()       { return resetAt; }

        private static String humanMessage(final String userId,
                                           final long used,
                                           final long budget,
                                           final Instant resetAt) {
            final String u = userId == null || userId.isEmpty() ? "<unknown>" : userId;
            final String r = resetAt == null ? "<unknown>" : resetAt.toString();
            return "User '" + u + "' has used " + used + " of " + budget
                    + " monthly extension fuel units."
                    + " Contact your Stardog administrator to raise your"
                    + " allocation, or wait until " + r + " for the monthly reset.";
        }

        private static String jsonOf(final String userId,
                                     final String orgId,
                                     final String extensionUri,
                                     final long monthlyUsed,
                                     final long monthlyBudget,
                                     final Instant resetAt) {
            return "{"
                    + "\"error_code\":\"WF_USER_QUOTA_EXHAUSTED\","
                    + "\"user\":\"" + jsonEscape(userId == null ? "" : userId) + "\","
                    + "\"org\":\"" + jsonEscape(orgId == null ? "" : orgId) + "\","
                    + "\"extension\":\"" + jsonEscape(extensionUri == null ? "" : extensionUri) + "\","
                    + "\"monthly_used\":" + monthlyUsed + ","
                    + "\"monthly_budget\":" + monthlyBudget + ","
                    + "\"reset_at\":\"" + jsonEscape(resetAt == null ? "" : resetAt.toString()) + "\""
                    + "}";
        }
    }

    /**
     * {@code WF_DEADLINE_EXCEEDED} — the invocation's effective deadline
     * has been reached at a host-callback boundary. Two sources feed the
     * deadline:
     * <ul>
     *   <li>{@code SOURCE_CONFIG} — the plugin's own
     *       {@link WebFunctionConfig#PROP_MAX_EXEC_MILLIS} cap, captured
     *       at {@link CallbackContext} bind time. Elapsed wall-clock time
     *       since bind exceeded the cap.</li>
     *   <li>{@code SOURCE_MONITOR} — the outer query's Stardog
     *       {@code ExecutionMonitor} reported {@code isCancelled()} (query
     *       timeout, admin kill, or shutdown). Detected at the next
     *       host-callback dispatch.</li>
     * </ul>
     *
     * <p>Fires cooperatively at host-callback dispatch — a wasm frame that
     * never re-enters a host callback (pure compute) is not interrupted
     * by this variant; the substrate's engine-level ceilings (fuel cap,
     * epoch deadline if enabled) cover that path. Chain propagation is
     * automatic: nested wasm invocations share the outer CallbackContext,
     * so a deadline captured at the root frame applies to every callee
     * dispatch.
     *
     * <p>JSON payload schema:
     * <pre>
     * {
     *   "error_code": "WF_DEADLINE_EXCEEDED",
     *   "extension": "&lt;ipfs://... or file://... wasm URI&gt;",
     *   "callback_name": "&lt;e.g. graph-callbacks.execute-query&gt;",
     *   "elapsed_millis": &lt;long, wall-clock since context bind&gt;,
     *   "deadline_millis": &lt;long, config cap; 0 when triggered by monitor&gt;,
     *   "source": "config" | "monitor"
     * }
     * </pre>
     */
    public static final class DeadlineExceeded extends WfBudgetError {

        /** Config-side trip — {@link WebFunctionConfig#PROP_MAX_EXEC_MILLIS}. */
        public static final String SOURCE_CONFIG  = "config";
        /** Monitor-side trip — Stardog {@code ExecutionMonitor.isCancelled()}. */
        public static final String SOURCE_MONITOR = "monitor";

        private final String extensionUri;
        private final String callbackName;
        private final long   elapsedMillis;
        private final long   deadlineMillis;
        private final String source;

        public DeadlineExceeded(final String extensionUri,
                                final String callbackName,
                                final long elapsedMillis,
                                final long deadlineMillis,
                                final String source) {
            super("WF_DEADLINE_EXCEEDED",
                  humanMessage(extensionUri, callbackName, elapsedMillis, deadlineMillis, source),
                  jsonOf(extensionUri, callbackName, elapsedMillis, deadlineMillis, source));
            this.extensionUri = extensionUri == null ? "" : extensionUri;
            this.callbackName = callbackName == null ? "" : callbackName;
            this.elapsedMillis = elapsedMillis;
            this.deadlineMillis = deadlineMillis;
            this.source = source == null ? "" : source;
        }

        public String extensionUri()   { return extensionUri; }
        public String callbackName()   { return callbackName; }
        public long   elapsedMillis()  { return elapsedMillis; }
        public long   deadlineMillis() { return deadlineMillis; }
        public String source()         { return source; }

        private static String humanMessage(final String extensionUri,
                                           final String callbackName,
                                           final long elapsedMillis,
                                           final long deadlineMillis,
                                           final String source) {
            if (SOURCE_MONITOR.equals(source)) {
                return "Extension '" + extensionUri + "' cancelled at host callback '"
                        + callbackName + "' — outer query monitor reported cancellation "
                        + "after " + elapsedMillis + " ms. This is a query-level timeout "
                        + "or admin cancellation observed at the plugin boundary.";
            }
            return "Extension '" + extensionUri + "' exceeded the plugin-side execution "
                    + "deadline (" + deadlineMillis + " ms) at host callback '"
                    + callbackName + "' after " + elapsedMillis + " ms. Raise "
                    + WebFunctionConfig.PROP_MAX_EXEC_MILLIS
                    + " or reduce the extension's callback chain latency.";
        }

        private static String jsonOf(final String extensionUri,
                                     final String callbackName,
                                     final long elapsedMillis,
                                     final long deadlineMillis,
                                     final String source) {
            return "{"
                    + "\"error_code\":\"WF_DEADLINE_EXCEEDED\","
                    + "\"extension\":\"" + jsonEscape(extensionUri == null ? "" : extensionUri) + "\","
                    + "\"callback_name\":\"" + jsonEscape(callbackName == null ? "" : callbackName) + "\","
                    + "\"elapsed_millis\":" + elapsedMillis + ","
                    + "\"deadline_millis\":" + deadlineMillis + ","
                    + "\"source\":\"" + jsonEscape(source == null ? "" : source) + "\""
                    + "}";
        }
    }

    /**
     * Minimal JSON string escaping — only the characters mandated by
     * RFC 8259 §7 that would break a bare {@code "..."} literal. Enough
     * for extension URIs and callback names; we do not accept arbitrary
     * user text into either field, so the escape surface is small.
     */
    private static String jsonEscape(final String s) {
        if (s == null) return "";
        final StringBuilder out = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b");  break;
                case '\f': out.append("\\f");  break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
