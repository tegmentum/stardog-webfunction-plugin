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
 * SELECT ?interface ?method ?host ?httpPath ?wasmCallee WHERE {
 *   &lt;ipfs://Qm...&gt; cap:trusted true ;
 *                    cap:allowInterface ?interface .
 *   OPTIONAL { &lt;ipfs://Qm...&gt; cap:allowMethod ?method . }
 *   OPTIONAL { &lt;ipfs://Qm...&gt; cap:allowHost ?host . }
 *   OPTIONAL { &lt;ipfs://Qm...&gt; cap:allowHttpPath ?httpPath . }
 *   OPTIONAL { &lt;ipfs://Qm...&gt; cap:allowWasmCallee ?wasmCallee . }
 * }
 * </pre>
 *
 * <p>Phase 5 axes {@code ?httpPath} and {@code ?wasmCallee} project into
 * the corresponding fields on {@link PolicyTriples}; the resolver wraps
 * those into {@link HttpPathAllowlist} + {@link WasmCalleeAllowlist} on
 * the grant.
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
     * Default management database name mirrors
     * {@link WebFunctionConfig#DEFAULT_CAPABILITY_POLICY_STORE_DATABASE}.
     * Kept as a class-level constant so tests can assert on it without
     * touching config.
     */
    public static final String DEFAULT_DATABASE_NAME =
            WebFunctionConfig.DEFAULT_CAPABILITY_POLICY_STORE_DATABASE;

    /**
     * Guice-friendly constructor. Reads
     * {@link WebFunctionConfig#capabilityPolicyDatabaseName()} for the
     * management database name so a deployment that wants a differently-
     * named policy DB overrides via system property.
     */
    @Inject
    public KernelBackedCapabilityPolicyStore(final Kernel kernel) {
        this(kernel, WebFunctionConfig.capabilityPolicyDatabaseName());
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

    // ---- capability-ask wave -------------------------------------------

    /**
     * Overwrite-then-insert the extension's declared ask under the
     * dedicated ask named graph. Wrapped in
     * {@link ShiroUtils#executeAsSuperUser} so the write goes through
     * regardless of the invoker's Shiro subject — matches the
     * {@link KernelBackedFuelStateStore#writeRowSparql} pattern.
     *
     * <p>Best-effort per {@code capability-ask.md} §6: any write
     * failure is logged (stderr, same convention the rest of the store
     * uses on bootstrap paths) and swallowed. The extension load
     * continues and grant resolution takes over.
     */
    @Override
    public void recordAsk(final URL extensionUrl, final CapabilityAsk ask) {
        if (extensionUrl == null || ask == null) return;
        initialize();
        if (!ready.get()) return;
        final String subject = "<" + escapeIri(extensionUrl.toString()) + ">";
        final String askDocIri = "<" + escapeIri(extensionUrl.toString()) + "#ask>";
        final String graph = "<" + CapabilityVocabulary.CAP_ASKS_NAMED_GRAPH + ">";

        // DELETE anything previously written for this extension in the
        // ask graph. Two subjects to clean: the extension URL itself
        // (cap:hasAsk) and the ask document IRI (all cap:asks* triples
        // rooted there). One WHERE-clause DELETE covers both via a
        // UNION so a single UPDATE round-trip suffices.
        final String delete =
                "DELETE { GRAPH " + graph + " { ?s ?p ?o } } "
                + "WHERE { GRAPH " + graph + " { "
                + "{ " + subject + " ?p ?o BIND(" + subject + " AS ?s) } "
                + "UNION "
                + "{ " + askDocIri + " ?p ?o BIND(" + askDocIri + " AS ?s) } "
                + "} }";

        // INSERT DATA — flat triple list, one predicate per axis. Empty
        // axes contribute nothing. cap:hasAsk links extension → ask
        // document; cap:CapabilityAsk classifies the document.
        final StringBuilder insert = new StringBuilder(
                "INSERT DATA { GRAPH " + graph + " { ");
        insert.append(subject).append(" <")
              .append(CapabilityVocabulary.CAP_HAS_ASK)
              .append("> ").append(askDocIri).append(" . ");
        insert.append(askDocIri).append(" a <")
              .append(CapabilityVocabulary.CAP_CAPABILITY_ASK).append("> . ");
        for (final String iri : ask.asksInterfaces()) {
            insert.append(askDocIri).append(" <")
                  .append(CapabilityVocabulary.CAP_ASKS_INTERFACE)
                  .append("> ");
            appendIriOrLiteral(insert, iri);
            insert.append(" . ");
        }
        for (final String iri : ask.asksMethods()) {
            insert.append(askDocIri).append(" <")
                  .append(CapabilityVocabulary.CAP_ASKS_METHOD)
                  .append("> ");
            appendIriOrLiteral(insert, iri);
            insert.append(" . ");
        }
        for (final String host : ask.asksHosts()) {
            insert.append(askDocIri).append(" <")
                  .append(CapabilityVocabulary.CAP_ASKS_HOST)
                  .append("> \"").append(escapeLiteral(host)).append("\" . ");
        }
        for (final String path : ask.asksHttpPaths()) {
            insert.append(askDocIri).append(" <")
                  .append(CapabilityVocabulary.CAP_ASKS_HTTP_PATH)
                  .append("> \"").append(escapeLiteral(path)).append("\" . ");
        }
        for (final String callee : ask.asksWasmCallees()) {
            insert.append(askDocIri).append(" <")
                  .append(CapabilityVocabulary.CAP_ASKS_WASM_CALLEE)
                  .append("> ");
            appendIriOrLiteral(insert, callee);
            insert.append(" . ");
        }
        if (ask.rationale().isPresent()) {
            insert.append(askDocIri).append(" <")
                  .append(CapabilityVocabulary.CAP_ASKS_RATIONALE)
                  .append("> \"")
                  .append(escapeLiteral(ask.rationale().get()))
                  .append("\" . ");
        }
        insert.append("} }");

        try {
            ShiroUtils.executeAsSuperUser(kernel.getSecurityManager(), () -> {
                try (DatabaseConnection conn = kernel.getConnection(databaseName, Options.empty())) {
                    conn.begin(java.util.UUID.randomUUID(), true);
                    try {
                        conn.update("", delete, null).execute();
                        conn.update("", insert.toString(), null).execute();
                        conn.commit();
                    } catch (RuntimeException ex) {
                        try { conn.rollback(); } catch (RuntimeException ignore) {}
                        throw ex;
                    }
                }
            });
        } catch (RuntimeException e) {
            // Best-effort per capability-ask.md §6 — log and proceed.
            System.err.println("[wf-cap-ask] recordAsk failed for "
                    + extensionUrl + ": " + e.getMessage());
        }
    }

    /**
     * Read back the ask previously recorded for {@code extensionUrl}
     * from the ask named graph. Returns {@link Optional#empty()} when
     * no ask is on file. Used by admin tooling; the runtime warn-on-
     * undeclared diagnostic reads the ask off {@link CallbackContext}
     * (stamped at load time) rather than round-tripping through this
     * lookup on every dispatch.
     */
    @Override
    public Optional<CapabilityAsk> loadAskFor(final URL extensionUrl) {
        if (extensionUrl == null) return Optional.empty();
        initialize();
        if (!ready.get()) return Optional.empty();
        try {
            return readAsk(extensionUrl.toString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<CapabilityAsk> readAsk(final String extensionUri) {
        final String subject = "<" + escapeIri(extensionUri) + ">";
        final String askDocIri = "<" + escapeIri(extensionUri) + "#ask>";
        final String graph = "<" + CapabilityVocabulary.CAP_ASKS_NAMED_GRAPH + ">";
        final String select =
                "SELECT ?p ?o WHERE { "
                + "GRAPH " + graph + " { "
                + subject + " <" + CapabilityVocabulary.CAP_HAS_ASK + "> " + askDocIri + " . "
                + askDocIri + " ?p ?o . "
                + "} }";

        final java.util.Set<String> interfaces  = new java.util.LinkedHashSet<>();
        final java.util.Set<String> methods     = new java.util.LinkedHashSet<>();
        final java.util.Set<String> hosts       = new java.util.LinkedHashSet<>();
        final java.util.Set<String> httpPaths   = new java.util.LinkedHashSet<>();
        final java.util.Set<String> wasmCallees = new java.util.LinkedHashSet<>();
        final String[] rationale = new String[]{null};
        final boolean[] anyRow = new boolean[]{false};

        ShiroUtils.executeAsSuperUser(kernel.getSecurityManager(), () -> {
            try (DatabaseConnection conn = kernel.getConnection(databaseName, Options.empty());
                 SelectQueryResult rs = conn.select(select).execute()) {
                while (rs.hasNext()) {
                    final BindingSet bs = rs.next();
                    final Object p = bs.get("p");
                    final Object o = bs.get("o");
                    if (p == null || o == null) continue;
                    anyRow[0] = true;
                    final String pIri = p.toString();
                    final String oStr = literalOrIriString(o);
                    if (CapabilityVocabulary.CAP_ASKS_INTERFACE.equals(pIri)) {
                        interfaces.add(oStr);
                    } else if (CapabilityVocabulary.CAP_ASKS_METHOD.equals(pIri)) {
                        methods.add(oStr);
                    } else if (CapabilityVocabulary.CAP_ASKS_HOST.equals(pIri)) {
                        hosts.add(oStr);
                    } else if (CapabilityVocabulary.CAP_ASKS_HTTP_PATH.equals(pIri)) {
                        httpPaths.add(oStr);
                    } else if (CapabilityVocabulary.CAP_ASKS_WASM_CALLEE.equals(pIri)) {
                        wasmCallees.add(oStr);
                    } else if (CapabilityVocabulary.CAP_ASKS_RATIONALE.equals(pIri)) {
                        rationale[0] = oStr;
                    }
                }
            }
        });

        if (!anyRow[0]) return Optional.empty();
        return Optional.of(new CapabilityAsk(
                interfaces, methods, hosts, httpPaths, wasmCallees,
                Optional.ofNullable(rationale[0])));
    }

    /**
     * Format {@code s} as either an IRI (wrapped in {@code <...>}) or a
     * plain string literal — heuristic based on whether {@code s}
     * contains a colon before any whitespace, which matches every
     * scheme URL and vocabulary IRI the ask model ships.
     */
    private static void appendIriOrLiteral(final StringBuilder out, final String s) {
        if (looksLikeIri(s)) {
            out.append('<').append(escapeIri(s)).append('>');
        } else {
            out.append('"').append(escapeLiteral(s)).append('"');
        }
    }

    private static boolean looksLikeIri(final String s) {
        if (s == null || s.isEmpty()) return false;
        final int colon = s.indexOf(':');
        if (colon <= 0) return false;
        // Whitespace before the colon rules out an IRI.
        for (int i = 0; i < colon; i++) {
            final char c = s.charAt(i);
            if (Character.isWhitespace(c)) return false;
        }
        return true;
    }

    /**
     * Minimal string-literal escape for the inline INSERT DATA payload
     * — mirrors {@link KernelBackedFuelStateStore#escapeLiteral(String)}'s
     * shape. Handles backslash, double-quote, and control characters.
     */
    static String escapeLiteral(final String s) {
        final StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"':  out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    // ---- grant-side read path (unchanged) ------------------------------

    private PolicyTriples readTriples(final String extensionUri) {
        final String subject = "<" + escapeIri(extensionUri) + ">";
        final String select =
                "SELECT ?interface ?method ?host ?httpPath ?wasmCallee WHERE { "
                + subject + " <" + CapabilityVocabulary.CAP_TRUSTED + "> true . "
                + subject + " <" + CapabilityVocabulary.CAP_ALLOW_INTERFACE + "> ?interface . "
                + "OPTIONAL { " + subject + " <"
                + CapabilityVocabulary.CAP_ALLOW_METHOD + "> ?method . } "
                + "OPTIONAL { " + subject + " <"
                + CapabilityVocabulary.CAP_ALLOW_HOST + "> ?host . } "
                + "OPTIONAL { " + subject + " <"
                + CapabilityVocabulary.CAP_ALLOW_HTTP_PATH + "> ?httpPath . } "
                + "OPTIONAL { " + subject + " <"
                + CapabilityVocabulary.CAP_ALLOW_WASM_CALLEE + "> ?wasmCallee . } "
                + "}";

        final Set<String> ifaces      = new LinkedHashSet<>();
        final Set<String> methods     = new LinkedHashSet<>();
        final Set<String> hosts       = new LinkedHashSet<>();
        final Set<String> httpPaths   = new LinkedHashSet<>();
        final Set<String> wasmCallees = new LinkedHashSet<>();

        ShiroUtils.executeAsSuperUser(kernel.getSecurityManager(), () -> {
            try (DatabaseConnection conn = kernel.getConnection(databaseName, Options.empty());
                 SelectQueryResult rs = conn.select(select).execute()) {
                while (rs.hasNext()) {
                    final BindingSet bs = rs.next();
                    final Object iface      = bs.get("interface");
                    final Object method     = bs.get("method");
                    final Object host       = bs.get("host");
                    final Object httpPath   = bs.get("httpPath");
                    final Object wasmCallee = bs.get("wasmCallee");
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
                    if (httpPath != null) {
                        httpPaths.add(literalOrIriString(httpPath));
                    }
                    if (wasmCallee != null) {
                        // cap:allowWasmCallee is expected to be an IRI in the
                        // policy triples (per the wave 5 vocabulary lock-in),
                        // but literalOrIriString handles either shape so the
                        // store is tolerant of admin-authored TTL that uses a
                        // string literal.
                        wasmCallees.add(literalOrIriString(wasmCallee));
                    }
                }
            }
        });

        if (ifaces.isEmpty() && methods.isEmpty() && hosts.isEmpty()
                && httpPaths.isEmpty() && wasmCallees.isEmpty()) {
            return PolicyTriples.EMPTY;
        }
        return new PolicyTriples(ifaces, methods, hosts, httpPaths, wasmCallees);
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
