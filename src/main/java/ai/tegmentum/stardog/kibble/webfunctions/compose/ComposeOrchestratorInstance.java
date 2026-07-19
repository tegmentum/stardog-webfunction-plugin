package ai.tegmentum.stardog.kibble.webfunctions.compose;

import ai.tegmentum.stardog.kibble.webfunctions.WebFunctionConfig;
import ai.tegmentum.webassembly4j.api.Component;
import ai.tegmentum.webassembly4j.api.ComponentInstance;
import ai.tegmentum.webassembly4j.api.DefaultLinkingContext;
import ai.tegmentum.webassembly4j.api.DefaultWasiContext;
import ai.tegmentum.webassembly4j.api.Engine;
import ai.tegmentum.webassembly4j.api.WebAssembly;
import ai.tegmentum.webassembly4j.api.WebAssemblyBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lifecycle owner for the compose orchestrator's wasm component and its
 * single cached {@link ComponentInstance}.
 *
 * <p>The orchestrator's imports are WASI Preview 2 only (plus the
 * harmless {@code sys:compose/types} type-only interface). This class
 * builds a {@link DefaultLinkingContext} with a {@link DefaultWasiContext}
 * that preopens the three loader-provisioned directories
 * ({@code /blobs}, {@code /cache}, {@code /trust}) and instantiates the
 * component lazily on first {@link #instance()} call.
 *
 * <p>Distinct from {@link ai.tegmentum.stardog.kibble.webfunctions.StardogWasmInstance}:
 * that class wires the base {@code tegmentum:webfunction/*} host
 * callbacks for user extensions; the orchestrator has no such callbacks
 * on its WIT world so its linker context is intentionally minimal.
 *
 * <p>Thread safety: {@link #instance()} is safe to call concurrently.
 * Component instantiation happens once under a monitor and the returned
 * {@link ComponentInstance} is reused for the lifetime of the JVM (or
 * until {@link #close()}). Callers must serialize concurrent invocations
 * on the returned instance if the underlying provider is single-threaded
 * (wasmtime4j surfaces per-instance mutability via its own store).
 */
public class ComposeOrchestratorInstance implements AutoCloseable {

    private final ComposeOrchestratorLoader loader;

    private volatile Engine engine;
    private volatile Component component;
    private volatile ComponentInstance instance;
    private boolean closed;

    public ComposeOrchestratorInstance(final ComposeOrchestratorLoader loader) {
        this.loader = loader;
    }

    /**
     * Convenience constructor — uses the default
     * {@code ${stardog.home}/webfunctions-compose} root.
     */
    public static ComposeOrchestratorInstance forDefaultRoot() {
        return new ComposeOrchestratorInstance(ComposeOrchestratorLoader.forDefaultRoot());
    }

    /**
     * Return the cached {@link ComponentInstance}, instantiating on the
     * first call. Throws {@link IOException} on wasm extraction / loading
     * failures and {@link RuntimeException} on component-level errors.
     */
    public ComponentInstance instance() throws IOException {
        ComponentInstance current = instance;
        if (current != null) return current;
        synchronized (this) {
            if (closed) throw new IllegalStateException("orchestrator already closed");
            if (instance == null) {
                loader.ensureExtracted();
                instance = buildInstance();
            }
            return instance;
        }
    }

    public ComposeOrchestratorLoader loader() {
        return loader;
    }

    private ComponentInstance buildInstance() throws IOException {
        final byte[] bytes = Files.readAllBytes(loader.orchestratorWasm());

        // The orchestrator wasm's imports are WASI Preview 2 only. Build
        // a WASI context that preopens the three loader-provisioned host
        // directories at fixed guest paths (mirrors probe agent's idiom
        // in wasmtime4j-provider WasmtimeCallableResourceTest#86-100).
        final DefaultWasiContext wasi = DefaultWasiContext.builder()
                .preopenDir(loader.blobsDir().toString(),  "/blobs",      true)
                .preopenDir(loader.cacheDir().toString(),  "/emit-cache", true)
                .preopenDir(loader.trustDir().toString(),  "/trust",      true)
                .build();

        final DefaultLinkingContext linking = DefaultLinkingContext.builder()
                .wasiContext(wasi)
                .build();

        this.engine = buildEngine();
        this.component = engine.loadComponent(bytes);
        return (ComponentInstance) component.instantiate(
                linking,
                WebFunctionConfig.componentConfigFromSystemProperties());
    }

    private static Engine buildEngine() {
        final WebAssemblyBuilder builder = WebAssembly.builder()
                .provider(WebFunctionConfig.engineProvider())
                .config(WebFunctionConfig.fromSystemProperties());
        WebFunctionConfig.engineId().ifPresent(builder::engine);
        final Engine engine = builder.build();
        if (!engine.capabilities().supportsComponents()) {
            engine.close();
            throw new IllegalStateException(
                    "compose orchestrator requires component-model support; engine '"
                            + engine.info().engineId() + "' does not support components");
        }
        return engine;
    }

    @Override
    public synchronized void close() {
        closed = true;
        // Best-effort — release the component and engine when no longer
        // needed. Instance handles are managed by the component; closing
        // the component tears them down.
        try {
            if (component != null) {
                try { component.close(); } catch (RuntimeException ignore) {}
                component = null;
            }
        } finally {
            instance = null;
            if (engine != null) {
                try { engine.close(); } catch (RuntimeException ignore) {}
                engine = null;
            }
        }
    }

    // Package-visible test hooks — expose the preopen directories the
    // orchestrator's imports resolved onto so integration tests can drop
    // blob fixtures into /blobs before invoking compose.
    Path blobsDir() { return loader.blobsDir(); }
    Path cacheDir() { return loader.cacheDir(); }
    Path trustDir() { return loader.trustDir(); }
}
