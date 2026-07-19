package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.index.dictionary.MappingDictionary;
import com.stardog.stark.Values;

import java.net.MalformedURLException;
import java.util.concurrent.ExecutionException;

/**
 * Thin facade for loading a callee wasm component by URL for the
 * {@code wasm-callbacks} dispatch surface. Reuses the same load path
 * {@link StardogWasmInstance#from(com.stardog.stark.Value,
 * MappingDictionary)} uses for top-level {@code wf:call} invocations —
 * that means the plugin-wide byte cache ({@link
 * StardogWasmInstance#loadingCache}) and component cache
 * ({@link StardogWasmInstance} internal COMPONENT_CACHE) are shared, so a
 * callee URL invoked N times in one query only pays for its download
 * and compile once; only per-invocation instantiation is repeated.
 *
 * <p>URL scheme is transparent: any scheme the plugin's registered URL
 * handlers accept works — {@code ipfs://}, {@code https://},
 * {@code file://}, {@code sha256://}. The
 * {@link StardogWasmInstance#getWasmUrl} path normalises the URL,
 * appends the plugin-version suffix for non-content-addressed schemes,
 * and lands the same {@link java.net.URL} the top-level path would use.
 *
 * <p>Shiro permission check + capability grant resolution both fire
 * from inside {@link StardogWasmInstance#from} — nothing extra needed
 * here. On URL parse failure or a load-time capability denial, the
 * checked exception (or {@link RuntimeException}) escapes; the caller
 * in {@link HostCallbacks} maps it to the appropriate
 * {@code wasm-call-error} arm.
 */
public final class CalleeComponentLoader {

    private CalleeComponentLoader() {}

    /**
     * Load a callee wasm component by URL. Returns a fresh
     * {@link StardogWasmInstance} wrapping the cached
     * {@link ai.tegmentum.webassembly4j.api.Component}; the caller owns
     * the returned instance's lifecycle and MUST close it (typically via
     * try-with-resources at the dispatch site).
     *
     * @param url the callee URL (any scheme the plugin's registered URL
     *            handlers accept — ipfs, https, file, sha256).
     * @param dictionary the outer query's MappingDictionary, plumbed
     *                   through so the callee can resolve dictionary IDs.
     *                   Nullable (isolated / test paths).
     * @throws MalformedURLException if the URL is malformed
     * @throws ExecutionException if bytes fetch failed
     */
    public static StardogWasmInstance load(final String url,
                                           final MappingDictionary dictionary)
            throws MalformedURLException, ExecutionException {
        return StardogWasmInstance.from(Values.iri(url), dictionary);
    }
}
