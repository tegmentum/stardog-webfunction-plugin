package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.webassembly4j.api.Component;
import com.complexible.stardog.security.ShiroUtils;
import com.complexible.stardog.security.StardogAuthorizationException;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.authz.permission.WildcardPermission;
import org.apache.shiro.subject.Subject;

import java.util.Optional;

/**
 * Capability-policy orchestration — mirrors {@link UserFuelPolicy}'s
 * shape so the Phase 1b agent wires it into the invocation hot path
 * through the same code sites.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #preInvocation(FuelContext, Component, ExtensionManifest)}
 *       — instantiation-time. Delegates to
 *       {@link CapabilityPolicyResolver#resolve} and writes a single
 *       {@code GRANTED} row (or leaves the throw path to the resolver's
 *       {@link WfCapabilityError.LoadTimeDenied}).</li>
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
    public CapabilityGrant preInvocation(final FuelContext fuelCtx,
                                         final Component component,
                                         final ExtensionManifest manifest) {
        final Subject subject = currentSubjectOrNull();
        final String extensionUri = fuelCtx == null ? "" : fuelCtx.extensionUri();
        final CapabilityGrant grant = CapabilityPolicyResolver.resolve(
                extensionUri, component, manifest, subject);
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
     *       {@link WebFunctionCapability#forInvoke} against the current
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

        // 1. Interface + method policy.
        if (grant == null || !grant.allowsInterface(interfaceName)) {
            denyAndThrow(invoker, extensionUri, interfaceName, method, argsSummary,
                    WfCapabilityError.PerCallDenied.REASON_INTERFACE_DENIED);
        }
        if (!grant.allowsMethod(interfaceName, method)) {
            denyAndThrow(invoker, extensionUri, interfaceName, method, argsSummary,
                    WfCapabilityError.PerCallDenied.REASON_METHOD_DENIED);
        }

        // 2. HTTP host allowlist.
        if ("http-callbacks".equals(interfaceName)) {
            if (!grant.httpAllowlist().matches(argsSummary)) {
                denyAndThrow(invoker, extensionUri, interfaceName, method, argsSummary,
                        WfCapabilityError.PerCallDenied.REASON_HOST_DENIED);
            }
        }

        // 3. Shiro permission — collapse any Shiro/Stardog failure onto
        // permission-denied so a broken auth path doesn't leak an
        // ambient-credential path through to the guest.
        final String permString = WebFunctionCapability.forInvoke(interfaceName, method);
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
                    WfCapabilityError.PerCallDenied.REASON_PERMISSION_DENIED);
        }

        // Success — one GRANTED row per dispatch per strategy memo §9.
        CapabilityAttributionRing.recordGranted(
                invoker, extensionUri, interfaceName, method, argsSummary);
    }

    private static void denyAndThrow(final String invoker,
                                     final String extensionUri,
                                     final String interfaceName,
                                     final String method,
                                     final String argsSummary,
                                     final String reason) {
        CapabilityAttributionRing.recordDenied(
                invoker, extensionUri, interfaceName, method, argsSummary, reason);
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
