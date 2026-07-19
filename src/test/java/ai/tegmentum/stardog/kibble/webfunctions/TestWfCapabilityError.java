package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.StardogException;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capability wave Phase 1a — sealed {@link WfCapabilityError} hierarchy.
 *
 * <p>Mirrors {@code TestFuelMetering} / {@code TestUserQuota}'s coverage
 * of the sibling {@link WfBudgetError}: exercises variant construction,
 * error-code strings, JSON-payload shape, and the sentinel {@code " json="}
 * suffix in {@link Throwable#getMessage()} that string-only Stardog
 * SPARQL error paths depend on.
 */
public class TestWfCapabilityError {

    @Test
    public void allVariantsAreStardogExceptions() {
        // Sealed base extends StardogException (RuntimeException) so any
        // variant propagates unchanged through the query stack.
        assertThat(new WfCapabilityError.LoadTimeDenied("u", "i", "policy", "alice", "acme"))
                .isInstanceOf(StardogException.class)
                .isInstanceOf(RuntimeException.class);
        assertThat(new WfCapabilityError.PerCallDenied("u", "i", "m", "alice", "x", "y"))
                .isInstanceOf(StardogException.class);
        assertThat(new WfCapabilityError.PolicyStoreUnavailable("u", "err"))
                .isInstanceOf(StardogException.class);
        assertThat(new WfCapabilityError.UnknownExtension("u", "alice", "policy"))
                .isInstanceOf(StardogException.class);
    }

    @Test
    public void loadTimeDeniedCarriesStableErrorCodeAndJson() {
        final WfCapabilityError.LoadTimeDenied err = new WfCapabilityError.LoadTimeDenied(
                "file:///ext.wasm", "graph-callbacks", "policy", "alice", "default-policy");
        assertThat(err.errorCode()).isEqualTo("WF_CAPABILITY_DENIED_AT_LOAD");
        assertThat(err.extensionUri()).isEqualTo("file:///ext.wasm");
        assertThat(err.missingInterface()).isEqualTo("graph-callbacks");
        assertThat(err.resolutionStage()).isEqualTo("policy");
        assertThat(err.invoker()).isEqualTo("alice");
        assertThat(err.policySource()).isEqualTo("default-policy");
        // JSON payload has every field.
        assertThat(err.jsonPayload())
                .contains("\"error_code\":\"WF_CAPABILITY_DENIED_AT_LOAD\"")
                .contains("\"extension\":\"file:///ext.wasm\"")
                .contains("\"missing_interface\":\"graph-callbacks\"")
                .contains("\"resolution_stage\":\"policy\"")
                .contains("\"invoker\":\"alice\"")
                .contains("\"policy_source\":\"default-policy\"");
    }

    @Test
    public void perCallDeniedCarriesReasonAndArgumentsSummary() {
        final WfCapabilityError.PerCallDenied err = new WfCapabilityError.PerCallDenied(
                "file:///ext.wasm", "http-callbacks", "http-get",
                "alice",
                WfCapabilityError.PerCallDenied.REASON_HOST_DENIED,
                "api.evil.com");
        assertThat(err.errorCode()).isEqualTo("WF_CAPABILITY_DENIED_AT_CALL");
        assertThat(err.reason()).isEqualTo(WfCapabilityError.PerCallDenied.REASON_HOST_DENIED);
        assertThat(err.argumentsSummary()).isEqualTo("api.evil.com");
        assertThat(err.jsonPayload())
                .contains("\"interface\":\"http-callbacks\"")
                .contains("\"method\":\"http-get\"")
                .contains("\"reason\":\"host-denied\"")
                .contains("\"arguments_summary\":\"api.evil.com\"");
    }

    @Test
    public void policyStoreUnavailableCarriesDetail() {
        final WfCapabilityError.PolicyStoreUnavailable err =
                new WfCapabilityError.PolicyStoreUnavailable(
                        "file:///ext.wasm", "policy store not installed");
        assertThat(err.errorCode()).isEqualTo("WF_CAPABILITY_POLICY_STORE_UNAVAILABLE");
        assertThat(err.detail()).isEqualTo("policy store not installed");
        assertThat(err.jsonPayload())
                .contains("\"detail\":\"policy store not installed\"");
    }

    @Test
    public void unknownExtensionCarriesInvokerAndPolicySource() {
        final WfCapabilityError.UnknownExtension err =
                new WfCapabilityError.UnknownExtension(
                        "file:///ext.wasm", "alice",
                        "unknown-extension-policy=deny");
        assertThat(err.errorCode()).isEqualTo("WF_CAPABILITY_UNKNOWN_EXTENSION");
        assertThat(err.invoker()).isEqualTo("alice");
        assertThat(err.policySource()).isEqualTo("unknown-extension-policy=deny");
        assertThat(err.jsonPayload())
                .contains("\"invoker\":\"alice\"")
                .contains("\"policy_source\":\"unknown-extension-policy=deny\"");
    }

    @Test
    public void messageEmbedsJsonAtSentinelSuffix() {
        // Same shape as WfBudgetError — Stardog's string-only SPARQL error
        // surface conveys the JSON payload through this suffix.
        final WfCapabilityError err = new WfCapabilityError.PolicyStoreUnavailable("u", "e");
        assertThat(err.getMessage()).contains(" json=");
        final String tail = err.getMessage().substring(err.getMessage().indexOf(" json=") + 6);
        assertThat(tail).isEqualTo(err.jsonPayload());
    }

    @Test
    public void jsonEscapesQuotesAndBackslashes() {
        final WfCapabilityError.PolicyStoreUnavailable err =
                new WfCapabilityError.PolicyStoreUnavailable(
                        "file:///a\"b\\c.wasm", "quote \" and backslash \\ present");
        assertThat(err.jsonPayload())
                .contains("\\\"")   // escaped inner quote
                .contains("\\\\");  // escaped backslash
    }

    @Test
    public void nullArgumentsBecomeEmptyStrings() {
        // Defensive — callers on the trap path may not have every field
        // populated. Nulls must not NPE at construction.
        final WfCapabilityError.LoadTimeDenied err = new WfCapabilityError.LoadTimeDenied(
                null, null, null, null, null);
        assertThat(err.extensionUri()).isEmpty();
        assertThat(err.missingInterface()).isEmpty();
        assertThat(err.resolutionStage()).isEmpty();
        assertThat(err.invoker()).isEmpty();
        assertThat(err.policySource()).isEmpty();
    }

    @Test
    public void sealedHierarchyPermitsFourVariants() {
        // The permits clause is a design commitment; any follow-up that
        // adds a variant needs an explicit permits update. Assertion is
        // compile-only via the exhaustive switch below.
        final WfCapabilityError err = new WfCapabilityError.PerCallDenied(
                "u", "i", "m", "a", "r", "s");
        final String tag = switch (err) {
            case WfCapabilityError.LoadTimeDenied ignored          -> "load";
            case WfCapabilityError.PerCallDenied ignored           -> "call";
            case WfCapabilityError.PolicyStoreUnavailable ignored  -> "store-down";
            case WfCapabilityError.UnknownExtension ignored        -> "unknown";
        };
        assertThat(tag).isEqualTo("call");
    }
}
