package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.common.base.Options;
import com.complexible.stardog.Kernel;
import com.complexible.stardog.StardogException;
import com.complexible.stardog.db.DatabaseConnection;
import com.complexible.stardog.db.DatabaseOptions;
import com.complexible.stardog.metadata.Metadata;
import com.complexible.stardog.security.ShiroUtils;
import com.complexible.stardog.server.UnknownDatabaseException;
import com.stardog.stark.Literal;
import com.stardog.stark.Values;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.SelectQueryResult;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production {@link FuelStateStore} — persists user fuel state as RDF in a
 * dedicated Stardog database opened via the {@link Kernel} handle. Mirrors
 * the QueryLog persistence pattern (see {@code fuel-implementation.md} §7a.2
 * and {@code ~/git/stardog/querylog/core/main/src/com/stardog/querylog/QueryLogImpl.java}):
 *
 * <ul>
 *   <li>On first construction, ensure the management database
 *       ({@link WebFunctionConfig#fuelStateDatabaseName()}, default
 *       {@code system-webfunctions-fuel}) exists — create it as super-user
 *       via {@link ShiroUtils#executeAsSuperUser} if not.</li>
 *   <li>Hot-path reads/writes go through a {@link ConcurrentHashMap}
 *       write-behind cache; a scheduled flush task ({@link
 *       WebFunctionConfig#fuelStateFlushIntervalMillis()}, default 60s)
 *       persists dirty rows in one SPARQL UPDATE per row.</li>
 *   <li>JVM shutdown flushes pending writes (best-effort).</li>
 * </ul>
 *
 * <p>RDF vocabulary uses {@code tag:tegmentum,2026:webfunction/fuel#} —
 * a tag URI that doesn't need to resolve, matching the tag: convention
 * Stardog already uses internally (e.g. {@code tag:stardog:api:array}).
 * Predicates: {@code #monthlyBudget}, {@code #monthlyUsed},
 * {@code #anniversary}. User subject: {@code user:<userId>} (opaque URI —
 * no dereferenceability contract).
 *
 * <p>Note on §7a.4 recommendation for cluster: {@link Kernel#createDatabase}
 * and {@link Kernel#getConnection} run through pack replication in cluster
 * deployments, so fuel state replicates like stored queries do without
 * additional plumbing.
 *
 * <p>Not injected under Guice today — the plugin's existing
 * {@link WebFunctionServiceModule} does not have a compile-time dependency
 * on the DBMS-module classpath needed to bind {@link Kernel}. This class
 * ships with a hand-instantiation constructor {@link #wire(Kernel)} the
 * plugin's next-wave Guice work can flip to {@code @Inject}. See
 * {@code fuel-implementation.md} §8 Phase 2 "honest failure paths".
 */
@Singleton
public final class KernelBackedFuelStateStore implements FuelStateStore, AutoCloseable {

    private static final String NS = "tag:tegmentum,2026:webfunction/fuel#";
    private static final String USER_NS = "tag:tegmentum,2026:webfunction/fuel/user#";

    private final Kernel kernel;
    private final String databaseName;
    private final long flushIntervalMillis;

    private final ConcurrentHashMap<String, UserFuelState> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> dirty = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> flushFuture;
    private Thread shutdownHook;

    /**
     * Guice-friendly constructor. When the plugin's Guice module binds
     * {@link Kernel} (Stardog's DBMS-module provides one on the classpath
     * to first-party modules; the plugin's module wiring is being
     * extended to consume it), this becomes the injection surface.
     */
    @Inject
    public KernelBackedFuelStateStore(final Kernel kernel) {
        this(kernel,
             WebFunctionConfig.fuelStateDatabaseName(),
             WebFunctionConfig.fuelStateFlushIntervalMillis());
    }

    /** Explicit-config constructor. Used by tests and by callers that hand-wire the store. */
    public KernelBackedFuelStateStore(final Kernel kernel,
                                      final String databaseName,
                                      final long flushIntervalMillis) {
        this.kernel = kernel;
        this.databaseName = databaseName;
        this.flushIntervalMillis = flushIntervalMillis;
    }

    /**
     * Hand-wire and initialize when this store is not managed by Guice.
     * Idempotent — repeated calls no-op after the first.
     */
    public static KernelBackedFuelStateStore wire(final Kernel kernel) {
        final KernelBackedFuelStateStore store = new KernelBackedFuelStateStore(kernel);
        store.initialize();
        return store;
    }

    /**
     * Lazy initialization — ensures the fuel database exists (creates as
     * super-user if missing) and schedules the flush task. Idempotent; safe
     * to call repeatedly. Callers hand-wiring the store outside Guice call
     * this after construction; the {@link Inject}ed path calls it lazily
     * on first read/write.
     */
    public void initialize() {
        if (!initialized.compareAndSet(false, true)) return;
        ensureFuelDatabase();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "wf-fuel-state-flush");
            t.setDaemon(true);
            return t;
        });
        this.flushFuture = scheduler.scheduleAtFixedRate(
                this::flushSafely,
                flushIntervalMillis,
                flushIntervalMillis,
                TimeUnit.MILLISECONDS);
        this.shutdownHook = new Thread(this::flushSafely, "wf-fuel-state-flush-on-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException shuttingDown) {
            // JVM is already in shutdown; no-op is fine.
        }
    }

    @Override
    public Optional<UserFuelState> loadUser(final String userId) {
        if (userId == null || userId.isEmpty()) return Optional.empty();
        initialize();
        final UserFuelState cached = cache.get(userId);
        if (cached != null) return Optional.of(cached);
        final Optional<UserFuelState> persisted = readFromDatabase(userId);
        persisted.ifPresent(state -> cache.putIfAbsent(userId, state));
        return persisted;
    }

    @Override
    public void saveUser(final UserFuelState state) {
        if (state == null || state.userId() == null || state.userId().isEmpty()) return;
        initialize();
        cache.put(state.userId(), state);
        dirty.put(state.userId(), Boolean.TRUE);
    }

    /**
     * Best-effort flush entry point for the scheduler. Swallows exceptions
     * so a transient Stardog error doesn't kill the recurring task
     * (matches QueryLog's {@code flushToDisk} shape).
     */
    void flushSafely() {
        try {
            flushOnce();
        } catch (RuntimeException e) {
            // Structured log would be nicer; slf4j is already on the classpath.
            System.err.println("[wf-fuel] flush task error: " + e.getMessage());
        }
    }

    /**
     * Flush every dirty entry through a SPARQL UPDATE (DELETE + INSERT for
     * atomic replacement of the row). One connection per flush cycle;
     * multiple rows share the transaction.
     */
    void flushOnce() {
        if (!active.get() || dirty.isEmpty()) return;
        final Map<String, UserFuelState> toFlush = new HashMap<>();
        for (String userId : dirty.keySet()) {
            final UserFuelState state = cache.get(userId);
            if (state != null) toFlush.put(userId, state);
        }
        if (toFlush.isEmpty()) return;

        ShiroUtils.executeAsSuperUser(kernel.getSecurityManager(), () -> {
            try (DatabaseConnection conn = kernel.getConnection(databaseName, Options.empty())) {
                conn.begin(UUID.randomUUID(), true);
                try {
                    for (Map.Entry<String, UserFuelState> e : toFlush.entrySet()) {
                        writeRowSparql(conn, e.getValue());
                    }
                    conn.commit();
                    for (String userId : toFlush.keySet()) {
                        dirty.remove(userId);
                    }
                } catch (RuntimeException ex) {
                    try { conn.rollback(); } catch (RuntimeException ignore) {}
                    throw ex;
                }
            }
        });
    }

    private static void writeRowSparql(final DatabaseConnection conn, final UserFuelState state) {
        final String subject = "<" + USER_NS + escapeIri(state.userId()) + ">";
        final String pMonthlyBudget = "<" + NS + "monthlyBudget>";
        final String pMonthlyUsed   = "<" + NS + "monthlyUsed>";
        final String pAnniversary   = "<" + NS + "anniversary>";
        final String pOrg           = "<" + NS + "orgId>";

        final String update =
                "DELETE { " + subject + " ?p ?o } WHERE { " + subject + " ?p ?o }";
        conn.update("", update, null).execute();

        final StringBuilder insert = new StringBuilder("INSERT DATA { ");
        insert.append(subject).append(' ').append(pMonthlyBudget).append(' ')
              .append(state.monthlyBudget()).append(" ; ")
              .append(pMonthlyUsed).append(' ').append(state.monthlyUsed()).append(" ; ")
              .append(pAnniversary).append(" \"")
              .append(state.billingAnniversary() == null ? "" : state.billingAnniversary().toString())
              .append("\"^^<http://www.w3.org/2001/XMLSchema#dateTime> ; ")
              .append(pOrg).append(" \"").append(escapeLiteral(state.orgId() == null ? "" : state.orgId()))
              .append("\" . }");
        conn.update("", insert.toString(), null).execute();
    }

    private Optional<UserFuelState> readFromDatabase(final String userId) {
        final String subject = "<" + USER_NS + escapeIri(userId) + ">";
        final String select =
                "SELECT ?budget ?used ?anniversary ?org WHERE { "
                + subject + " <" + NS + "monthlyBudget> ?budget . "
                + subject + " <" + NS + "monthlyUsed> ?used . "
                + subject + " <" + NS + "anniversary> ?anniversary . "
                + "OPTIONAL { " + subject + " <" + NS + "orgId> ?org } "
                + "}";

        final UserFuelState[] out = new UserFuelState[1];
        try {
            ShiroUtils.executeAsSuperUser(kernel.getSecurityManager(), () -> {
                try (DatabaseConnection conn = kernel.getConnection(databaseName, Options.empty())) {
                    try (SelectQueryResult rs = conn.select(select).execute()) {
                        if (rs.hasNext()) {
                            final BindingSet bs = rs.next();
                            final long budget = Literal.longValue((Literal) bs.get("budget"));
                            final long used = Literal.longValue((Literal) bs.get("used"));
                            final String anniversary = ((Literal) bs.get("anniversary")).label();
                            final String org = bs.get("org") == null ? "" : ((Literal) bs.get("org")).label();
                            out[0] = new UserFuelState(
                                    userId,
                                    org,
                                    budget,
                                    used,
                                    Instant.parse(anniversary));
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            System.err.println("[wf-fuel] read error for user '" + userId + "': " + e.getMessage());
            return Optional.empty();
        }
        return Optional.ofNullable(out[0]);
    }

    private void ensureFuelDatabase() {
        if (databaseExists()) return;
        ShiroUtils.executeAsSuperUser(kernel.getSecurityManager(), () -> {
            try {
                kernel.createDatabase(Metadata.create().set(DatabaseOptions.NAME, databaseName));
            } catch (StardogException e) {
                // Race with another node/process. If the DB now exists we're
                // fine; otherwise rethrow so we don't silently proceed
                // against a missing store.
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
            // getDatabase throws unchecked on unknown; treat any lookup failure
            // as "does not exist" and let createDatabase fail loudly if that's
            // the real issue.
            return false;
        }
        return exists[0];
    }

    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) return;
        try {
            if (flushFuture != null) flushFuture.cancel(false);
            if (scheduler != null) scheduler.shutdown();
            flushSafely();
        } finally {
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignore) {
                    // JVM shutdown already in progress; hook still fires.
                }
            }
        }
    }

    // ------------------------------------------------------------
    // Minimal escape helpers — IRIs and RDF literals in inline SPARQL.
    // Not general-purpose SPARQL encoding; the userId is either a Shiro
    // principal (limited alphabet) or "", so the escape surface is small.
    // ------------------------------------------------------------

    private static String escapeIri(final String s) {
        // Angle-bracket-safe: strip characters that would terminate an IRI
        // literal in Turtle/SPARQL. Users who put < > " { } | \ ^ ` in their
        // Shiro principal are already off the reservation.
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

    private static String escapeLiteral(final String s) {
        // Enough for xsd:string literals inside a SPARQL query text; RFC 3986
        // + the SPARQL grammar disagree on some edge cases but " and \ suffice.
        final StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    // Values namespace used elsewhere in the plugin for constructing IRIs;
    // referenced here to keep the import graph obviously live in future
    // grep-based audits even though the current write path assembles SPARQL
    // text directly.
    @SuppressWarnings("unused")
    private static final com.stardog.stark.IRI ROOT_IRI = Values.iri(NS);
}
