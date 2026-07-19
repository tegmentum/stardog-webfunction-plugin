package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capability-ask CA5 — verifies {@link HostCallbacks#warnIfUndeclared}
 * emits {@link CapabilityAuditRow.Outcome#GRANTED_UNDECLARED} rows for
 * dispatches the grant permitted but the extension's ask did not
 * declare. Drives the check in isolation from the wasm dispatch stack
 * — {@code warnIfUndeclared} is package-private specifically for this
 * kind of harness.
 *
 * <p>Covers the two failure modes the memo §8 catches: malicious
 * understated ask (callback reaches beyond declared scope) and buggy
 * under-declared ask (author forgot to update the ask when adding a
 * feature). Plus the negative case (declared ⇒ no row) and the master
 * gate case (no ask stamped ⇒ no row).
 */
public class TestHostCallbacksWarnOnUndeclared {

    @Before
    public void setUp() {
        CapabilityAttributionRing.INSTANCE.clear();
        CapabilityAttributionRing.INSTANCE.setEnabled(true);
        CapabilityAttributionRing.INSTANCE.setCapacity(
                CapabilityAttributionRing.DEFAULT_CAPACITY);
    }

    @After
    public void tearDown() {
        CapabilityAttributionRing.INSTANCE.clear();
        final CallbackContext ctx = CallbackContext.current();
        if (ctx != null) CallbackContext.unbindIfOutermost(ctx);
    }

    @Test
    public void undeclaredDispatchEmitsGrantedUndeclaredRow() {
        // Ask declares graph-callbacks/execute-query only; the dispatch
        // targets http-callbacks/http-post-json — undeclared, though
        // grant permits. One GRANTED_UNDECLARED row lands.
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setAsk(new CapabilityAsk(
                Set.of("graph-callbacks"),
                Set.of("graph-callbacks/execute-query"),
                Set.of(), Set.of(), Set.of(),
                Optional.empty()));
        final CapabilityGrant grant = grantFor("alice");

        HostCallbacks.warnIfUndeclared(
                ctx, grant, "http-callbacks", "http-post-json", "api.evil.com");

        final List<CapabilityAuditRow> rows = CapabilityAttributionRing.INSTANCE.snapshot();
        assertThat(rows).hasSize(1);
        final CapabilityAuditRow row = rows.get(0);
        assertThat(row.outcome()).isEqualTo(CapabilityAuditRow.Outcome.GRANTED_UNDECLARED);
        assertThat(row.interfaceName()).isEqualTo("http-callbacks");
        assertThat(row.method()).isEqualTo("http-post-json");
        assertThat(row.userId()).isEqualTo("alice");
    }

    @Test
    public void declaredDispatchEmitsNoRow() {
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setAsk(new CapabilityAsk(
                Set.of("http-callbacks"),
                Set.of("http-callbacks/http-get"),
                Set.of(), Set.of(), Set.of(),
                Optional.empty()));

        HostCallbacks.warnIfUndeclared(
                ctx, grantFor("alice"),
                "http-callbacks", "http-get", "api.acme.com");

        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
    }

    @Test
    public void interfaceDeclaredButMethodUndeclaredStillEmitsRow() {
        // The extension asked for the interface but a different method
        // on it — the drift case §8 calls out (author added a POST
        // path in v1.1 but forgot to update the ask).
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setAsk(new CapabilityAsk(
                Set.of("http-callbacks"),
                Set.of("http-callbacks/http-get"),
                Set.of(), Set.of(), Set.of(),
                Optional.empty()));

        HostCallbacks.warnIfUndeclared(
                ctx, grantFor("alice"),
                "http-callbacks", "http-post-json", "api.acme.com");

        final List<CapabilityAuditRow> rows = CapabilityAttributionRing.INSTANCE.snapshot();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).outcome()).isEqualTo(
                CapabilityAuditRow.Outcome.GRANTED_UNDECLARED);
    }

    @Test
    public void emptyAskFiresOnEveryDispatch() {
        // An extension that shipped with CapabilityAsk.EMPTY declared —
        // caller asked for nothing — so every dispatch is undeclared.
        // Distinct from "no ask stamped" (which fires zero rows) —
        // §8's "malicious understated ask" catches exactly this.
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setAsk(CapabilityAsk.EMPTY);

        HostCallbacks.warnIfUndeclared(
                ctx, grantFor("alice"),
                "http-callbacks", "http-get", "api.acme.com");
        HostCallbacks.warnIfUndeclared(
                ctx, grantFor("alice"),
                "graph-callbacks", "execute-query", "");

        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).hasSize(2);
    }

    @Test
    public void noAskStampedEmitsNoRow() {
        // Extension shipped without a stardog.capability-ask section —
        // ctx.ask() returns empty; the warn-on-undeclared check
        // short-circuits (missing ask is reported at load time, no
        // per-callback noise per memo §6).
        final CallbackContext ctx = CallbackContext.bind();
        assertThat(ctx.ask()).isEmpty();

        HostCallbacks.warnIfUndeclared(
                ctx, grantFor("alice"),
                "http-callbacks", "http-post-json", "api.evil.com");

        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
    }

    @Test
    public void nullGrantYieldsBlankInvoker() {
        // Defensive path — grant absent shouldn't NPE the diagnostic;
        // the row lands with an empty invoker instead.
        final CallbackContext ctx = CallbackContext.bind();
        ctx.setAsk(CapabilityAsk.EMPTY);

        HostCallbacks.warnIfUndeclared(
                ctx, null, "http-callbacks", "http-get", "api.acme.com");

        final List<CapabilityAuditRow> rows = CapabilityAttributionRing.INSTANCE.snapshot();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).userId()).isEmpty();
    }

    @Test
    public void grantedUndeclaredIsNewEnumVariant() {
        // Locks in the new outcome enum variant so downstream
        // compliance query surfaces can filter for it. Also validates
        // the enum values so a future refactor that renames it breaks
        // this test (deliberate — the audit row schema is load-bearing).
        assertThat(CapabilityAuditRow.Outcome.values())
                .containsExactly(
                        CapabilityAuditRow.Outcome.GRANTED,
                        CapabilityAuditRow.Outcome.DENIED,
                        CapabilityAuditRow.Outcome.GRANTED_UNDECLARED);
    }

    /** Grant that permits everything — the ask-side diagnostic isn't gated on the grant shape. */
    private static CapabilityGrant grantFor(final String invoker) {
        return new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("http-callbacks", "graph-callbacks"),
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                HttpPathAllowlist.ALLOW_NONE,
                WasmCalleeAllowlist.ALLOW_NONE,
                invoker,
                CapabilityModel.INVOKER_SUBJECT);
    }
}
