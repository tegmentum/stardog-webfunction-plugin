package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.webassembly4j.api.Engine;
import ai.tegmentum.webassembly4j.api.EngineCapabilities;
import ai.tegmentum.webassembly4j.api.EngineInfo;
import ai.tegmentum.webassembly4j.api.capability.EpochController;
import ai.tegmentum.webassembly4j.api.Component;
import ai.tegmentum.webassembly4j.api.Module;
import org.junit.After;
import org.junit.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EpochTicker} — verifies idempotent start/stop and
 * that a stub {@link EpochController} sees {@code incrementEpoch} calls
 * on the expected cadence.
 *
 * <p>Pure JVM — the ticker is engine-agnostic once it has resolved the
 * {@link EpochController} extension, so a synchronous fake engine covers
 * the timing contract without booting wasmtime.
 */
public class TestEpochTicker {

    @After
    public void resetTicker() {
        EpochTicker.instance().stop();
    }

    @Test
    public void startIsIdempotent() {
        final FakeEngine engine = new FakeEngine();
        final EpochTicker ticker = EpochTicker.instance();
        ticker.start(engine, 50L);
        assertThat(ticker.isRunning()).isTrue();
        assertThat(ticker.tickMillis()).isEqualTo(50L);

        // Second start at a different cadence is a no-op — first-write-wins.
        ticker.start(engine, 10L);
        assertThat(ticker.tickMillis()).isEqualTo(50L);

        ticker.stop();
        assertThat(ticker.isRunning()).isFalse();
    }

    @Test
    public void stopIsIdempotent() {
        final EpochTicker ticker = EpochTicker.instance();
        ticker.stop();
        ticker.stop();
        assertThat(ticker.isRunning()).isFalse();
    }

    @Test
    public void tickerFiresIncrementEpochOnCadence() throws InterruptedException {
        final FakeEngine engine = new FakeEngine();
        final EpochTicker ticker = EpochTicker.instance();
        ticker.start(engine, 25L);
        try {
            // ~120 ms should see ~4 ticks; assert conservatively (at least 2)
            // to keep the timing tolerance loose enough for CI jitter.
            Thread.sleep(120L);
            assertThat(engine.controller.count.get()).isGreaterThanOrEqualTo(2);
        } finally {
            ticker.stop();
        }
    }

    @Test
    public void providerWithoutEpochControllerDoesNotStart() {
        final Engine engine = new FakeEngine(false);
        final EpochTicker ticker = EpochTicker.instance();
        ticker.start(engine, 10L);
        assertThat(ticker.isRunning()).isFalse();
    }

    @Test
    public void startWithNullEngineIsSafe() {
        final EpochTicker ticker = EpochTicker.instance();
        ticker.start(null, 10L);
        assertThat(ticker.isRunning()).isFalse();
    }

    /** Fake engine that surfaces (or hides) an EpochController. */
    private static final class FakeEngine implements Engine {
        final CountingController controller = new CountingController();
        private final boolean hasEpoch;

        FakeEngine() { this(true); }
        FakeEngine(final boolean hasEpoch) { this.hasEpoch = hasEpoch; }

        @Override public EngineInfo info() {
            return new EngineInfo() {
                @Override public String engineId() { return "fake"; }
                @Override public String providerId() { return "fake"; }
                @Override public String providerVersion() { return "0"; }
                @Override public String engineVersion() { return "0"; }
                @Override public int minimumJavaVersion() { return 21; }
            };
        }
        @Override public EngineCapabilities capabilities() {
            return new EngineCapabilities() {
                @Override public boolean supportsCoreModules() { return false; }
                @Override public boolean supportsComponents() { return true; }
                @Override public boolean supportsWasi() { return false; }
                @Override public boolean supportsFuel() { return false; }
                @Override public boolean supportsEpochInterruption() { return hasEpoch; }
                @Override public boolean supportsThreads() { return false; }
                @Override public boolean supportsGc() { return false; }
                @Override public boolean supportsReferenceTypes() { return false; }
                @Override public boolean supportsMultiMemory() { return false; }
                @Override public boolean supportsNativeInterop() { return false; }
            };
        }
        @Override public Module loadModule(byte[] bytes) { throw new UnsupportedOperationException(); }
        @Override public Component loadComponent(byte[] bytes) { throw new UnsupportedOperationException(); }
        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> extension(final Class<T> extensionType) {
            if (extensionType == EpochController.class && hasEpoch) {
                return Optional.of((T) controller);
            }
            return Optional.empty();
        }
        @Override public <T> Optional<T> unwrap(final Class<T> nativeType) { return Optional.empty(); }
        @Override public void close() {}
    }

    private static final class CountingController implements EpochController {
        final AtomicInteger count = new AtomicInteger();
        @Override public void incrementEpoch() { count.incrementAndGet(); }
    }
}
