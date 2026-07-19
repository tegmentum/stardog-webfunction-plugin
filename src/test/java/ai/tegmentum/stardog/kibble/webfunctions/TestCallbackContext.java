package ai.tegmentum.stardog.kibble.webfunctions;

import org.apache.shiro.authz.Permission;
import org.apache.shiro.subject.Subject;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capability wave Phase 4a — verifies the {@link CallbackContext#invokerSubject}
 * setter / getter roundtrip that {@link StardogWasmInstance} populates at
 * instantiation and {@link HostCallbacks} consults on every host-callback
 * dispatch when wrapping Stardog operations in
 * {@code ShiroUtils.executeAs(subject, ...)}.
 *
 * <p>Bypasses the wasm dispatch stack — the point of the invokerSubject
 * plumbing is a data-shape contract on CallbackContext, so this fixture
 * proves the shape without needing a live wasm engine.
 */
public class TestCallbackContext {

    @After
    public void tearDown() {
        // CallbackContext.bind uses a ThreadLocal — clear so cross-test
        // pollution can't leak into the next fixture.
        final CallbackContext ctx = CallbackContext.current();
        if (ctx != null) {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    @Test
    public void invokerSubjectDefaultsToEmpty() {
        final CallbackContext ctx = CallbackContext.bind();
        assertThat(ctx.invokerSubject()).isEmpty();
    }

    @Test
    public void invokerSubjectSetterRoundTrips() {
        final CallbackContext ctx = CallbackContext.bind();
        final Subject alice = stubSubject("alice");
        ctx.setInvokerSubject(alice);
        final Optional<Subject> back = ctx.invokerSubject();
        assertThat(back).isPresent();
        assertThat(back.get()).isSameAs(alice);
    }

    @Test
    public void invokerSubjectNullClears() {
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setInvokerSubject(stubSubject("bob"));
        assertThat(ctx.invokerSubject()).isPresent();
        ctx.setInvokerSubject(null);
        assertThat(ctx.invokerSubject()).isEmpty();
    }

    /**
     * Java-Proxy Subject stub — the invokerSubject slot on CallbackContext
     * is just a reference holder, so the proxy only needs to answer
     * {@code getPrincipal} for equality-like assertions.
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
