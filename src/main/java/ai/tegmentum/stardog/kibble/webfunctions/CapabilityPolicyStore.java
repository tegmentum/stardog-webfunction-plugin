package ai.tegmentum.stardog.kibble.webfunctions;

import java.net.URL;
import java.util.Optional;

/**
 * Source-of-truth store for capability policy — replaces the Phase 1
 * TOML sidecar manifest with an RDF triple store keyed by extension URL.
 *
 * <p>{@link #resolveFor(URL)} returns the projected {@link PolicyTriples}
 * for the extension: allowed interfaces, allowed methods, allowed hosts.
 *
 * <p>Empty {@link Optional} return means the store has no data yet
 * bootstrapped (bootstrap failed, or {@link #isReady()} is false). Empty
 * {@link PolicyTriples} return means the store is up but has no policy
 * for the extension — the resolver routes through the
 * {@code webfunctions.capability.unknown-extension-policy} config for
 * that case.
 *
 * <p>Implementations must be safe for concurrent access from multiple
 * invocation threads; the store is consulted on every extension
 * instantiation and there is no cache layer above it per implementation
 * memo §13.
 */
public interface CapabilityPolicyStore {

    /**
     * Look up the effective capability policy for {@code extensionUrl}.
     *
     * @return {@code Optional.of(PolicyTriples.EMPTY)} when the store is
     *         up but the extension is unknown; {@code Optional.of(non-empty)}
     *         when policy exists; {@code Optional.empty()} when the
     *         store is not ready (bootstrap failed / not yet initialized).
     */
    Optional<PolicyTriples> resolveFor(URL extensionUrl);

    /**
     * True iff the store is bootstrapped and can service queries. Callers
     * check this before accepting an empty {@link #resolveFor(URL)}
     * result as "unknown extension" — a store that never came up would
     * otherwise appear indistinguishable from a store that came up with
     * no policy data.
     */
    boolean isReady();

    /**
     * Capability-ask wave — record the extension's declared ask into
     * the dedicated ask named graph
     * ({@link CapabilityVocabulary#CAP_ASKS_NAMED_GRAPH}) so the admin
     * can diff it against grants with a SPARQL query
     * ({@code capability-ask.md} §7).
     *
     * <p>Overwrite semantics: on each call, any prior ask triples keyed
     * on {@code extensionUrl} in the ask named graph are removed before
     * the new ones land ({@code capability-ask.md} §13's collision rule
     * — latest wins). Grants in the default graph are untouched.
     *
     * <p>Best-effort: implementations swallow write failures and log
     * rather than throw. Ask insertion is diagnostic; grant resolution
     * still runs even when ask writing fails ({@code capability-ask.md}
     * §6's "cannot write the ask" branch).
     *
     * <p>Default implementation is a no-op — in-memory / test fakes get
     * a working store surface without having to implement the ask path.
     */
    default void recordAsk(URL extensionUrl, CapabilityAsk ask) {
        // No-op default. Kernel-backed implementation overrides.
    }

    /**
     * Look up the ask previously recorded for {@code extensionUrl}.
     * Returns {@link Optional#empty()} when no ask is on file (extension
     * never loaded, or shipped without a {@code stardog.capability-ask}
     * custom section). Used by admin tooling and by the warn-on-
     * undeclared diagnostic in {@link HostCallbacks} when the runtime
     * needs to check whether a granted dispatch was declared.
     *
     * <p>Default implementation returns empty — matches the no-op
     * {@link #recordAsk} default so a test store that doesn't
     * participate in ask reporting still satisfies the interface.
     */
    default Optional<CapabilityAsk> loadAskFor(URL extensionUrl) {
        return Optional.empty();
    }
}
