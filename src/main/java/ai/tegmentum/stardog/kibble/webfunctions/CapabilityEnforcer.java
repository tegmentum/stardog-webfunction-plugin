package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.webassembly4j.api.Component;
import com.complexible.stardog.security.ShiroUtils;
import com.complexible.stardog.security.StardogAuthorizationException;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.authz.permission.WildcardPermission;
import org.apache.shiro.subject.Subject;

import java.net.URL;
import java.util.Optional;

/**
 * Capability-policy orchestration — mirrors {@link UserFuelPolicy}'s
 * shape so the Phase 1b agent wires it into the invocation hot path
 * through the same code sites.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #preInvocation(Component, URL)} —
 *       instantiation-time. Delegates to
 *       {@link CapabilityPolicyResolver#resolve} and writes a single
 *       {@code GRANTED} row (or leaves the throw path to the resolver's
 *       {@link WfCapabilityError.LoadTimeDenied} /
 *       {@link WfCapabilityError.UnknownExtension} /
 *       {@link WfCapabilityError.PolicyStoreUnavailable}).</li>
 *   <li>{@link #perCallback(CallbackContext, CapabilityGrant, String, String, String)}
 *       — per host-callback dispatch. Runs three checks in order (method
 *       policy, HTTP host allowlist, Shiro permission) and writes an
 *       audit row on both branches — GRANTED and DENIED per strategy
 *       memo §9's second commitment.</li>
 * </ul>
 *
 * <p>Installation pattern mirrors {@link UserFuelPolicy#install}:
 * {@link WebFunctionServiceModule} (Phase 1b's job) constructs an
 * instance at startup and calls {@link #install}; the hot path fetches
 * a fresh reference via {@link #activePolicy()}, which returns
 * {@link Optional#empty()} when the master gate is off so the hot path
 * short-circuits Phase 1 work entirely on unconfigured deployments.
 */
public final class CapabilityEnforcer {

    private static volatile CapabilityEnforcer INSTANCE;
    private static volatile boolean ENABLED;

    private CapabilityEnforcer() {}

    /**
     * Install the singleton. Phase 1b's module wiring layer calls this
     * once at startup after {@code CapabilityPolicyResolver.setAnonymousPolicy}.
     * Null clears the installation for tests that need a clean slate.
     */
    public static void install(final CapabilityEnforcer enforcer) {
        INSTANCE = enforcer;
    }

    /**
     * Fresh instance factory — the enforcer is stateless per-call, so
     * production wiring is:
     * <pre>
     *   CapabilityEnforcer.install(CapabilityEnforcer.create());
     * </pre>
     */
    public static CapabilityEnforcer create() {
        return new CapabilityEnforcer();
    }

    /**
     * Master gate — Phase 1b wires from
     * {@code webfunctions.capability.enabled}. When {@code false},
     * {@link #activePolicy()} returns empty and the hot path bypasses
     * Phase 1 work.
     */
    public static void setEnabled(final boolean on) {
        ENABLED = on;
    }

    /** Current enabled state — for tests. */
    static boolean enabled() {
        return ENABLED;
    }

    /**
     * Return the currently-installed enforcer, or empty when the master
     * gate is off or nothing has been installed yet. Callers on the hot
     * path use this to skip all capability work in the unconfigured case.
     */
    public static Optional<CapabilityEnforcer> activePolicy() {
        if (!ENABLED) return Optional.empty();
        final CapabilityEnforcer e = INSTANCE;
        return Optional.ofNullable(e);
    }

    /**
     * Instantiation-time capability check. Resolves the grant per
     * {@link CapabilityPolicyResolver} — throws
     * {@link WfCapabilityError.LoadTimeDenied} on failure — and writes
     * one {@code GRANTED} audit row on success with the synthetic
     * {@code capability/instantiation} tag pair.
     *
     * @return the resolved grant; caller stamps it onto the
     *         {@code CallbackContext} for the hot-path enforcement to
     *         consume.
     */
    public CapabilityGrant preInvocation(final Component component,
                                         final URL extensionUrl) {
        final Subject subject = currentSubjectOrNull();
        final CapabilityGrant grant = CapabilityPolicyResolver.resolve(
                extensionUrl, component, subject);
        CapabilityAttributionRing.recordGranted(
                grant.invokerPrincipal(),
                grant.extensionUri(),
                CapabilityAuditRow.INTERFACE_TAG_CAPABILITY,
                CapabilityAuditRow.CALLBACK_INSTANTIATION,
                "");
        return grant;
    }

