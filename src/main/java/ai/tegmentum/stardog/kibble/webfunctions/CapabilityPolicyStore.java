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
}
