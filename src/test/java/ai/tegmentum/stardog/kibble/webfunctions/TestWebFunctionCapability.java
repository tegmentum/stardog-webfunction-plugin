package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capability wave Phase 1b — permission string surface + resource type.
 *
 * <p>The Phase 1b agent will register {@link WebFunctionCallbackResourceType}
 * in {@code WebFunctionServiceModule}; this file only proves the constants
 * resolve to the strings admins will paste into
 * {@code stardog-admin role permission add ...} commands per
 * {@code capability-implementation.md} §8.
 */
public class TestWebFunctionCapability {

    @Test
    public void resourceTypeIdMatchesConvention() {
        assertThat(WebFunctionCallbackResourceType.INSTANCE.id())
                .isEqualTo("web-function-callback");
        // Callback grants are process-wide, not per-database — distinct
        // from WebFunctionResourceType which gates EXECUTE per URL.
        assertThat(WebFunctionCallbackResourceType.INSTANCE.isDatabaseType()).isFalse();
    }

    @Test
    public void graphCallbackConstantsMatchMemo() {
        assertThat(WebFunctionCapability.GRAPH_QUERY)
                .isEqualTo("web-function-callback:invoke:graph-callbacks/execute-query");
        assertThat(WebFunctionCapability.GRAPH_UPDATE)
                .isEqualTo("web-function-callback:invoke:graph-callbacks/execute-update");
    }

    @Test
    public void httpCallbackConstantsMatchMemo() {
        assertThat(WebFunctionCapability.HTTP_GET)
                .isEqualTo("web-function-callback:invoke:http-callbacks/http-get");
        assertThat(WebFunctionCapability.HTTP_POST)
                .isEqualTo("web-function-callback:invoke:http-callbacks/http-post-json");
    }

    @Test
    public void wasmCallbackConstantsMatchMemo() {
        assertThat(WebFunctionCapability.WASM_INVOKE)
                .isEqualTo("web-function-callback:invoke:wasm-callbacks/invoke-wasm");
        assertThat(WebFunctionCapability.WASM_SERVICE)
                .isEqualTo("web-function-callback:invoke:wasm-callbacks/invoke-wasm-service");
    }

    @Test
    public void forInvokeSynthesisMatchesEveryConstant() {
        // Each constant must round-trip through the helper — Phase 5+
        // interfaces added later compute the same shape.
        assertThat(WebFunctionCapability.forInvoke("graph-callbacks", "execute-query"))
                .isEqualTo(WebFunctionCapability.GRAPH_QUERY);
        assertThat(WebFunctionCapability.forInvoke("graph-callbacks", "execute-update"))
                .isEqualTo(WebFunctionCapability.GRAPH_UPDATE);
        assertThat(WebFunctionCapability.forInvoke("http-callbacks", "http-get"))
                .isEqualTo(WebFunctionCapability.HTTP_GET);
        assertThat(WebFunctionCapability.forInvoke("http-callbacks", "http-post-json"))
                .isEqualTo(WebFunctionCapability.HTTP_POST);
        assertThat(WebFunctionCapability.forInvoke("wasm-callbacks", "invoke-wasm"))
                .isEqualTo(WebFunctionCapability.WASM_INVOKE);
        assertThat(WebFunctionCapability.forInvoke("wasm-callbacks", "invoke-wasm-service"))
                .isEqualTo(WebFunctionCapability.WASM_SERVICE);
    }

    @Test
    public void forInvokeHandlesNewInterfacesUniformly() {
        // Phase 5+ interfaces compute through the same helper.
        assertThat(WebFunctionCapability.forInvoke("sink-callbacks", "write"))
                .isEqualTo("web-function-callback:invoke:sink-callbacks/write");
    }

    @Test
    public void forInvokeToleratesNullsWithEmptyPieces() {
        // Defensive — malformed inputs must not NPE at the enforcer's
        // permission-build site. Empty pieces still round-trip predictably
        // so audit rows can attribute the misuse.
        assertThat(WebFunctionCapability.forInvoke(null, null))
                .isEqualTo("web-function-callback:invoke:/");
        assertThat(WebFunctionCapability.forInvoke("graph-callbacks", null))
                .isEqualTo("web-function-callback:invoke:graph-callbacks/");
    }

    @Test
    public void resourceTypeIsSingleton() {
        // Mirrors WebFunctionResourceType.INSTANCE — a fresh call in tests
        // returns the same reference so consumers can use ==.
        assertThat(WebFunctionCallbackResourceType.INSTANCE)
                .isSameAs(WebFunctionCallbackResourceType.INSTANCE);
    }

    @Test
    public void actionInvokeConstantAvailable() {
        assertThat(WebFunctionCapability.ACTION_INVOKE).isEqualTo("invoke");
    }
}
