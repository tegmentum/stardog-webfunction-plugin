package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.After;
import org.junit.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capability-ask CA4 — verifies the {@link CallbackContext#setAsk(CapabilityAsk)}
 * setter / {@link CallbackContext#ask()} getter roundtrip that
 * {@link StardogWasmInstance} populates at instantiation time (via
 * {@code extractAndRecordAsk}) and {@link HostCallbacks} consults on
 * every host-callback dispatch when firing the warn-on-undeclared
 * diagnostic ({@code capability-ask.md} §8).
 *
 * <p>Data-shape contract on {@link CallbackContext} only — no wasm
 * dispatch stack needed. The extraction path in
 * {@link StardogWasmInstance#extractAndRecordAsk} is covered indirectly
 * by {@link WasmTestSuiteIT} (mvn verify).
 */
public class TestCallbackContextAsk {

    @After
    public void tearDown() {
        final CallbackContext ctx = CallbackContext.current();
        if (ctx != null) CallbackContext.unbindIfOutermost(ctx);
    }

    @Test
    public void askDefaultsToEmpty() {
        final CallbackContext ctx = CallbackContext.bind();
        assertThat(ctx.ask()).isEmpty();
    }

    @Test
    public void askSetterRoundTripsThroughGetter() {
        final CallbackContext ctx = CallbackContext.bind();
        final CapabilityAsk ask = new CapabilityAsk(
                Set.of("http-callbacks"),
                Set.of("http-callbacks/get"),
                Set.of("api.acme.com"),
                Set.of(), Set.of(),
                Optional.of("uses OAuth"));
        ctx.setAsk(ask);
        final Optional<CapabilityAsk> stamped = ctx.ask();
        assertThat(stamped).isPresent();
        assertThat(stamped.get()).isEqualTo(ask);
    }

    @Test
    public void askEmptySentinelIsARealPresentValue() {
        // Distinguishes "extension declared an empty ask" from "no ask
        // was extracted" — the former stamps EMPTY on the context so
        // the warn-on-undeclared diagnostic knows to fire (every
        // callback fires GRANTED_UNDECLARED against an empty ask); the
        // latter leaves the context ask absent.
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setAsk(CapabilityAsk.EMPTY);
        assertThat(ctx.ask()).isPresent();
        assertThat(ctx.ask().get()).isEqualTo(CapabilityAsk.EMPTY);
    }

    @Test
    public void askClearedBySetNull() {
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setAsk(CapabilityAsk.EMPTY);
        assertThat(ctx.ask()).isPresent();
        ctx.setAsk(null);
        assertThat(ctx.ask()).isEmpty();
    }
}
