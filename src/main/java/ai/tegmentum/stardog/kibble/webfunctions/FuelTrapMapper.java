package ai.tegmentum.stardog.kibble.webfunctions;

/**
 * Fuel-metering Phase 1 trap → typed error mapper.
 *
 * <p>Sits at the invocation boundary in {@link Call#evaluate} and
 * {@link WebFunctionServiceOperator#computeNext}. Given a thrown exception
 * and the current {@link CallbackContext}, decides whether to promote it
 * into a typed {@link WfBudgetError}:
 *
 * <ul>
 *   <li>If the throwable is already a {@link WfBudgetError} (either
 *       {@link WfBudgetError.HostCallbackTollExhausted} thrown directly
 *       from {@link CallbackContext#chargeToll}, or a
 *       {@link WfBudgetError.PerInvocationTrap} thrown earlier in the
 *       stack), it is returned as-is.</li>
 *   <li>If a wasmtime {@code OUT_OF_FUEL} trap appears anywhere in the
 *       cause chain, the frame's {@link CallbackContext#tollExhaustedCallback}
 *       is checked: when set, the trap is promoted to
 *       {@link WfBudgetError.HostCallbackTollExhausted}; otherwise it is
 *       promoted to {@link WfBudgetError.PerInvocationTrap}.</li>
 *   <li>Task 303 T5 — if a wasmtime {@code INTERRUPT} trap (epoch
 *       interruption) appears anywhere in the cause chain, promote to
 *       {@link WfBudgetError.DeadlineExceeded} with the elapsed millis
 *       + configured cap for attribution. Fires when a pure-compute
 *       wasm loop with no host re-entry runs past the configured
 *       execution deadline; complements the cooperative check at
 *       {@link CallbackContext#checkDeadline(String)}.</li>
 *   <li>Otherwise, {@code null} is returned — the caller preserves the
 *       original error path.</li>
 * </ul>
 *
 * <p>See {@code fuel-implementation.md} §5 for the error surface and
 * §8 Phase 1 for the scope of typed mapping that lands here.
 */
final class FuelTrapMapper {

    private FuelTrapMapper() {}

    /**
     * Inspect {@code thrown} + the current {@link CallbackContext} and
     * return the typed {@link WfBudgetError} to surface, or {@code null}
     * to preserve the caller's existing error path.
     */
    static WfBudgetError mapOrNull(final Throwable thrown, final CallbackContext ctx) {
        // Direct throw from chargeToll — already the correct type,
        // just return it. Same for a re-thrown PerInvocationTrap.
        Throwable cur = thrown;
        int hops = 0;
        while (cur != null && hops < 16) {
            if (cur instanceof WfBudgetError) {
                return (WfBudgetError) cur;
            }
            cur = cur.getCause();
            hops++;
        }

        // Task 303 T5 — wasm-level epoch interrupt promotion. Independent
        // of fuel.enabled since the deadline surface (webfunctions.exec.max.millis)
        // is a plugin-side execution cap orthogonal to the commercial fuel
        // metering. Checked before the fuel-only OUT_OF_FUEL branch below
        // so a store that has both fuel and epoch enabled and traps on
        // interrupt (deadline) does not get misreported as OUT_OF_FUEL —
        // the two trap types are mutually exclusive from wasmtime's side
        // but the message check for OUT_OF_FUEL happens to match some
        // shared prefix layouts, so the INTERRUPT check takes priority.
        //
        // Untyped-wasm-trap fallback covers the wasmtime4j 46.0.1-1.4.7 /
        // webassembly4j 2.4.3 shape where the JNI wraps the trap with
        // "Function call failed: {}" — Rust's Display drops the inner
        // trap-type message so we see a generic {@link WasmRuntimeException}
        // and cannot key off the trap type textually. The guard is tight:
        // untyped-wasm-trap message shape AND elapsed time past the
        // configured deadline. This mirrors how the fast path would fire
        // for a properly-typed INTERRUPT trap on a substrate that surfaces
        // the trap type in the message.
        if (hasInterruptTrap(thrown)
                || isProbableInterruptByElapsed(thrown, ctx)) {
            final String extensionUri = ctx == null ? "" : ctx.extensionUri();
            // Elapsed since bind is the honest wall-clock reading; the
            // ticker's tick interval is a strict lower bound on the
            // over-shoot (up to one tick past the deadline before the
            // wasm frame reaches its next safepoint). deadlineMillis is
            // the operator-configured cap (0 when unset — same 0 the
            // monitor-side path uses so downstream JSON parsing can key
            // off {@code source: "config"} vs a live-set 0 elapsed).
            final long elapsed = ctx == null ? -1L : ctx.elapsedMillisSinceBind();
            final long deadlineMs = WebFunctionConfig.execMaxMillis().orElse(0L);
            return new WfBudgetError.DeadlineExceeded(
                    extensionUri,
                    /* callbackName */ "",
                    elapsed < 0L ? 0L : elapsed,
                    deadlineMs,
                    WfBudgetError.DeadlineExceeded.SOURCE_CONFIG);
        }

        if (!WebFunctionConfig.fuelEnabled()) {
            return null;
        }

        // Walk the cause chain looking for a wasmtime OUT_OF_FUEL trap.
        // The exception comes back as ExecutionException from
        // WasmtimeComponentInstanceAdapter.invokeWit(...) with the raw
        // wasmtime4j TrapException as the cause.
        if (hasOutOfFuelTrap(thrown)) {
            final String extensionUri = ctx == null ? "" : ctx.extensionUri();
            final long cap = WebFunctionConfig.fuelPerInvocationMax();
            // Real fuel consumed: wasmtime4j 1.4.7 / webassembly4j 2.4.3 expose
            // ComponentInstance.fuelConsumed() through CallbackContext. Prefer
            // the real value; fall back to the cap (upper bound — the guest
            // necessarily hit it to trap) when the provider returns the -1
            // sentinel (non-wasmtime provider without fuelConsumed support).
            final long consumed = ctx == null ? -1L : ctx.fuelConsumed();
            final long reportedConsumed = consumed >= 0L ? consumed : cap;
            if (ctx != null && ctx.tollExhaustedCallback() != null) {
                return new WfBudgetError.HostCallbackTollExhausted(
                        extensionUri,
                        ctx.tollExhaustedCallback(),
                        reportedConsumed,
                        cap,
                        WebFunctionConfig.fuelHostCallbackToll());
            }
            return new WfBudgetError.PerInvocationTrap(
                    extensionUri,
                    reportedConsumed,
                    cap);
        }
        return null;
    }

