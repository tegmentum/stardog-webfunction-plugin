package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.After;
import org.junit.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Deadline propagation coverage — the plugin-side execution deadline
 * captured on {@link CallbackContext} at bind time and enforced at every
 * host-callback dispatch chokepoint (see {@link HostCallbacks#enforceCapability
 * enforceCapability}'s call to {@link CallbackContext#checkDeadline(String)
 * checkDeadline}).
 *
 * <p>Pure-JVM: no wasm engine, no Stardog boot. The point of the plumbing
 * is a data-shape contract on CallbackContext and a call-order contract on
 * the enforceCapability chokepoint; both are testable through the public
 * surface without booting a query.
 */
public class TestCallbackDeadline {

    @After
    public void tearDown() {
        final CallbackContext ctx = CallbackContext.current();
        if (ctx != null) {
            CallbackContext.unbindIfOutermost(ctx);
        }
        System.clearProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS);
    }

    /** No {@link WebFunctionConfig#PROP_MAX_EXEC_MILLIS} set => no deadline. */
    @Test
    public void deadlineAbsentWhenPropertyUnset() {
        System.clearProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS);
        final CallbackContext ctx = CallbackContext.bind();
        assertThat(ctx.deadlineMillisIfConfigured()).isEqualTo(OptionalLong.empty());
        // checkDeadline is a no-op when no source is configured; call
        // shouldn't throw.
        ctx.checkDeadline("host.execute-query");
    }

    /**
     * Configured cap → {@link CallbackContext#deadlineMillisIfConfigured}
     * reports the value; checkDeadline stays quiet until the wall-clock
     * elapsed exceeds the cap.
     */
    @Test
    public void deadlineCapturedAndHonored() throws InterruptedException {
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "50");
        final CallbackContext ctx = CallbackContext.bind();
        assertThat(ctx.deadlineMillisIfConfigured()).isEqualTo(OptionalLong.of(50L));

        // Immediately after bind — no trip.
        ctx.checkDeadline("host.execute-query");

        // Sleep past the cap, verify checkDeadline trips.
        Thread.sleep(80L);
        final Throwable t = catchThrowable(() -> ctx.checkDeadline("host.execute-query"));
        assertThat(t).isInstanceOf(WfBudgetError.DeadlineExceeded.class);
        final WfBudgetError.DeadlineExceeded de = (WfBudgetError.DeadlineExceeded) t;
        assertThat(de.source()).isEqualTo(WfBudgetError.DeadlineExceeded.SOURCE_CONFIG);
        assertThat(de.callbackName()).isEqualTo("host.execute-query");
        assertThat(de.deadlineMillis()).isEqualTo(50L);
        assertThat(de.elapsedMillis()).isGreaterThanOrEqualTo(50L);
        assertThat(de.errorCode()).isEqualTo("WF_DEADLINE_EXCEEDED");
    }

    /**
     * Deadline is captured at {@link CallbackContext#bind()} time — a later
     * {@link System#setProperty} does not retroactively change an already-
     * bound context. Nested dispatches therefore inherit the outer
     * deadline through the shared ThreadLocal.
     */
    @Test
    public void deadlineFrozenAtBindTime() {
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "100");
        final CallbackContext ctx = CallbackContext.bind();
        assertThat(ctx.deadlineMillisIfConfigured()).isEqualTo(OptionalLong.of(100L));

        // Change the property after bind — the frozen stamp should not
        // update. Setting to 1ms would otherwise force an immediate trip
        // if the stamp were live-read.
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "1");
        ctx.checkDeadline("host.execute-query"); // no trip
        assertThat(ctx.deadlineMillisIfConfigured()).isEqualTo(OptionalLong.of(100L));
    }

    /** JSON payload shape sanity — the error-code + source discriminator
     *  must appear so downstream SPARQL Results JSON error parsing can key
     *  off them. */
    @Test
    public void deadlineErrorJsonPayloadIsMachineParseable() {
        final WfBudgetError.DeadlineExceeded de = new WfBudgetError.DeadlineExceeded(
                "ipfs://Q123",
                "graph-callbacks.execute-query",
                175L,
                100L,
                WfBudgetError.DeadlineExceeded.SOURCE_CONFIG);
        final String json = de.jsonPayload();
        assertThat(json)
                .contains("\"error_code\":\"WF_DEADLINE_EXCEEDED\"")
                .contains("\"extension\":\"ipfs://Q123\"")
                .contains("\"callback_name\":\"graph-callbacks.execute-query\"")
                .contains("\"elapsed_millis\":175")
                .contains("\"deadline_millis\":100")
                .contains("\"source\":\"config\"");
    }

    /**
     * DeadlineExceeded is a WfBudgetError — the sealed hierarchy lets the
     * outer catch in Call.evaluate / WebFunctionServiceOperator.computeNext
     * dispatch through the same {@code catch (WfBudgetError e)} arm as
     * PerInvocationTrap / HostCallbackTollExhausted.
     */
    @Test
    public void deadlineExceededIsAWfBudgetError() {
        final WfBudgetError.DeadlineExceeded de = new WfBudgetError.DeadlineExceeded(
                "", "", 0L, 0L, WfBudgetError.DeadlineExceeded.SOURCE_MONITOR);
        assertThat((WfBudgetError) de).isInstanceOf(WfBudgetError.class);
    }
}
