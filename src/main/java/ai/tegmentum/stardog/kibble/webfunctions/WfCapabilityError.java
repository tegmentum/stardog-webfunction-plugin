package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.StardogException;

/**
 * Typed SPARQL error surface for the capability-policy Phase 1 landing.
 * Mirrors {@link WfBudgetError}'s sealed-hierarchy shape so programmatic
 * callers can dispatch on the variant type without string-matching, and
 * so the JSON payload rides through Stardog's string-only error surface
 * via the same {@code " json=<payload>"} sentinel convention.
 *
 * <p>Phase 1 variants:
 * <ul>
 *   <li>{@link LoadTimeDenied} — instantiation-time capability denial
 *       ({@code WF_CAPABILITY_DENIED_AT_LOAD}). Fires when a required
 *       interface is missing from the effective grant. Policy-actionable
 *       (admin action to grant the interface).</li>
 *   <li>{@link PerCallDenied} — per-callback capability denial
 *       ({@code WF_CAPABILITY_DENIED_AT_CALL}). Fires when a dispatch
 *       fails a method policy, HTTP host allowlist, or Shiro permission
 *       check. Policy-actionable.</li>
 *   <li>{@link ManifestMalformed} — manifest parsing / lookup failure
 *       ({@code WF_CAPABILITY_MANIFEST_MALFORMED}). Fires when the
 *       sidecar manifest is missing, unreadable, or fails TOML parse.
 *       Publisher-actionable (extension author fixes the manifest).</li>
 * </ul>
 *
 * <p>Phase 2 will extend the sealed hierarchy with {@code UnsignedRejected}
 * and {@code SignatureInvalid} once Ed25519 signature verification lands
 * per {@code capability-implementation.md} §14.
 *
 * <p>All variants extend {@link StardogException} (a {@link RuntimeException})
 * so they propagate through Stardog's query-evaluation stack as any other
 * plugin error would.
 */
