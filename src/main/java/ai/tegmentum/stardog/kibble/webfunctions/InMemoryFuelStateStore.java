package ai.tegmentum.stardog.kibble.webfunctions;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link FuelStateStore} — a {@link ConcurrentHashMap} keyed by
 * userId. Used by unit tests and available as an MVP fallback when the
 * Kernel-backed store is unwired (see {@code fuel-implementation.md} §11
 * open question "Store implementation identity for §7 interim").
 *
 * <p>State does NOT survive JVM restart. Documented as evaluation-only for
 * production quota; the intended production impl is
 * {@link KernelBackedFuelStateStore}.
 */
public final class InMemoryFuelStateStore implements FuelStateStore {

    private final Map<String, UserFuelState> users = new ConcurrentHashMap<>();

    @Override
    public Optional<UserFuelState> loadUser(final String userId) {
        if (userId == null) return Optional.empty();
        return Optional.ofNullable(users.get(userId));
    }

    @Override
    public void saveUser(final UserFuelState state) {
        if (state == null || state.userId() == null || state.userId().isEmpty()) return;
        users.put(state.userId(), state);
    }
}