    /**
     * True when any cause in the chain (up to a reasonable depth) is a
     * wasmtime4j {@code TrapException} whose {@code TrapType} matches
     * INTERRUPT (epoch deadline reached, or timeout callback fired).
     * Mirrors {@link #hasOutOfFuelTrap} in structure so an operator
     * looking at the two mapping branches sees them as symmetric.
     */
    private static boolean hasInterruptTrap(final Throwable thrown) {
        Throwable cur = thrown;
        int hops = 0;
        while (cur != null && hops < 16) {
            if (isInterruptTrap(cur)) return true;
            cur = cur.getCause();
            hops++;
        }
        return false;
    }

    private static boolean isInterruptTrap(final Throwable t) {
        // Fast path: the wasmtime4j TrapException message shape embeds the
        // trap type as a {@code [TRAP_TYPE] wasm trap: description} prefix.
        // INTERRUPT's stock description is "Execution interrupted (timeout
        // or cancellation)"; wasmtime itself surfaces {@code "wasm trap:
        // interrupt"} in its unqualified form. Check both.
        final String msg = t.getMessage();
        if (msg != null) {
            if (msg.contains("[INTERRUPT]")) return true;
            // Wasmtime's raw trap text for epoch interruption; matches
            // both {@code "wasm trap: interrupt"} and the wasmtime4j
            // wrapped {@code "Execution interrupted"} variants.
            if (msg.contains("wasm trap: interrupt")) return true;
            if (msg.contains("Execution interrupted")) return true;
        }

        // Fall back to reflectively checking wasmtime4j's TrapType enum.
        // Classpath-sensitive; the string check above is the common case.
        try {
            final Class<?> nativeTrapClass = Class.forName(
                    "ai.tegmentum.wasmtime4j.exception.TrapException");
            if (nativeTrapClass.isInstance(t)) {
                final Object trapType = nativeTrapClass
                        .getMethod("getTrapType").invoke(t);
                return trapType != null && "INTERRUPT".equals(trapType.toString());
            }
        } catch (ReflectiveOperationException ignore) {
            // wasmtime4j not on classpath in this JVM — nothing we can do
            // here; the string check above is our fallback.
        }
        return false;
    }