public abstract sealed class WfCapabilityError extends StardogException
        permits WfCapabilityError.LoadTimeDenied,
                WfCapabilityError.PerCallDenied,
                WfCapabilityError.ManifestMalformed,
                WfCapabilityError.PolicyStoreUnavailable,
                WfCapabilityError.UnknownExtension {

    private final String errorCode;
    private final String jsonPayload;

    private WfCapabilityError(final String errorCode,
                              final String humanMessage,
                              final String jsonPayload) {
        super(humanMessage + " json=" + jsonPayload);
        this.errorCode = errorCode;
        this.jsonPayload = jsonPayload;
    }

    /**
     * Stable identifier — {@code WF_CAPABILITY_DENIED_AT_LOAD},
     * {@code WF_CAPABILITY_DENIED_AT_CALL}, or
     * {@code WF_CAPABILITY_MANIFEST_MALFORMED}.
     */
    public final String errorCode() {
        return errorCode;
    }

    /** Machine-parseable JSON payload; see subclass docs for the schema. */
    public final String jsonPayload() {
        return jsonPayload;
    }

    /**
     * {@code WF_CAPABILITY_DENIED_AT_LOAD} — instantiation refused
     * because a required interface is missing from the effective grant.
     *
     * <p>Fields carry the extension URI, the missing interface name, the
     * resolution stage that dropped it ({@code policy} or {@code shiro}),
     * the invoker principal (or {@code ""} for anonymous), and a short
     * policy-source string admins can grep audit logs against.
     *
     * <p>JSON payload schema:
     * <pre>
     * {
     *   "error_code": "WF_CAPABILITY_DENIED_AT_LOAD",
     *   "extension": "&lt;ipfs://... or file://... wasm URI&gt;",
     *   "missing_interface": "&lt;e.g. graph-callbacks&gt;",
     *   "resolution_stage": "&lt;policy | shiro | linker&gt;",
     *   "invoker": "&lt;shiro principal or ''&gt;",
     *   "policy_source": "&lt;short human tag&gt;"
     * }
     * </pre>
     */
    public static final class LoadTimeDenied extends WfCapabilityError {

        private final String extensionUri;
        private final String missingInterface;
        private final String resolutionStage;
        private final String invoker;
        private final String policySource;

        public LoadTimeDenied(final String extensionUri,
                              final String missingInterface,
                              final String resolutionStage,
                              final String invoker,
                              final String policySource) {
            super("WF_CAPABILITY_DENIED_AT_LOAD",
                  humanMessage(extensionUri, missingInterface, resolutionStage, policySource),
                  jsonOf(extensionUri, missingInterface, resolutionStage, invoker, policySource));
            this.extensionUri     = nonNull(extensionUri);
            this.missingInterface = nonNull(missingInterface);
            this.resolutionStage  = nonNull(resolutionStage);
            this.invoker          = nonNull(invoker);
            this.policySource     = nonNull(policySource);
        }

        public String extensionUri()     { return extensionUri; }
        public String missingInterface() { return missingInterface; }
        public String resolutionStage()  { return resolutionStage; }
        public String invoker()          { return invoker; }
        public String policySource()     { return policySource; }

        private static String humanMessage(final String extensionUri,
                                           final String missingInterface,
                                           final String resolutionStage,
                                           final String policySource) {
            return "Extension '" + nonNull(extensionUri) + "' declared required interface '"
                    + nonNull(missingInterface) + "' but it was denied at "
                    + nonNull(resolutionStage) + " (source: " + nonNull(policySource)
                    + "). Contact your Stardog administrator to add the interface"
                    + " to the extension's capability policy.";
        }

        private static String jsonOf(final String extensionUri,
                                     final String missingInterface,
                                     final String resolutionStage,
                                     final String invoker,
                                     final String policySource) {
            return "{"
                    + "\"error_code\":\"WF_CAPABILITY_DENIED_AT_LOAD\","
                    + "\"extension\":\"" + jsonEscape(nonNull(extensionUri)) + "\","
                    + "\"missing_interface\":\"" + jsonEscape(nonNull(missingInterface)) + "\","
                    + "\"resolution_stage\":\"" + jsonEscape(nonNull(resolutionStage)) + "\","
                    + "\"invoker\":\"" + jsonEscape(nonNull(invoker)) + "\","
                    + "\"policy_source\":\"" + jsonEscape(nonNull(policySource)) + "\""
                    + "}";
        }
    }

    /**
     * {@code WF_CAPABILITY_DENIED_AT_CALL} — per-callback capability
     * denial. Fires when a dispatch fails a method-policy check, an HTTP
     * host allowlist check, or a Shiro permission check.
     *
     * <p>{@link #reason} carries a short discriminator
     * ({@code method-denied}, {@code host-denied},
     * {@code permission-denied}) so audit tooling can group without
     * parsing the human message.
     *
     * <p>JSON payload schema:
     * <pre>
     * {
     *   "error_code": "WF_CAPABILITY_DENIED_AT_CALL",
     *   "extension": "&lt;wasm URI&gt;",
     *   "interface": "&lt;e.g. http-callbacks&gt;",
     *   "method": "&lt;e.g. http-get&gt;",
     *   "invoker": "&lt;shiro principal or ''&gt;",
     *   "reason": "&lt;method-denied | host-denied | permission-denied&gt;",
     *   "arguments_summary": "&lt;hostname for HTTP, db+graph for SPARQL, ...&gt;"
     * }
     * </pre>
     */
    public static final class PerCallDenied extends WfCapabilityError {

        /** Stable discriminator tags for {@link #reason()}. */
        public static final String REASON_METHOD_DENIED     = "method-denied";
        public static final String REASON_HOST_DENIED       = "host-denied";
        public static final String REASON_PERMISSION_DENIED = "permission-denied";
        public static final String REASON_INTERFACE_DENIED  = "interface-denied";

        private final String extensionUri;
        private final String interfaceName;
        private final String method;
        private final String invoker;
        private final String reason;
        private final String argumentsSummary;

        public PerCallDenied(final String extensionUri,
                             final String interfaceName,
                             final String method,
                             final String invoker,
                             final String reason,
                             final String argumentsSummary) {
            super("WF_CAPABILITY_DENIED_AT_CALL",
                  humanMessage(extensionUri, interfaceName, method, reason, argumentsSummary),
                  jsonOf(extensionUri, interfaceName, method, invoker, reason, argumentsSummary));
            this.extensionUri     = nonNull(extensionUri);
            this.interfaceName    = nonNull(interfaceName);
            this.method           = nonNull(method);
            this.invoker          = nonNull(invoker);
            this.reason           = nonNull(reason);
            this.argumentsSummary = nonNull(argumentsSummary);
        }

        public String extensionUri()     { return extensionUri; }
        public String interfaceName()    { return interfaceName; }
        public String method()           { return method; }
        public String invoker()          { return invoker; }
        public String reason()           { return reason; }
        public String argumentsSummary() { return argumentsSummary; }

        private static String humanMessage(final String extensionUri,
                                           final String interfaceName,
                                           final String method,
                                           final String reason,
                                           final String argumentsSummary) {
            return "Extension '" + nonNull(extensionUri) + "' invoked '"
                    + nonNull(interfaceName) + "/" + nonNull(method) + "' with '"
                    + nonNull(argumentsSummary) + "' but the call was denied ("
                    + nonNull(reason) + "). Capability denial, not a network"
                    + " or protocol error.";
        }

        private static String jsonOf(final String extensionUri,
                                     final String interfaceName,
                                     final String method,
                                     final String invoker,
                                     final String reason,
                                     final String argumentsSummary) {
            return "{"
                    + "\"error_code\":\"WF_CAPABILITY_DENIED_AT_CALL\","
                    + "\"extension\":\"" + jsonEscape(nonNull(extensionUri)) + "\","
                    + "\"interface\":\"" + jsonEscape(nonNull(interfaceName)) + "\","
                    + "\"method\":\"" + jsonEscape(nonNull(method)) + "\","
                    + "\"invoker\":\"" + jsonEscape(nonNull(invoker)) + "\","
                    + "\"reason\":\"" + jsonEscape(nonNull(reason)) + "\","
                    + "\"arguments_summary\":\"" + jsonEscape(nonNull(argumentsSummary)) + "\""
                    + "}";
        }
    }

    /**
     * {@code WF_CAPABILITY_MANIFEST_MALFORMED} — the sidecar manifest
     * could not be read or parsed. Fires from
     * {@link ExtensionManifestLoader} on missing sidecar (when
     * {@code require-manifest=true}), on unreadable URL, or on TOML
     * parse error. Publisher-actionable — the extension author fixes
     * the manifest and re-publishes.
     *
     * <p>JSON payload schema:
     * <pre>
     * {
     *   "error_code": "WF_CAPABILITY_MANIFEST_MALFORMED",
     *   "extension": "&lt;wasm URI or manifest URI&gt;",
     *   "parse_error": "&lt;short human parse error or fetch failure&gt;"
     * }
     * </pre>
     */
    public static final class ManifestMalformed extends WfCapabilityError {

        private final String extensionUri;
        private final String parseError;

        public ManifestMalformed(final String extensionUri, final String parseError) {
            super("WF_CAPABILITY_MANIFEST_MALFORMED",
                  humanMessage(extensionUri, parseError),
                  jsonOf(extensionUri, parseError));
            this.extensionUri = nonNull(extensionUri);
            this.parseError   = nonNull(parseError);
        }

        public String extensionUri() { return extensionUri; }
        public String parseError()   { return parseError; }

        private static String humanMessage(final String extensionUri, final String parseError) {
            return "Extension '" + nonNull(extensionUri) + "' manifest failed to load or parse: "
                    + nonNull(parseError) + ". Fix the TOML sidecar and re-upload the extension.";
        }

        private static String jsonOf(final String extensionUri, final String parseError) {
            return "{"
                    + "\"error_code\":\"WF_CAPABILITY_MANIFEST_MALFORMED\","
                    + "\"extension\":\"" + jsonEscape(nonNull(extensionUri)) + "\","
                    + "\"parse_error\":\"" + jsonEscape(nonNull(parseError)) + "\""
                    + "}";
        }
    }

    /**
     * {@code WF_CAPABILITY_POLICY_STORE_UNAVAILABLE} — the capability
     * policy store did not answer. Fires when the store is not installed
     * (plugin bootstrap incomplete), when its Kernel-backed database
     * bootstrap failed, or when a transient read failure returned an
     * empty snapshot the resolver can't distinguish from
     * "unknown-extension policy" without knowing the store's ready state.
     *
     * <p>Deployment-actionable — the operator needs to bring the
     * {@code system-webfunctions-capability} database up.
     *
     * <p>JSON payload schema:
     * <pre>
     * {
     *   "error_code": "WF_CAPABILITY_POLICY_STORE_UNAVAILABLE",
     *   "extension": "&lt;wasm URI&gt;",
     *   "detail": "&lt;short human-readable diagnostic&gt;"
     * }
     * </pre>
     */
    public static final class PolicyStoreUnavailable extends WfCapabilityError {

        private final String extensionUri;
        private final String detail;

        public PolicyStoreUnavailable(final String extensionUri, final String detail) {
            super("WF_CAPABILITY_POLICY_STORE_UNAVAILABLE",
                  humanMessage(extensionUri, detail),
                  jsonOf(extensionUri, detail));
            this.extensionUri = nonNull(extensionUri);
            this.detail       = nonNull(detail);
        }

        public String extensionUri() { return extensionUri; }
        public String detail()       { return detail; }

        private static String humanMessage(final String extensionUri, final String detail) {
            return "Capability policy store unavailable while resolving '"
                    + nonNull(extensionUri) + "': " + nonNull(detail)
                    + ". Ensure the system-webfunctions-capability database is up.";
        }

        private static String jsonOf(final String extensionUri, final String detail) {
            return "{"
                    + "\"error_code\":\"WF_CAPABILITY_POLICY_STORE_UNAVAILABLE\","
                    + "\"extension\":\"" + jsonEscape(nonNull(extensionUri)) + "\","
                    + "\"detail\":\"" + jsonEscape(nonNull(detail)) + "\""
                    + "}";
        }
    }

    /**
     * {@code WF_CAPABILITY_UNKNOWN_EXTENSION} — the policy store is up but
     * has no policy triples for the extension URL, and the configured
     * {@code webfunctions.capability.unknown-extension-policy} is
     * {@code deny}. Admin-actionable — the operator adds policy triples
     * for the extension or flips the unknown-extension policy to permit /
     * inherit.
     *
     * <p>JSON payload schema:
     * <pre>
     * {
     *   "error_code": "WF_CAPABILITY_UNKNOWN_EXTENSION",
     *   "extension": "&lt;wasm URI&gt;",
     *   "invoker": "&lt;shiro principal or ''&gt;",
     *   "policy_source": "&lt;e.g. unknown-extension-policy=deny&gt;"
     * }
     * </pre>
     */
    public static final class UnknownExtension extends WfCapabilityError {

        private final String extensionUri;
        private final String invoker;
        private final String policySource;

        public UnknownExtension(final String extensionUri,
                                final String invoker,
                                final String policySource) {
            super("WF_CAPABILITY_UNKNOWN_EXTENSION",
                  humanMessage(extensionUri, policySource),
                  jsonOf(extensionUri, invoker, policySource));
            this.extensionUri = nonNull(extensionUri);
            this.invoker      = nonNull(invoker);
            this.policySource = nonNull(policySource);
        }

        public String extensionUri() { return extensionUri; }
        public String invoker()      { return invoker; }
        public String policySource() { return policySource; }

        private static String humanMessage(final String extensionUri,
                                           final String policySource) {
            return "Extension '" + nonNull(extensionUri)
                    + "' has no capability policy in the store (source: "
                    + nonNull(policySource) + "). Add policy triples to the"
                    + " system-webfunctions-capability database, or flip"
                    + " webfunctions.capability.unknown-extension-policy to"
                    + " permit/inherit.";
        }

        private static String jsonOf(final String extensionUri,
                                     final String invoker,
                                     final String policySource) {
            return "{"
                    + "\"error_code\":\"WF_CAPABILITY_UNKNOWN_EXTENSION\","
                    + "\"extension\":\"" + jsonEscape(nonNull(extensionUri)) + "\","
                    + "\"invoker\":\"" + jsonEscape(nonNull(invoker)) + "\","
                    + "\"policy_source\":\"" + jsonEscape(nonNull(policySource)) + "\""
                    + "}";
        }
    }

    private static String nonNull(final String s) {
        return s == null ? "" : s;
    }

    /**
     * Minimal JSON string escaping — only the characters mandated by
     * RFC 8259 §7 that would break a bare {@code "..."} literal. Same
     * shape as {@link WfBudgetError}'s helper.
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
