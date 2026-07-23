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

    /**
     * Untyped-wasm-trap fallback — wasmtime4j 46.0.1-1.4.7 wraps every
     * trap with a message that lacks the trap-type indicator. Under the
     * conditions "message shape is a wasm backtrace" AND "elapsed time
     * exceeded the deadline," the mapper promotes to DeadlineExceeded.
     */
    @Test
    public void untypedWasmTrapWithElapsedPastDeadlinePromotes() throws InterruptedException {
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "10");
        final CallbackContext ctx = CallbackContext.bind();
        try {
            // Elapse past the 10 ms deadline.
            Thread.sleep(30L);
            final RuntimeException untypedTrap = new RuntimeException(
                    "Runtime error: Function call failed: error while executing at wasm backtrace:\n"
                            + "    0:   0x89ca - example.wasm!some_func");
            final WfBudgetError mapped = FuelTrapMapper.mapOrNull(untypedTrap, ctx);
            assertThat(mapped).isInstanceOf(WfBudgetError.DeadlineExceeded.class);
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /**
     * Untyped-wasm-trap that fires BEFORE the deadline elapsed — the
     * fallback is guarded on elapsed time so we do not misclassify an
     * OOB or arithmetic trap that happens to look untyped.
     */
    @Test
    public void untypedWasmTrapBeforeDeadlineDoesNotPromote() {
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "10000");
        final CallbackContext ctx = CallbackContext.bind();
        try {
            final RuntimeException untypedTrap = new RuntimeException(
                    "Runtime error: Function call failed: error while executing at wasm backtrace:\n"
                            + "    0:   0x89ca - example.wasm!some_func");
            final WfBudgetError mapped = FuelTrapMapper.mapOrNull(untypedTrap, ctx);
            assertThat(mapped).isNull();
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /**
     * Untyped-wasm-trap with NO deadline configured — the fallback is
     * disabled since there is no plugin-side cap to compare elapsed
     * time against.
     */
    @Test
    public void untypedWasmTrapWithNoDeadlineDoesNotPromote() throws InterruptedException {
        System.clearProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS);
        final CallbackContext ctx = CallbackContext.bind();
        try {
            Thread.sleep(20L);
            final RuntimeException untypedTrap = new RuntimeException(
                    "Runtime error: Function call failed: error while executing at wasm backtrace:\n"
                            + "    0:   0x89ca - example.wasm!some_func");
            final WfBudgetError mapped = FuelTrapMapper.mapOrNull(untypedTrap, ctx);
            assertThat(mapped).isNull();
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /**
     * wasmtime4j 47.0.2-1.5.1+ typed-prefix path — the {@code [trap_code:10]}
     * discriminator is the ordinal for {@code TrapType.INTERRUPT}. Message
     * carries no human-readable interrupt substring and no wasm-backtrace
     * fallback shape, so promotion happens strictly through the numeric
     * dispatch added in Task 311. Deadline is set but elapsed time is
     * negligible — the fallback matchers would not fire on their own.
     */
    @Test
    public void interruptTrapWithTypedCodePrefixPromotesToDeadlineExceeded() {
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "60000");
        final CallbackContext ctx = CallbackContext.bind();
        try {
            // Real 1.5.1 wire shape from WasmtimeError::from_wasmtime_error:
            // "[trap_code:10]WebAssembly trap: <trap Display>". No
            // "interrupt" or "wasm trap: interrupt" substring — the
            // typed-code path is the only match.
            final WfBudgetError mapped = FuelTrapMapper.mapOrNull(
                    new RuntimeException(
                            "Runtime error: Function call trapped: "
                                    + "[trap_code:10]WebAssembly trap: (trap)"),
                    ctx);
            assertThat(mapped).isInstanceOf(WfBudgetError.DeadlineExceeded.class);
            final WfBudgetError.DeadlineExceeded de = (WfBudgetError.DeadlineExceeded) mapped;
            assertThat(de.deadlineMillis()).isEqualTo(60000L);
            assertThat(de.source()).isEqualTo(WfBudgetError.DeadlineExceeded.SOURCE_CONFIG);
            assertThat(de.errorCode()).isEqualTo("WF_DEADLINE_EXCEEDED");
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /**
     * Typed [trap_code:10] nested in the cause chain still promotes — the
     * mapper walks up to a bounded depth on the trap-code side just as it
     * does for the substring shapes.
     */
    @Test
    public void interruptTrapWithTypedCodeNestedInCauseChainStillPromotes() {
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "5000");
        final CallbackContext ctx = CallbackContext.bind();
        try {
            final Throwable root = new RuntimeException(
                    "[trap_code:10]WebAssembly trap: (trap)");
            final Throwable mid = new RuntimeException("wrapper", root);
            final Throwable outer = new RuntimeException("outer wrapper", mid);
            final WfBudgetError mapped = FuelTrapMapper.mapOrNull(outer, ctx);
            assertThat(mapped).isInstanceOf(WfBudgetError.DeadlineExceeded.class);
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }

    /**
     * Trap-code prefix for a non-INTERRUPT/non-OUT_OF_FUEL variant does NOT
     * promote — a real memory-out-of-bounds trap should keep its original
     * error path even when it happens to fire past a configured deadline.
     * Ordinal 1 = MEMORY_OUT_OF_BOUNDS.
     */
    @Test
    public void otherTypedTrapCodeDoesNotPromote() {
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "1000");
        final CallbackContext ctx = CallbackContext.bind();
        try {
            final WfBudgetError mapped = FuelTrapMapper.mapOrNull(
                    new RuntimeException(
                            "[trap_code:1]WebAssembly trap: memory access out of bounds"),
                    ctx);
            // The untyped-fallback needs "error while executing at wasm
            // backtrace" in the message — this string does not carry it,
            // so no promotion.
            assertThat(mapped).isNull();
        } finally {
            CallbackContext.unbindIfOutermost(ctx);
        }
    }
}
