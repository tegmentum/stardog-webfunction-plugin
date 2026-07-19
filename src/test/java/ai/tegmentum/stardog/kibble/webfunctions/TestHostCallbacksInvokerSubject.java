package ai.tegmentum.stardog.kibble.webfunctions;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.Permission;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Capability wave Phase 4c — verifies {@link HostCallbacks#executeAsInvoker}
 * wraps Stardog-touching bodies in {@code ShiroUtils.executeAs} when
 * capability enforcement is active, falls through when it isn't, and
 * respects {@code webfunctions.capability.anonymous-policy} for the
 * no-invoker case.
 *
 * <p>Bypasses the wasm dispatch stack: {@code executeAsInvoker} is
 * package-private so tests drive the wrap with synthetic Supplier bodies.
 * ShiroUtils.executeAs uses {@code ThreadContext.bind()} to swap the
 * subject for the duration of the body — the body's read of
 * {@link SecurityUtils#getSubject} therefore returns the passed subject,
 * which is what these tests observe.
 */
public class TestHostCallbacksInvokerSubject {

    private String prevAnonPolicy;

    @Before
    public void setUp() {
        // Ensure a clean enforcer + audit ring for tests that enable it.
        CapabilityAttributionRing.INSTANCE.clear();
        CapabilityAttributionRing.INSTANCE.setEnabled(false);
        CapabilityEnforcer.install(null);
        CapabilityEnforcer.setEnabled(false);
        prevAnonPolicy = System.getProperty(WebFunctionConfig.PROP_CAPABILITY_ANONYMOUS_POLICY);
        System.clearProperty(WebFunctionConfig.PROP_CAPABILITY_ANONYMOUS_POLICY);
        ThreadContext.unbindSubject();
        CapabilityPolicyResolver.setAnonymousPolicy(null);
    }

    @After
    public void tearDown() {
        CapabilityEnforcer.install(null);
        CapabilityEnforcer.setEnabled(false);
        CapabilityAttributionRing.INSTANCE.clear();
        ThreadContext.unbindSubject();
        if (prevAnonPolicy == null) {
            System.clearProperty(WebFunctionConfig.PROP_CAPABILITY_ANONYMOUS_POLICY);
        } else {
            System.setProperty(WebFunctionConfig.PROP_CAPABILITY_ANONYMOUS_POLICY, prevAnonPolicy);
        }
        CapabilityPolicyResolver.setAnonymousPolicy(null);
        final CallbackContext ctx = CallbackContext.current();
        if (ctx != null) CallbackContext.unbindIfOutermost(ctx);
    }

    @Test
    public void capabilityDisabledSkipsWrapEvenWithoutSubject() {
        // Master gate off — no ShiroUtils.executeAs call fires and the
        // body runs directly. Anonymous invoker on the thread stays
        // anonymous inside the body (pre-Phase-4 behavior preserved).
        assertThat(CapabilityEnforcer.activePolicy()).isEmpty();
        final CallbackContext ctx = CallbackContext.bind();
        final AtomicInteger callCount = new AtomicInteger();
        final Object[] out = HostCallbacks.executeAsInvoker(
                ctx, "graph-callbacks", "execute-query", "", () -> {
                    callCount.incrementAndGet();
                    return new Object[] { "ok" };
                });
        assertThat(out).containsExactly("ok");
        assertThat(callCount).hasValue(1);
    }

    @Test
    public void capabilityEnabledWithInvokerRunsBodyUnderExecuteAs() {
        // Enable capability + stamp an invoker subject; the body's
        // SecurityUtils.getSubject() must equal that stamped subject —
        // that's the observable proof ShiroUtils.executeAs fired.
        CapabilityEnforcer.install(CapabilityEnforcer.create());
        CapabilityEnforcer.setEnabled(true);
        final CallbackContext ctx = CallbackContext.bind();
        final Subject alice = stubSubject("alice");
        ctx.setInvokerSubject(alice);

        final AtomicReference<Subject> observed = new AtomicReference<>();
        final Object[] out = HostCallbacks.executeAsInvoker(
                ctx, "graph-callbacks", "execute-query", "", () -> {
                    observed.set(SecurityUtils.getSubject());
                    return new Object[] { "ran" };
                });

        assertThat(out).containsExactly("ran");
        // ShiroUtils.executeAs wraps the subject via withoutLogout(...) —
        // a NoLogoutSubject decorator — before binding to ThreadContext.
        // Assert via getPrincipal() rather than reference equality so the
        // decorator is transparent.
        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().getPrincipal()).isEqualTo("alice");
    }

    @Test
    public void capabilityEnabledAnonymousDenyThrowsPerCallDenied() {
        // Enable capability, no invoker subject on ctx, anonymous-policy=deny.
        CapabilityEnforcer.install(CapabilityEnforcer.create());
        CapabilityEnforcer.setEnabled(true);
        System.setProperty(WebFunctionConfig.PROP_CAPABILITY_ANONYMOUS_POLICY, "deny");
        final CallbackContext ctx = CallbackContext.bind();
        assertThat(ctx.invokerSubject()).isEmpty();

        final AtomicInteger callCount = new AtomicInteger();
        final Throwable thrown = catchThrowable(() -> HostCallbacks.executeAsInvoker(
                ctx, "graph-callbacks", "execute-query", "SELECT * WHERE {}", () -> {
                    callCount.incrementAndGet();
                    return new Object[] { "should-not-run" };
                }));

        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        final WfCapabilityError.PerCallDenied err = (WfCapabilityError.PerCallDenied) thrown;
        assertThat(err.reason()).isEqualTo(
                WfCapabilityError.PerCallDenied.REASON_PERMISSION_DENIED);
        assertThat(err.interfaceName()).isEqualTo("graph-callbacks");
        assertThat(err.method()).isEqualTo("execute-query");
        assertThat(err.argumentsSummary()).contains("anonymous invoker not permitted");
        assertThat(callCount).hasValue(0);
    }

    @Test
    public void capabilityEnabledAnonymousPermitFallsThroughToBody() {
        // Enable capability, no invoker subject, anonymous-policy=permit
        // → body runs, no ShiroUtils.executeAs fires (invoker absent).
        CapabilityEnforcer.install(CapabilityEnforcer.create());
        CapabilityEnforcer.setEnabled(true);
        System.setProperty(WebFunctionConfig.PROP_CAPABILITY_ANONYMOUS_POLICY, "permit");
        final CallbackContext ctx = CallbackContext.bind();
        assertThat(ctx.invokerSubject()).isEmpty();

        final AtomicInteger callCount = new AtomicInteger();
        final Object[] out = HostCallbacks.executeAsInvoker(
                ctx, "graph-callbacks", "execute-query", "", () -> {
                    callCount.incrementAndGet();
                    return new Object[] { "fallthrough" };
                });
        assertThat(out).containsExactly("fallthrough");
        assertThat(callCount).hasValue(1);
    }

    @Test
    public void capabilityEnabledAnonymousInheritFallsThroughToBody() {
        CapabilityEnforcer.install(CapabilityEnforcer.create());
        CapabilityEnforcer.setEnabled(true);
        System.setProperty(WebFunctionConfig.PROP_CAPABILITY_ANONYMOUS_POLICY, "inherit");
        final CallbackContext ctx = CallbackContext.bind();

        final AtomicInteger callCount = new AtomicInteger();
        final Object[] out = HostCallbacks.executeAsInvoker(
                ctx, "graph-callbacks", "execute-update", "", () -> {
                    callCount.incrementAndGet();
                    return new Object[] { "inherited" };
                });
        assertThat(out).containsExactly("inherited");
        assertThat(callCount).hasValue(1);
    }

    @Test
    public void capabilityEnabledNullCtxFallsThroughOnPermit() {
        // Defensive path: null ctx + capability on + permit policy →
        // body runs. Direct-instantiation code paths that never bound
        // a CallbackContext still work under permit policy.
        CapabilityEnforcer.install(CapabilityEnforcer.create());
        CapabilityEnforcer.setEnabled(true);
        System.setProperty(WebFunctionConfig.PROP_CAPABILITY_ANONYMOUS_POLICY, "permit");

        final AtomicInteger callCount = new AtomicInteger();
        final Object[] out = HostCallbacks.executeAsInvoker(
                null, "graph-callbacks", "execute-query", "", () -> {
                    callCount.incrementAndGet();
                    return new Object[] { "ran" };
                });
        assertThat(out).containsExactly("ran");
        assertThat(callCount).hasValue(1);
    }

    /**
     * Java-Proxy Subject stub sufficient for {@link ShiroUtils#executeAs}
     * plus a body that reads {@link SecurityUtils#getSubject} — Shiro's
     * executeAs uses {@code ThreadContext.bind(subject)}, so during the
     * body the SecurityUtils.getSubject() call returns exactly this
     * instance.
     */
    private static Subject stubSubject(final String principal) {
        return (Subject) Proxy.newProxyInstance(
                Subject.class.getClassLoader(),
                new Class[]{Subject.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getPrincipal":    return principal;
                        case "isAuthenticated": return true;
                        case "isRemembered":    return false;
                        case "checkPermission": return null;
                        case "isPermitted":
                            if (args != null && args.length == 1 && args[0] instanceof Permission) {
                                return Boolean.TRUE;
                            }
                            return Boolean.TRUE;
                        default:
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            if (method.getReturnType() == long.class) return 0L;
                            return null;
                    }
                });
    }
}
