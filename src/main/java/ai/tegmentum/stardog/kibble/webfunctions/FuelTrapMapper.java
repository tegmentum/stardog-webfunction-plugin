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
            // sentinel (module mode, non-wasmtime provider).
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