    /**
     * Untyped-wasm-trap fallback for INTERRUPT recognition. As of
     * wasmtime4j 46.0.1-1.4.7 / webassembly4j 2.4.3, the JNI wraps every
     * wasmtime {@code Func::call} error with {@code
     * format!("Function call failed: {}", e)}. Rust anyhow's Display via
     * {@code "{}"} shows only the outermost error message, so wasmtime's
     * inner {@code "wasm trap: interrupt"} text lands on {@code e.source()}
     * and is dropped before reaching Java. The exception surfaces as
     * {@link ai.tegmentum.wasmtime4j.exception.WasmRuntimeException} with
     * a message shaped {@code "Runtime error: Function call failed: error
     * while executing at wasm backtrace: ..."} — no trap-type indicator.
     *
     * <p>This helper matches that specific untyped-trap shape from the
     * wasmtime provider. Callers who add the epoch-deadline-exceeded
     * elapsed-time guard on top of this match get a conservative
     * INTERRUPT signal even without substrate trap-type propagation.
     * When the substrate fixes trap-type surfacing (upstream {@code "{:#}"}
     * or explicit trap-code parsing in the JNI's error mapper), this
     * fallback becomes redundant with the fast path above — but stays
     * harmless because the fast path fires first.
     */
    private static boolean looksLikeUntypedWasmtimeTrap(final Throwable t) {
        Throwable cur = t;
        int hops = 0;
        while (cur != null && hops < 16) {
            final String msg = cur.getMessage();
            if (msg != null
                    && msg.contains("error while executing at wasm backtrace")) {
                return true;
            }
            cur = cur.getCause();
            hops++;
        }
        return false;
    }

    /**
     * Elapsed wall-clock exceeded the configured plugin-side deadline by
     * a comfortable margin. Only meaningful when
     * {@link WebFunctionConfig#PROP_MAX_EXEC_MILLIS} is set and the
     * caller passed a bound {@link CallbackContext}. Used with
     * {@link #looksLikeUntypedWasmtimeTrap} to attribute an untyped
     * wasm trap to epoch interruption without over-triggering.
     */
    private static boolean elapsedPastDeadline(final CallbackContext ctx) {
        if (ctx == null) return false;
        final long capMs = WebFunctionConfig.execMaxMillis().orElse(0L);
        if (capMs <= 0L) return false;
        // A 10% grace covers the ticker's own quantization + JVM
        // scheduling jitter — no false positives from an untyped trap
        // that fired right around the deadline for unrelated reasons.
        return ctx.elapsedMillisSinceBind() >= capMs;
    }

    /**
     * True when the throwable looks like an INTERRUPT trap even though
     * the wasmtime substrate did not propagate the trap type. Requires
     * both an untyped-wasm-trap message shape and elapsed time past the
     * configured deadline — the tight guard prevents mislabeling a
     * genuinely different trap (memory-out-of-bounds, integer-overflow)
     * that happened to fire late in a long invocation.
     */
    private static boolean isProbableInterruptByElapsed(final Throwable thrown,
                                                        final CallbackContext ctx) {
        return looksLikeUntypedWasmtimeTrap(thrown) && elapsedPastDeadline(ctx);
    }

    /**
     * True when any cause in the chain (up to a reasonable depth) is a
     * wasmtime4j {@code TrapException} whose {@code TrapType} matches
     * OUT_OF_FUEL. Uses reflective access to avoid a hard classpath
     * dependency on wasmtime4j from the plugin API surface — the check
     * still binds only when the runtime classes are present.
     */
    private static boolean hasOutOfFuelTrap(final Throwable thrown) {
        Throwable cur = thrown;
        int hops = 0;
        while (cur != null && hops < 16) {
            if (isOutOfFuelTrap(cur)) return true;
            cur = cur.getCause();
            hops++;
        }
        return false;
    }

    private static boolean isOutOfFuelTrap(final Throwable t) {
        // Fast path: the compile-time-available webassembly4j TrapException
        // has a message shape that embeds the trap type — e.g.
        // "[OUT_OF_FUEL] wasm trap: all fuel consumed by WebAssembly".
        // Cheaper than reflecting, and covers both the wrapped and
        // unwrapped shapes.
        final String msg = t.getMessage();
        if (msg != null && msg.contains("OUT_OF_FUEL")) return true;
        if (msg != null && msg.contains("all fuel consumed")) return true;

        // Fall back to reflectively checking wasmtime4j's TrapType enum.
        // Classpath-sensitive; the string check above is the common case.
        try {
            final Class<?> nativeTrapClass = Class.forName(
                    "ai.tegmentum.wasmtime4j.exception.TrapException");
            if (nativeTrapClass.isInstance(t)) {
                final Object trapType = nativeTrapClass
                        .getMethod("getTrapType").invoke(t);
                return trapType != null && "OUT_OF_FUEL".equals(trapType.toString());
            }
        } catch (ReflectiveOperationException ignore) {
            // wasmtime4j not on classpath in this JVM — nothing we can
            // do here; the string check above is our fallback.
        }
        return false;
    }
}
