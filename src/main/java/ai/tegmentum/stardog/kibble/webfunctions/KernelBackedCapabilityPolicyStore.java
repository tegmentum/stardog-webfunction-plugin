package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.common.base.Options;
import com.complexible.stardog.Kernel;
import com.complexible.stardog.StardogException;
import com.complexible.stardog.db.DatabaseConnection;
import com.complexible.stardog.db.DatabaseOptions;
import com.complexible.stardog.metadata.Metadata;
import com.complexible.stardog.security.ShiroUtils;
import com.complexible.stardog.server.UnknownDatabaseException;
import com.stardog.stark.IRI;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.SelectQueryResult;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production {@link CapabilityPolicyStore} — reads policy triples from a
 * dedicated Stardog database opened via the {@link Kernel} handle.
 *
 * <p>Mirrors {@link KernelBackedFuelStateStore}'s pattern:
 * <ul>
 *   <li>On first construction, ensure the capability database
 *       ({@link WebFunctionConfig#capabilityPolicyDatabaseName()},
 *       default {@code system-webfunctions-capability}) exists — create
 *       it as super-user via {@link ShiroUtils#executeAsSuperUser} if
 *       not.</li>
 *   <li>Per-invocation reads (no cache) per capability implementation
 *       memo §13: admin edits to policy triples take effect on the next
 *       extension instantiation without waiting for a cache TTL.</li>
 * </ul>
 *
 * <p>The SELECT the store issues per instantiation:
 * <pre>
 * PREFIX cap: &lt;http://semantalytics.com/2021/03/ns/stardog/webfunction/capability#&gt;
 * SELECT ?interface ?method ?host WHERE {
 *   &lt;ipfs://Qm...&gt; cap:trusted true ;
 *                    cap:allowInterface ?interface .
 *   OPTIONAL { &lt;ipfs://Qm...&gt; cap:allowMethod ?method . }
 *   OPTIONAL { &lt;ipfs://Qm...&gt; cap:allowHost ?host . }
 * }
 * </pre>
 *
 * <p>Zero rows → unknown extension; the resolver consults the unknown-
 * extension policy config on that branch. Non-empty rows → the resolver
 * intersects the projected {@link PolicyTriples} with the component's
 * declared imports to produce the effective grant.
 */
@Singleton
public final class KernelBackedCapabilityPolicyStore implements CapabilityPolicyStore {

    private final Kernel kernel;
    private final String databaseName;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    /**
     * Default management database name — {@code system-webfunctions-capability}.
     * R7 (config keys sub-phase) will move this constant into
     * {@link WebFunctionConfig} once the system-property surface lands.
     */
    public static final String DEFAULT_DATABASE_NAME = "system-webfunctions-capability";

    /**
     * Guice-friendly constructor. Reads the default management-database
     * name — R7 flips this to read from
     * {@link WebFunctionConfig#capabilityPolicyDatabaseName()} once the
     * system-property surface lands so a deployment that wants a
     * differently-named policy DB can override.
     */
    @Inject
    public KernelBackedCapabilityPolicyStore(final Kernel kernel) {
        this(kernel, DEFAULT_DATABASE_NAME);
    }

    /**
     * Explicit-config constructor. Used by tests and by callers that
     * hand-wire the store with a non-default database name.
     */
    public KernelBackedCapabilityPolicyStore(final Kernel kernel,
                                             final String databaseName) {
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.databaseName = Objects.requireNonNull(databaseName, "databaseName");
    }

    /**
     * Hand-wire and initialize when this store is not managed by Guice.
     * Idempotent — repeated calls no-op after the first.
     */
    public static KernelBackedCapabilityPolicyStore wire(final Kernel kernel) {
        final KernelBackedCapabilityPolicyStore store =
                new KernelBackedCapabilityPolicyStore(kernel);
        store.initialize();
        return store;
    }

    /**
     * Lazy initialization — ensures the capability database exists
     * (creates as super-user if missing) and flips {@link #isReady()}
     * true. Idempotent; safe to call repeatedly.
     */
    public void initialize() {
        if (ready.get()) return;
        ensureDatabase();
        ready.set(true);
    }

    @Override
    public boolean isReady() {
        return ready.get();
    }

    @Override
    public Optional<PolicyTriples> resolveFor(final URL extensionUrl) {
        if (extensionUrl == null) return Optional.of(PolicyTriples.EMPTY);
        initialize();
        if (!ready.get()) return Optional.empty();
        try {
            return Optional.of(readTriples(extensionUrl.toString()));
        } catch (RuntimeException e) {
            // A transient read failure is a store-unavailable signal; the
            // resolver surfaces this as PolicyStoreUnavailable via its
            // caller.
            return Optional.empty();
        }
    }

    private PolicyTriples readTriples(final String extensionUri) {
        final String subject = "<" + escapeIri(extensionUri) + ">";
        final String select =
                "SELECT ?interface ?method ?host WHERE { "
                + subject + " <" + CapabilityVocabulary.CAP_TRUSTED + "> true . "
                + subject + " <" + CapabilityVocabulary.CAP_ALLOW_INTERFACE + "> ?interface . "
                + "OPTIONAL { " + subject + " <"
                + CapabilityVocabulary.CAP_ALLOW_METHOD + "> ?method . } "
                + "OPTIONAL { " + subject + " <"
                + CapabilityVocabulary.CAP_ALLOW_HOST + "> ?host . } "
                + "}";

        final Set<String> ifaces  = new LinkedHashSet<>();
        final Set<String> methods = new LinkedHashSet<>();
        final Set<String> hosts   = new LinkedHashSet<>();

        ShiroUtils.executeAsSuperUser(kernel.getSecurityManager(), () -> {
            try (DatabaseConnection conn = kernel.getConnection(databaseName, Options.empty());
                 SelectQueryResult rs = conn.select(select).execute()) {
                while (rs.hasNext()) {
                    final BindingSet bs = rs.next();
                    final Object iface  = bs.get("interface");
                    final Object method = bs.get("method");
                    final Object host   = bs.get("host");
                    if (iface instanceof IRI) {
                        final String wire = CapabilityVocabulary.wireNameFor(iface.toString());
                        ifaces.add(wire != null ? wire : iface.toString());
                    } else if (iface != null) {
                        ifaces.add(iface.toString());
                    }
                    if (method != null) {
                        methods.add(literalOrIriString(method));
                    }
                    if (host != null) {
                        hosts.add(literalOrIriString(host));
                    }
                }
            }
        });

        if (ifaces.isEmpty() && methods.isEmpty() && hosts.isEmpty()) {
            return PolicyTriples.EMPTY;
        }
        return new PolicyTriples(ifaces, methods, hosts);
    }

    private static String literalOrIriString(final Object v) {
        if (v instanceof com.stardog.stark.Literal) {
            return ((com.stardog.stark.Literal) v).label();
        }
        return v.toString();
    }

    private void ensureDatabase() {
        if (databaseExists()) return;
        ShiroUtils.executeAsSuperUser(kernel.getSecurityManager(), () -> {
            try {
                kernel.createDatabase(Metadata.create()
                        .set(DatabaseOptions.NAME, databaseName));
            } catch (StardogException e) {
                // Race with another node/process — if the DB now exists
                // we're fine; otherwise rethrow so we don't silently
                // proceed against a missing store.
                if (!databaseExists()) throw e;
            }
        });
    }

    private boolean databaseExists() {
        final boolean[] exists = {false};
        try {
            ShiroUtils.executeAsSuperUser(kernel.getSecurityManager(),
                    () -> exists[0] = kernel.getDatabase(databaseName) != null);
        } catch (UnknownDatabaseException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
        return exists[0];
    }

    /**
     * Minimal IRI-terminator strip so a maliciously-shaped extension URL
     * cannot escape the enclosing angle brackets. Same shape as
     * {@link KernelBackedFuelStateStore#escapeIri(String)} — the URL is
     * either a well-formed IPFS/HTTP URL or a file:// URL from tests, so
     * the escape surface is small.
     */
    static String escapeIri(final String s) {
        final StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c > 0x20 && c != '<' && c != '>' && c != '"'
                    && c != '{' && c != '}' && c != '|'
                    && c != '\\' && c != '^' && c != '`') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.toString();
    }
}