    /**
     * Per-callback capability check. Three checks in order, matching the
     * §7 memo pseudocode:
     * <ol>
     *   <li>Interface + method policy — grant must allow the tuple.</li>
     *   <li>HTTP host allowlist — {@code http-callbacks/*} dispatches
     *       extract the hostname from {@code argsSummary} and check the
     *       allowlist.</li>
     *   <li>Shiro permission — check
     *       {@link WebFunctionCapability#forExecute} against the current
     *       subject via {@link ShiroUtils#check}.</li>
     * </ol>
     *
     * <p>On success, writes a {@link CapabilityAuditRow.Outcome#GRANTED}
     * row and returns. On any failure, writes a
     * {@link CapabilityAuditRow.Outcome#DENIED} row and throws
     * {@link WfCapabilityError.PerCallDenied} carrying the discriminator
     * reason tag.
     */
    public void perCallback(final CallbackContext ctx,
                            final CapabilityGrant grant,
                            final String interfaceName,
                            final String method,
                            final String argsSummary) {
        final String invoker = grant == null ? "" : grant.invokerPrincipal();
        final String extensionUri = ctx == null ? "" : ctx.extensionUri();
        // Snapshot the current wasm-callbacks invocation chain — empty
        // at the outermost frame or on non-wasm-callbacks dispatches.
        // Threaded into every audit row we write so an operator can
        // grep the ring by chain root or by chain member.
        final java.util.List<String> callChain = ctx == null
                ? java.util.Collections.emptyList()
                : ctx.wasmCallChainSnapshot();

        // 1. Interface + method policy.
        if (grant == null || !grant.allowsInterface(interfaceName)) {
            denyAndThrow(invoker, extensionUri, interfaceName, method, argsSummary,
                    WfCapabilityError.PerCallDenied.REASON_INTERFACE_DENIED, callChain);
        }
        if (!grant.allowsMethod(interfaceName, method)) {
            denyAndThrow(invoker, extensionUri, interfaceName, method, argsSummary,
                    WfCapabilityError.PerCallDenied.REASON_METHOD_DENIED, callChain);
        }

        // 2. HTTP host allowlist. Empty allowlist means "no restriction
        // beyond the coarser interface + method check" (Phase 5 empty-
        // means-unrestricted semantics, applied uniformly across the
        // three per-argument allowlist axes so a policy that grants
        // cap:allowInterface cap:HttpCallbacks without any cap:allowHost
        // triples still passes the host check).
        if ("http-callbacks".equals(interfaceName)
                && !grant.httpAllowlist().isEmpty()
                && !grant.httpAllowlist().matches(argsSummary)) {
            denyAndThrow(invoker, extensionUri, interfaceName, method, argsSummary,
                    WfCapabilityError.PerCallDenied.REASON_HOST_DENIED, callChain);
        }

        // 3. Shiro permission — collapse any Shiro/Stardog failure onto
        // permission-denied so a broken auth path doesn't leak an
        // ambient-credential path through to the guest.
        final String permString = WebFunctionCapability.forExecute(interfaceName, method);
        boolean shiroOk;
        try {
            shiroOk = ShiroUtils.check(new WildcardPermission(permString));
        } catch (StardogAuthorizationException noSubject) {
            // No authenticated subject — treat as permission denied so
            // the grant surface never fires without Shiro's approval.
            shiroOk = false;
        } catch (AuthorizationException authFail) {
            // Covers UnauthenticatedException, UnauthorizedException,
            // and any other Shiro authz-hierarchy subtype uniformly.
            shiroOk = false;
        } catch (RuntimeException other) {
            // Any other Shiro / Stardog runtime error on the check path
            // resolves to permission-denied; the audit row surfaces the
            // reason to the operator.
            shiroOk = false;
        }
        if (!shiroOk) {
            denyAndThrow(invoker, extensionUri, interfaceName, method, argsSummary,
                    WfCapabilityError.PerCallDenied.REASON_PERMISSION_DENIED, callChain);
        }

        // Success — one GRANTED row per dispatch per strategy memo §9.
        CapabilityAttributionRing.recordGranted(
                invoker, extensionUri, interfaceName, method, argsSummary, callChain);
    }

    /**
     * Phase 5 — HTTP path-prefix allowlist check. Called by
     * {@link HostCallbacks#httpGet} / {@link HostCallbacks#httpPostJsonV1}
     * <em>after</em> {@link #perCallback} has cleared the coarser
     * interface / method / host axes.
     *
     * <p>Empty allowlist ⇒ no restriction (returns without checking).
     * Non-empty allowlist ⇒ {@code host+path} (URL scheme + userinfo
     * stripped) must start with any configured pattern; otherwise deny
     * with {@link WfCapabilityError.PerCallDenied#REASON_HTTP_PATH_DENIED}.
     *
     * <p>Same audit-row shape as {@link #perCallback}'s denials: one
     * {@link CapabilityAuditRow.Outcome#DENIED} row per rejection so
     * operators can grep the ring for a specific extension's http-path
     * failures.
     */
    public void enforceHttpPath(final CapabilityGrant grant,
                                final String extensionUri,
                                final String method,
                                final String url) {
        enforceHttpPath(grant, extensionUri, method, url,
                java.util.Collections.emptyList());
    }

