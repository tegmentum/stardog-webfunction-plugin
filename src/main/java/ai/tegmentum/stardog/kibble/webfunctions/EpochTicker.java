package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.webassembly4j.api.Engine;
import ai.tegmentum.webassembly4j.api.capability.EpochController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Task 303 T5 — wasm-level deadline interruption ticker.
 *
 * <p>The cooperative deadline check that landed in Task 302 fires only when
 * a wasm frame re-enters a host callback (see
 * {@link HostCallbacks#enforceCapability enforceCapability}'s call to
 * {@link CallbackContext#checkDeadline(String)}). Pure-compute wasm loops
 * with no host re-entry are not interrupted by that path.
 *
 * <p>Wasmtime's epoch interruption closes that gap: the engine is created
 * with {@code epochInterruption(true)}, each component instance is
 * configured with a per-store epoch deadline (in ticks) via
 * {@link ai.tegmentum.webassembly4j.api.config.ComponentConfig#epochDeadline},
 * and this ticker fires {@link Engine#extension(Class)} → {@link
 * EpochController#incrementEpoch()} on a fixed cadence. When the store's
 * epoch counter exceeds the deadline, wasmtime traps at the next
 * safepoint with {@code TrapType.INTERRUPT}; {@link FuelTrapMapper}
 * promotes that trap to {@link WfBudgetError.DeadlineExceeded}.
 *
 * <p>Singleton. {@link #start(Engine, long)} is idempotent — a subsequent
 * call while the ticker is running is a no-op, and the first call
 * registers a JVM shutdown hook so the executor drains cleanly on
 * plugin shutdown. Providers without epoch support (engine's
 * {@link EpochController} extension returns empty) result in a warn-log
 * and a no-op start.
 */
public final class EpochTicker {

    private static final Logger LOG = LoggerFactory.getLogger(EpochTicker.class);
    private static final EpochTicker INSTANCE = new EpochTicker();

    private final Object lock = new Object();
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> tickerFuture;
    private long tickMillis;
    private boolean shutdownHookInstalled;

    private EpochTicker() {}

    public static EpochTicker instance() {
        return INSTANCE;
    }

    /**
     * True when the ticker is currently scheduled. Diagnostic accessor
     * for tests; not part of the plugin's runtime contract.
     */
    public boolean isRunning() {
        synchronized (lock) {
            return tickerFuture != null && !tickerFuture.isCancelled();
        }
    }

    /** Diagnostic accessor for tests — the interval the ticker is running at, or 0 when stopped. */
    public long tickMillis() {
        synchronized (lock) {
            return isRunning() ? tickMillis : 0L;
        }
    }

    /**
     * Idempotently start the ticker against the given engine at the given
     * cadence. First call resolves the engine's {@link EpochController}
     * extension, spins up a single-threaded daemon executor, schedules
     * {@code incrementEpoch()} at fixed rate, and registers a JVM
     * shutdown hook that stops the executor. Subsequent calls are no-ops
     * — the engine is a plugin-wide singleton, and re-scheduling would
     * double the tick rate. Callers that need to change the cadence
     * (unit tests) must {@link #stop()} first.
     *
     * <p>Providers without epoch support (engine returns empty
     * {@link EpochController}) log a warning and return without
     * scheduling — nothing to tick, and the epoch deadline set on
     * per-instance {@code ComponentConfig} is a no-op on that provider
     * per wasmtime4j-provider's guard clause
     * ({@code isEpochInterruptionEnabled}).
     *
     * @param engine          the plugin's shared wasm engine
     * @param intervalMillis  ticker cadence in milliseconds; caller has
     *                        already applied {@link WebFunctionConfig#epochTickMillis}'s
     *                        floor/default so no additional guard runs here
     */
    public void start(final Engine engine, final long intervalMillis) {
        if (engine == null) {
            LOG.warn("EpochTicker.start called with null engine — skipping");
            return;
        }
        synchronized (lock) {
            if (isRunning()) {
                return;
            }
            final Optional<EpochController> controllerOpt =
                    engine.extension(EpochController.class);
            if (controllerOpt.isEmpty()) {
                LOG.warn("EpochTicker: engine '{}' does not surface EpochController "
                        + "extension — wasm-level deadline interruption unavailable "
                        + "(cooperative check at host-callback boundaries still applies)",
                        engine.info().engineId());
                return;
            }
            final EpochController controller = controllerOpt.get();
            this.tickMillis = intervalMillis;
            this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread t = new Thread(r, "webfunctions-epoch-ticker");
                t.setDaemon(true);
                return t;
            });
            this.tickerFuture = this.executor.scheduleAtFixedRate(
                    () -> {
                        try {
                            controller.incrementEpoch();
                        } catch (RuntimeException e) {
                            // Never let the ticker die from a stray provider
                            // exception — that would silently disable wasm-level
                            // interruption for the plugin's lifetime.
                            LOG.warn("EpochTicker: incrementEpoch failed: {}", e.toString());
                        }
                    },
                    intervalMillis,
                    intervalMillis,
                    TimeUnit.MILLISECONDS);
            installShutdownHookOnce();
            LOG.info("EpochTicker: started at {} ms interval "
                    + "(wasm-level deadline interruption active)", intervalMillis);
        }
    }

    /**
     * Stop the ticker and release its executor. Idempotent — safe to call
     * repeatedly and from the JVM shutdown hook installed on first
     * {@link #start(Engine, long)}. Unit tests that reconfigure the tick
     * interval call this between phases.
     */
    public void stop() {
        synchronized (lock) {
            if (tickerFuture != null) {
                tickerFuture.cancel(false);
                tickerFuture = null;
            }
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            tickMillis = 0L;
        }
    }

    /** JVM shutdown hook — installed once on first successful start. */
    private void installShutdownHookOnce() {
        if (shutdownHookInstalled) return;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "webfunctions-epoch-ticker-shutdown"));
        shutdownHookInstalled = true;
    }
}
