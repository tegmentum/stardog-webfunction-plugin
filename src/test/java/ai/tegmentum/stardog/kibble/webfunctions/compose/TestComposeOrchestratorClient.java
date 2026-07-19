package ai.tegmentum.stardog.kibble.webfunctions.compose;

import ai.tegmentum.webassembly4j.api.ComponentInstance;
import ai.tegmentum.webassembly4j.api.Function;
import ai.tegmentum.webassembly4j.api.Global;
import ai.tegmentum.webassembly4j.api.Memory;
import ai.tegmentum.webassembly4j.api.Table;
import ai.tegmentum.webassembly4j.api.exception.ExecutionException;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ComposeOrchestratorClient} — verifies the
 * multi-step compose flow (plan#deserialize → emit#compose →
 * emit#get-artifact) and the plan-to-turtle passthrough dispatch to
 * the expected WIT export paths, using a mock ComponentInstance that
 * records calls and returns canned responses.
 */
public class TestComposeOrchestratorClient {

    @Test
    public void composeInvokesExpectedExportsInOrder() {
        final FakeInstance fake = new FakeInstance();
        fake.responseFor(ComposeOrchestratorClient.EXPORT_PLAN_DESERIALIZE, "PLAN_RECORD");
        final Map<String, Object> composeResult = new HashMap<>();
        final byte[] digest = new byte[]{0x11, 0x22, 0x33};
        composeResult.put("digest", digest);
        composeResult.put("size", 512L);
        composeResult.put("emit-key", digest);
        fake.responseFor(ComposeOrchestratorClient.EXPORT_EMIT_COMPOSE, composeResult);
        fake.responseFor(ComposeOrchestratorClient.EXPORT_EMIT_GET_ARTIFACT, new byte[]{0x00, 0x61, 0x73, 0x6D});

        final ComposeOrchestratorClient client =
                new ComposeOrchestratorClient(new NoopOrchestratorInstance(fake));
        final byte[] planCbor = new byte[]{0x00, 0x01, 0x02};
        final byte[] wasm = client.composeFromCbor(planCbor);

        assertThat(fake.callsInOrder).containsExactly(
                ComposeOrchestratorClient.EXPORT_PLAN_DESERIALIZE,
                ComposeOrchestratorClient.EXPORT_EMIT_COMPOSE,
                ComposeOrchestratorClient.EXPORT_EMIT_GET_ARTIFACT);
        assertThat(wasm).containsExactly(0x00, 0x61, 0x73, 0x6D);
    }

    @Test
    public void composePropagatesExecutionExceptionAsComposeException() {
        final FakeInstance fake = new FakeInstance();
        fake.errorFor(ComposeOrchestratorClient.EXPORT_PLAN_DESERIALIZE, "plan-invalid-cbor");
        final ComposeOrchestratorClient client =
                new ComposeOrchestratorClient(new NoopOrchestratorInstance(fake));
        assertThatThrownBy(() -> client.composeFromCbor(new byte[]{0}))
                .isInstanceOf(ComposeException.class)
                .hasMessageContaining("plan-invalid-cbor");
    }

    @Test
    public void planToTurtleDispatchesToRdfExport() {
        final FakeInstance fake = new FakeInstance();
        fake.responseFor(ComposeOrchestratorClient.EXPORT_RDF_PLAN_TO_TURTLE, "@prefix : <urn:> .\n");
        final ComposeOrchestratorClient client =
                new ComposeOrchestratorClient(new NoopOrchestratorInstance(fake));
        final String turtle = client.planToTurtleCbor(new byte[]{1, 2, 3});
        assertThat(turtle).startsWith("@prefix");
        assertThat(fake.callsInOrder).containsExactly(
                ComposeOrchestratorClient.EXPORT_RDF_PLAN_TO_TURTLE);
    }

    @Test
    public void planFromTurtleDispatchesToRdfExport() {
        final FakeInstance fake = new FakeInstance();
        final byte[] cbor = new byte[]{0x77, 0x66, 0x11};
        fake.responseFor(ComposeOrchestratorClient.EXPORT_RDF_PLAN_FROM_TURTLE, cbor);
        final ComposeOrchestratorClient client =
                new ComposeOrchestratorClient(new NoopOrchestratorInstance(fake));
        final byte[] result = client.planFromTurtle("@prefix : <urn:> .\n");
        assertThat(result).containsExactly(0x77, 0x66, 0x11);
        assertThat(fake.callsInOrder).containsExactly(
                ComposeOrchestratorClient.EXPORT_RDF_PLAN_FROM_TURTLE);
        assertThat(fake.lastArgs.get(0))
                .as("turtle string is the only arg")
                .isEqualTo("@prefix : <urn:> .\n");
    }

    @Test
    public void planFromTurtleWithIriDispatchesToWithIriExport() {
        final FakeInstance fake = new FakeInstance();
        final byte[] cbor = new byte[]{0x01, 0x02};
        fake.responseFor(ComposeOrchestratorClient.EXPORT_RDF_PLAN_FROM_TURTLE_WI, cbor);
        final ComposeOrchestratorClient client =
                new ComposeOrchestratorClient(new NoopOrchestratorInstance(fake));
        client.planFromTurtle("@prefix : <urn:> .\n", "urn:my:plan");
        assertThat(fake.callsInOrder).containsExactly(
                ComposeOrchestratorClient.EXPORT_RDF_PLAN_FROM_TURTLE_WI);
        assertThat(fake.lastArgs.get(1))
                .as("plan iri is second arg")
                .isEqualTo("urn:my:plan");
    }

    @Test
    public void planFromTurtlePropagatesExecutionExceptionAsComposeException() {
        final FakeInstance fake = new FakeInstance();
        fake.errorFor(ComposeOrchestratorClient.EXPORT_RDF_PLAN_FROM_TURTLE,
                "invalid-input");
        final ComposeOrchestratorClient client =
                new ComposeOrchestratorClient(new NoopOrchestratorInstance(fake));
        assertThatThrownBy(() -> client.planFromTurtle("not turtle"))
                .isInstanceOf(ComposeException.class)
                .hasMessageContaining("invalid-input");
    }

    @Test
    public void planToTurtleWithIriDispatchesToWithIriExport() {
        final FakeInstance fake = new FakeInstance();
        fake.responseFor(
                ComposeOrchestratorClient.EXPORT_RDF_PLAN_TO_TURTLE_WI,
                "@prefix : <urn:plan> .\n");
        final ComposeOrchestratorClient client =
                new ComposeOrchestratorClient(new NoopOrchestratorInstance(fake));
        client.planToTurtleCbor(new byte[]{1, 2, 3}, "urn:my:plan");
        assertThat(fake.callsInOrder).containsExactly(
                ComposeOrchestratorClient.EXPORT_RDF_PLAN_TO_TURTLE_WI);
        assertThat(fake.lastArgs.get(0))
                .as("cbor bytes are first arg")
                .isInstanceOf(byte[].class);
        assertThat(fake.lastArgs.get(1))
                .as("plan iri is second arg")
                .isEqualTo("urn:my:plan");
    }

    /**
     * Adapts a hand-crafted mock {@link ComponentInstance} into a
     * {@link ComposeOrchestratorInstance} that just returns the mock
     * — used so the unit tests exercise the client's dispatch logic
     * without instantiating the real 12 MB orchestrator wasm.
     */
    private static final class NoopOrchestratorInstance extends ComposeOrchestratorInstance {
        private final ComponentInstance backing;

        NoopOrchestratorInstance(final ComponentInstance backing) {
            super(new NoopLoader());
            this.backing = backing;
        }

        @Override
        public ComponentInstance instance() {
            return backing;
        }
    }

    private static final class NoopLoader extends ComposeOrchestratorLoader {
        NoopLoader() {
            super(Paths.get(System.getProperty("java.io.tmpdir"), "wf-noop-loader"));
        }
        @Override
        public synchronized void ensureExtracted() { /* no-op for tests */ }
    }

    /**
     * Minimal ComponentInstance mock — records invoked function names,
     * returns canned responses, and can throw a scripted
     * ExecutionException on a target export.
     */
    private static final class FakeInstance implements ComponentInstance {
        final Map<String, Object> responses = new HashMap<>();
        final Map<String, String> errors = new HashMap<>();
        final List<String> callsInOrder = new ArrayList<>();
        List<Object> lastArgs = Collections.emptyList();

        void responseFor(final String export, final Object payload) {
            responses.put(export, payload);
        }

        void errorFor(final String export, final String code) {
            errors.put(export, code);
        }

        @Override
        public Object invoke(final String functionName, final Object... args) {
            callsInOrder.add(functionName);
            lastArgs = new ArrayList<>();
            Collections.addAll(lastArgs, args);
            if (errors.containsKey(functionName)) {
                throw new ExecutionException(errors.get(functionName));
            }
            return responses.get(functionName);
        }

        // --- Instance surface (no memory/table/global exercised) ------

        @Override public Optional<Function> function(final String name) { return Optional.empty(); }
        @Override public Optional<Memory> memory(final String name) { return Optional.empty(); }
        @Override public Optional<Table> table(final String name) { return Optional.empty(); }
        @Override public Optional<Global> global(final String name) { return Optional.empty(); }
        @Override public <T> Optional<T> unwrap(final Class<T> nativeType) { return Optional.empty(); }

        // --- ComponentInstance surface --------------------------------

        @Override public boolean hasFunction(final String name) { return true; }
        @Override public List<String> exportedFunctions() { return Collections.emptyList(); }
        @Override public List<String> exportedInterfaces() { return Collections.emptyList(); }
        @Override public boolean exportsInterface(final String name) { return true; }
    }
}