    /**
     * Multi-level-aware {@link #enforceHttpPath} variant — the caller
     * threads the current wasm-callbacks invocation chain into the
     * audit-row so an operator scanning denials can see the root
     * extension whose nested callee tripped the path check. Empty
     * chain for non-wasm-callbacks paths.
     */
    public void enforceHttpPath(final CapabilityGrant grant,
                                final String extensionUri,
                                final String method,
                                final String url,
                                final java.util.List<String> callChain) {
        if (grant == null) return;
        if (grant.httpPathAllowlist().isEmpty()) return;
        final String hostAndPath = hostAndPathFromUrl(url);
        if (!grant.httpPathAllowlist().matches(hostAndPath)) {
            denyAndThrow(grant.invokerPrincipal(), extensionUri,
                    "http-callbacks", method, hostAndPath,
                    WfCapabilityError.PerCallDenied.REASON_HTTP_PATH_DENIED,
                    callChain);
        }
    }

    /**
     * Phase 5 — wasm callee URL allowlist check. Called by
     * {@link HostCallbacks#invokeWasm} / {@link HostCallbacks#invokeWasmV1}
     * / {@link HostCallbacks#invokeWasmService} <em>after</em>
     * {@link #perCallback} has cleared the coarser interface / method
     * axes.
     *
     * <p>Empty allowlist ⇒ no restriction (returns without checking).
     * Non-empty allowlist ⇒ callee URL must equal any configured pattern
     * exactly; otherwise deny with
     * {@link WfCapabilityError.PerCallDenied#REASON_WASM_CALLEE_DENIED}.
     *
     * <p>URL-scheme-agnostic — callee URLs are compared as-is (ipfs://,
     * https://, file://, ...).
     */
    public void enforceWasmCallee(final CapabilityGrant grant,
                                  final String extensionUri,
                                  final String method,
                                  final String calleeUrl) {
        enforceWasmCallee(grant, extensionUri, method, calleeUrl,
                java.util.Collections.emptyList());
    }

    /**
     * Multi-level-aware {@link #enforceWasmCallee} variant — carries
     * the current wasm-callbacks invocation chain into the denial
     * audit-row so an operator scanning callee-allowlist rejections
     * can see the full path root → deepest.
     */
    public void enforceWasmCallee(final CapabilityGrant grant,
                                  final String extensionUri,
                                  final String method,
                                  final String calleeUrl,
                                  final java.util.List<String> callChain) {
        if (grant == null) return;
        if (grant.wasmCalleeAllowlist().isEmpty()) return;
        if (!grant.wasmCalleeAllowlist().matches(calleeUrl)) {
            denyAndThrow(grant.invokerPrincipal(), extensionUri,
                    "wasm-callbacks", method, calleeUrl == null ? "" : calleeUrl,
                    WfCapabilityError.PerCallDenied.REASON_WASM_CALLEE_DENIED,
                    callChain);
        }
    }

    /**
     * Extract the {@code host+path} portion from a URL for
     * {@link HttpPathAllowlist} matching. Strips scheme (e.g.
     * {@code "https://"}) + optional userinfo / port. Returns the raw
     * input when the URL fails to parse — the allowlist matcher will
     * then reject it (no pattern will start with {@code "https://"}).
     *
     * <p>Kept package-private so tests can drive parsing edge cases
     * without going through the full HostCallbacks stack.
     */
    static String hostAndPathFromUrl(final String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            final java.net.URI uri = java.net.URI.create(url);
            final String host = uri.getHost();
            final String path = uri.getRawPath();
            if (host == null) return url;
            return host + (path == null ? "" : path);
        } catch (RuntimeException ignore) {
            return url;
        }
    }

    private static void denyAndThrow(final String invoker,
                                     final String extensionUri,
                                     final String interfaceName,
                                     final String method,
                                     final String argsSummary,
                                     final String reason) {
        denyAndThrow(invoker, extensionUri, interfaceName, method, argsSummary,
                reason, java.util.Collections.emptyList());
    }

    /**
     * Multi-level-aware {@link #denyAndThrow} variant — threads the
     * caller's wasm-callbacks invocation chain into the DENIED audit
     * row so an operator scanning denials can see the root extension
     * whose deep callee tripped the rule.
     */
    private static void denyAndThrow(final String invoker,
                                     final String extensionUri,
                                     final String interfaceName,
                                     final String method,
                                     final String argsSummary,
                                     final String reason,
                                     final java.util.List<String> callChain) {
        CapabilityAttributionRing.recordDenied(
                invoker, extensionUri, interfaceName, method, argsSummary, reason, callChain);
        throw new WfCapabilityError.PerCallDenied(
                extensionUri, interfaceName, method, invoker, reason, argsSummary);
    }

    /**
     * Fetch the current Shiro subject or return null when none is bound.
     * Same defensive pattern {@link FuelContext#extract} uses — a
     * broken auth path must not take down the invocation.
     */
    private static Subject currentSubjectOrNull() {
        try {
            final Subject s = SecurityUtils.getSubject();
            if (s == null || s.getPrincipal() == null) return null;
            return s;
        } catch (RuntimeException ignore) {
            return null;
        }
    }
}
