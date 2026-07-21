package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.After;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link FuelTrapMapper}'s Task 303 T5 interrupt-trap
 * promotion branch. Verifies that a wasmtime {@code TrapType.INTERRUPT}
 * (epoch deadline reached; pure-compute wasm loop past
 * {@code webfunctions.exec.max.millis}) surfaces as
 * {@link WfBudgetError.DeadlineExceeded} with the config source tag.
 *
 * <p>Pure JVM — the mapper's string-shape path is exercised with a stub
 * exception. End-to-end coverage on a real wasmtime engine lives in
 * {@link TestEpochInterruption}.
 */
public class TestFuelTrapMapperInterrupt {

    @After
    public void resetSystemProperties() {
        System.clearProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS);
        System.clearProperty(WebFunctionConfig.PROP_FUEL_ENABLED);
        final CallbackContext ctx = CallbackContext.current();
        if (ctx != null) CallbackContext.unbindIfOutermost(ctx);
    }

    /**
     * Bracketed [INTERRUPT] prefix in the message — the fast-path shape
     * the wasmtime4j TrapException uses when the trap-type prefix embeds
     * in the wrapped message.
     */
    @Test
    public void interruptTrapWithBracketedPrefixPromotesToDeadlineExceeded() {
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "5000");
        final CallbackContext ctx = CallbackContext.bind();
        try {
            final WfBudgetError mapped = FuelTrapMapper.mapOrNull(
                    new RuntimeException("[INTERRUPT] wasm trap: interrupt"), ctx);
            assertThat(mapped).isInstanceOf(WfBudgetError.DeadlineExceeded.class);
            final WfBudgetError.DeadlineExceeded de = (WfBudgetError.DeadlineExceeded) mapped;
            assertThat(de.source()).isEqualTo(WfBudgetError.DeadlineExceeded.SOURCE_CONFIG);
            assertThat(de.deadlineMillis()).isEqualTo(5000L);
            assertThat(de.errorCode()).isEqualTo("WF_DEADLINE_EXCEEDED");
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /** Raw wasmtime message shape (no wasmtime4j prefix). */
    @Test
    public void interruptTrapWithRawWasmtimeShapePromotesToDeadlineExceeded() {
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "2500");
        final CallbackContext ctx = CallbackContext.bind();
        try {
            final WfBudgetError mapped = FuelTrapMapper.mapOrNull(
                    new RuntimeException("wasm trap: interrupt"), ctx);
            assertThat(mapped).isInstanceOf(WfBudgetError.DeadlineExceeded.class);
            assertThat(((WfBudgetError.DeadlineExceeded) mapped).deadlineMillis()).isEqualTo(2500L);
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /**
     * INTERRUPT promotion is independent of {@code webfunctions.fuel.enabled}
     * — the deadline surface is a plugin-side execution cap orthogonal to
     * commercial fuel metering.
     */
    @Test
    public void interruptPromotionIsIndependentOfFuelEnabled() {
        System.clearProperty(WebFunctionConfig.PROP_FUEL_ENABLED);
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "100");
        final CallbackContext ctx = CallbackContext.bind();
        try {
            final WfBudgetError mapped = FuelTrapMapper.mapOrNull(
                    new RuntimeException("[INTERRUPT] wasm trap"), ctx);
            assertThat(mapped).isInstanceOf(WfBudgetError.DeadlineExceeded.class);
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /** Nested cause — the mapper walks the cause chain up to a bounded depth. */
    @Test
    public void interruptTrapNestedInCauseChainStillPromotes() {
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "500");
        final CallbackContext ctx = CallbackContext.bind();
        try {
            final Throwable root = new RuntimeException("[INTERRUPT] wasm trap: interrupt");
            final Throwable mid  = new RuntimeException("wrapper", root);
            final Throwable outer = new RuntimeException("outer wrapper", mid);
            final WfBudgetError mapped = FuelTrapMapper.mapOrNull(outer, ctx);
            assertThat(mapped).isInstanceOf(WfBudgetError.DeadlineExceeded.class);
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /**
     * When both INTERRUPT and OUT_OF_FUEL shapes are present in the
     * cause chain, INTERRUPT takes priority (a store that has both fuel
     * and epoch enabled and traps on interrupt must not be misreported
     * as OUT_OF_FUEL). Fuel enabled + toll unset so the OUT_OF_FUEL
     * branch would otherwise produce PerInvocationTrap.
     */
    @Test
    public void interruptTakesPriorityOverOutOfFuel() {
        System.setProperty(WebFunctionConfig.PROP_FUEL_ENABLED, "true");
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "1000");
        final CallbackContext ctx = CallbackContext.bind();
        try {
            final WfBudgetError mapped = FuelTrapMapper.mapOrNull(
                    new RuntimeException(
                            "[INTERRUPT] wasm trap: OUT_OF_FUEL adjacent all fuel consumed"),
                    ctx);
            assertThat(mapped).isInstanceOf(WfBudgetError.DeadlineExceeded.class);
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /** Unrelated exception message — mapper returns null (no promotion). */
    @Test
    public void unrelatedExceptionDoesNotPromote() {
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "1000");
        final WfBudgetError mapped = FuelTrapMapper.mapOrNull(
                new RuntimeException("something else entirely"), null);
        assertThat(mapped).isNull();
    }
}
