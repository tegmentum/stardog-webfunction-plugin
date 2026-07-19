package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.api.Connection;
import com.complexible.stardog.api.ConnectionConfiguration;
import com.complexible.stardog.api.admin.AdminConnection;
import com.complexible.stardog.api.admin.AdminConnectionConfiguration;
import com.stardog.stark.Value;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.SelectQueryResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SPARQL helpers for driving the {@code system-webfunctions-capability}
 * management database from integration-test setup. Mirrors the shape of
 * SPARQL {@code KernelBackedCapabilityPolicyStore} issues so the tests
 * poke the same store the plugin reads, without any Kernel-level
 * private API.
 *
 * <p>All methods use hard-coded IRIs — no injection surface, because
 * the integration tests own every input.
 *
 * <p>Not part of the shipped plugin; test-only utility, kept here so
 * the reasoning about which vocabulary IRIs to use lives next to the
 * tests that consume them.
 */
final class CapabilityPolicyDbHelpers {

    /**
     * Name of the plugin-managed policy database. Mirrors
     * {@link WebFunctionConfig#DEFAULT_CAPABILITY_POLICY_STORE_DATABASE}.
     * The plugin's {@code CapabilityPolicyStarter} auto-creates this
     * database at Kernel install time, so tests can begin issuing
     * INSERT DATA queries against it immediately after the container
     * is ready.
     */
    static final String POLICY_DB = WebFunctionConfig.DEFAULT_CAPABILITY_POLICY_STORE_DATABASE;

    /**
     * SPARQL prefix declaration matching {@link CapabilityVocabulary#NAMESPACE}.
     * Prepend to every SPARQL string this helper issues so admin-authored
     * queries in the tests read like admin-facing docs.
     */
    static final String CAP_PREFIX =
            "PREFIX cap: <" + CapabilityVocabulary.NAMESPACE + ">\n";

    private CapabilityPolicyDbHelpers() {}

    /**
     * INSERT policy triples granting the given interfaces + methods to
     * an extension URL. Methods are string literals of shape
     * {@code "interface/method"} — matches the
     * {@link CapabilityPolicyResolver#methodPoliciesFromTriples} split
     * rule (the plugin currently accepts either literal or IRI shapes).
     */
    static void grantInterfaces(final String serverUrl,
                                final String extensionUrl,
                                final List<String> interfaceIris,
                                final List<String> methodLiterals) {
        final StringBuilder q = new StringBuilder(CAP_PREFIX);
        q.append("INSERT DATA { <").append(extensionUrl).append("> cap:trusted true ");
        for (final String iface : interfaceIris) {
            q.append("; cap:allowInterface <").append(iface).append("> ");
        }
        for (final String method : methodLiterals) {
            q.append("; cap:allowMethod \"").append(method).append("\" ");
        }
        q.append(". }");

        executeUpdate(serverUrl, POLICY_DB, q.toString());
    }

    /**
     * DELETE every triple from the policy DB that has {@code extensionUrl}
     * as subject in either the default graph (grant triples) or the ask
     * named graph (ask triples). Used by {@code @Before} to isolate
     * test cases from each other.
     */
    static void purgeExtension(final String serverUrl, final String extensionUrl) {
        final String q = CAP_PREFIX
                + "DELETE { ?s ?p ?o } WHERE { ?s ?p ?o FILTER (?s = <" + extensionUrl + ">) } ;\n"
                + "DELETE WHERE { GRAPH <" + CapabilityVocabulary.CAP_ASKS_NAMED_GRAPH + "> { "
                + "<" + extensionUrl + "> ?p ?o . <" + extensionUrl + "#ask> ?p2 ?o2 . } } ;\n"
                + "DELETE WHERE { GRAPH <" + CapabilityVocabulary.CAP_ASKS_NAMED_GRAPH + "> { "
                + "<" + extensionUrl + "> ?p ?o . } } ;\n"
                + "DELETE WHERE { GRAPH <" + CapabilityVocabulary.CAP_ASKS_NAMED_GRAPH + "> { "
                + "<" + extensionUrl + "#ask> ?p ?o . } }";
        executeUpdate(serverUrl, POLICY_DB, q);
    }

    /**
     * Read back the ask triples that were auto-inserted for
     * {@code extensionUrl} on plugin load. Returns a list of
     * {@code (predicateIri, objectLabelOrIri)} pairs.
     *
     * <p>Object rendering: for {@link com.stardog.stark.Literal} objects
     * we return the {@link com.stardog.stark.Literal#label()} string
     * (bare value, no quotes/datatype); for IRI objects we return the
     * {@code toString()}, which is the raw IRI form. That matches the
     * assertion shape tests want without a per-call Value-vs-Literal
     * dispatch in the test body.
     */
    static List<String[]> readAskTriples(final String serverUrl,
                                         final String extensionUrl) {
        final String q = CAP_PREFIX
                + "SELECT ?p ?o WHERE { GRAPH <" + CapabilityVocabulary.CAP_ASKS_NAMED_GRAPH + "> { "
                + "<" + extensionUrl + "#ask> ?p ?o . } }";
        final List<String[]> rows = new ArrayList<>();
        try (Connection conn = ConnectionConfiguration.to(POLICY_DB)
                .server(serverUrl)
                .credentials("admin", "admin")
                .connect();
             SelectQueryResult r = conn.select(q).execute()) {
            while (r.hasNext()) {
                final BindingSet bs = r.next();
                final Optional<Value> p = bs.value("p");
                final Optional<Value> o = bs.value("o");
                rows.add(new String[]{
                        p.map(Value::toString).orElse(""),
                        o.map(CapabilityPolicyDbHelpers::renderObject).orElse("")
                });
            }
        }
        return rows;
    }

    private static String renderObject(final Value v) {
        if (v instanceof com.stardog.stark.Literal l) return l.label();
        return v.toString();
    }

    /**
     * Ensure the {@code system-webfunctions-capability} database exists
     * even when the plugin's own bootstrap raced with test startup.
     * Idempotent — no-op when the DB is already present.
     */
    static void ensurePolicyDb(final String serverUrl) {
        try (AdminConnection admin = AdminConnectionConfiguration.toServer(serverUrl)
                .credentials("admin", "admin")
                .connect()) {
            if (!admin.list().contains(POLICY_DB)) {
                admin.newDatabase(POLICY_DB).create();
            }
        }
    }

    private static void executeUpdate(final String serverUrl,
                                      final String database,
                                      final String update) {
        try (Connection conn = ConnectionConfiguration.to(database)
                .server(serverUrl)
                .credentials("admin", "admin")
                .connect()) {
            conn.begin();
            conn.update(update).execute();
            conn.commit();
        }
    }
}
