package ai.tegmentum.stardog.kibble;

import com.complexible.stardog.api.Connection;
import com.complexible.stardog.api.ConnectionConfiguration;
import com.complexible.stardog.api.admin.AdminConnection;
import com.complexible.stardog.api.admin.AdminConnectionConfiguration;
import com.complexible.stardog.security.ActionType;
import com.complexible.stardog.security.CoreResourceType;
import com.complexible.stardog.security.Permission;
import com.complexible.stardog.security.PermissionManager;
import com.complexible.stardog.security.ResourceExistsException;
import com.complexible.stardog.security.RoleManager;
import com.complexible.stardog.security.SecurityResourceType;
import com.complexible.stardog.security.UserManager;
import com.google.common.collect.ImmutableList;

/**
 * Testcontainers-side helper for provisioning Stardog users, roles, and
 * per-graph ACLs against a running server, so multi-user integration
 * tests can construct a realistic authz shape (Alice/Bob with
 * asymmetric named-graph access) without dropping down to
 * {@code stardog-admin user add} shell-outs.
 *
 * <p>Sibling of {@link ai.tegmentum.stardog.kibble.webfunctions.CapabilityPolicyDbHelpers}
 * — that helper drives capability-policy triples via SPARQL against the
 * plugin's policy store; this one drives Stardog's built-in security
 * model via {@link UserManager} / {@link RoleManager} /
 * {@link PermissionManager} obtained from an admin {@link AdminConnection}.
 *
 * <p>All methods are idempotent: {@link #addUser} swallows
 * {@link ResourceExistsException} so a container-restart-and-reprovision
 * sequence does not require prior teardown, and permission-grant methods
 * likewise treat "already granted" as success. The permission-grant path
 * uses {@link PermissionManager#addUserPerm} directly rather than
 * checking {@link PermissionManager#getUserPerms} first — the manager
 * throws {@link ResourceExistsException} on the duplicate, which we
 * swallow the same way {@link #addUser} does.
 *
 * <p>Superuser {@code admin:admin} is used to bootstrap; individual test
 * classes still authenticate as the provisioned user for the actual
 * invocation under test.
 *
 * <p>Not part of the shipped plugin; test-only utility, kept alongside
 * {@link StardogContainer} so per-container provisioning code lives one
 * package up from the webfunctions-specific tests that consume it.
 */
public final class StardogUserProvisioner {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin";

    private final String serverUrl;

    public StardogUserProvisioner(final String serverUrl) {
        if (serverUrl == null || serverUrl.isEmpty()) {
            throw new IllegalArgumentException("serverUrl is required");
        }
        this.serverUrl = serverUrl;
    }

    // ---- users ----------------------------------------------------------

    /**
     * Create a non-superuser account. Idempotent — silently succeeds when
     * the user already exists (the container may be reused across test
     * classes in a single failsafe run, and {@code @BeforeClass} may
     * re-run against a warm image).
     */
    public void addUser(final String username, final String password) {
        try (AdminConnection admin = openAdmin()) {
            final UserManager users = admin.getUserManager();
            try {
                users.addUser(username, false, password.toCharArray());
            } catch (ResourceExistsException already) {
                // Already provisioned — leave as-is.
            }
        }
    }

    /**
     * Create a role. Idempotent — silently succeeds on
     * {@link ResourceExistsException}. Reusable across tests that
     * partition permissions by role (e.g. {@code reader}, {@code writer}
     * families) rather than granting user permissions directly.
     */
    public void addRole(final String role) {
        try (AdminConnection admin = openAdmin()) {
            final RoleManager roles = admin.getRoleManager();
            try {
                roles.addRole(role);
            } catch (ResourceExistsException already) {
                // Already provisioned — leave as-is.
            }
        }
    }

    /**
     * Assign a role to a user. Idempotent — swallows the underlying
     * duplicate-assignment error. Wraps
     * {@link UserManager#addUserRole(String, String)}.
     */
    public void assignRole(final String username, final String role) {
        try (AdminConnection admin = openAdmin()) {
            final UserManager users = admin.getUserManager();
            try {
                users.addUserRole(username, role);
            } catch (RuntimeException already) {
                // Stardog uses ResourceExistsException on some paths and
                // a generic StardogException on others when the role is
                // already assigned. Swallow anything the assignment
                // triggers rather than plumbing every subtype through.
            }
        }
    }

    // ---- permissions ----------------------------------------------------

    /**
     * Grant a user READ on the named database. Required baseline before
     * any per-graph permissions take effect — a user without db-level
     * READ cannot open the database to run queries, so named-graph
     * permissions on that db are moot.
     */
    public void grantDatabaseRead(final String username, final String database) {
        addUserPermQuietly(username, ActionType.READ,
                CoreResourceType.DATABASE,
                ImmutableList.of(database));
    }

    /**
     * Grant a user WRITE on the named database — used sparingly, only
     * where the test needs a specific user to seed data (Alice inserting
     * fixture triples, for instance). Most invocation-side tests only
     * need {@link #grantDatabaseRead}.
     */
    public void grantDatabaseWrite(final String username, final String database) {
        addUserPermQuietly(username, ActionType.WRITE,
                CoreResourceType.DATABASE,
                ImmutableList.of(database));
    }

    /**
     * Grant a user READ on a specific named graph inside a database.
     * Only takes effect when the database has
     * {@code security.named.graphs=true} set — otherwise the built-in
     * security model does not consult named-graph permissions and the
     * grant is inert.
     *
     * <p>Stardog's {@link CoreResourceType#NAMED_GRAPH} resource takes a
     * two-element resource name: {@code [dbName, graphIri]}. Passing a
     * malformed graph IRI (empty string, wildcards) is caller error;
     * this method does not validate.
     */
    public void grantNamedGraphRead(final String username,
                                    final String database,
                                    final String graphIri) {
        addUserPermQuietly(username, ActionType.READ,
                CoreResourceType.NAMED_GRAPH,
                ImmutableList.of(database, graphIri));
    }

    /**
     * Grant a user WRITE on a specific named graph — parallel to
     * {@link #grantNamedGraphRead}. Only takes effect under
     * {@code security.named.graphs=true}.
     */
    public void grantNamedGraphWrite(final String username,
                                     final String database,
                                     final String graphIri) {
        addUserPermQuietly(username, ActionType.WRITE,
                CoreResourceType.NAMED_GRAPH,
                ImmutableList.of(database, graphIri));
    }

    /**
     * Grant a user a permission against a plugin-registered
     * {@link SecurityResourceType} — the escape hatch for the plugin's
     * own resource types ({@code WebFunctionResourceType},
     * {@code WebFunctionCallbackResourceType}) that are not covered by
     * the {@link #grantDatabaseRead}-style database-scoped helpers.
     *
     * <p>Uses the same idempotent {@link PermissionManager#addUserPerm}
     * path as the built-in helpers — the manager throws
     * {@link ResourceExistsException} on a re-grant, which we swallow.
     *
     * <p>Both {@link ActionType} and {@link SecurityResourceType} are
     * proper enum / interface values, so the compiler catches typos
     * that the string-based HTTP admin fallback would only surface at
     * runtime.
     */
    public void grantUserPermission(final String username,
                                    final ActionType action,
                                    final SecurityResourceType resourceType,
                                    final String... resourceNames) {
        addUserPermQuietly(username, action, resourceType,
                ImmutableList.copyOf(resourceNames));
    }

    // ---- data seeding ---------------------------------------------------

    /**
     * INSERT DATA into a named graph as the admin superuser. The caller
     * hands raw Turtle-style triple text ({@code "<s> <p> <o> . <s2> ..."})
     * — this helper wraps it in a GRAPH block and issues the update
     * against the given database.
     *
     * <p>Uses the admin credential so this bootstrap path does not
     * depend on the user under test having write access — the point of
     * the invoker-subject IT is precisely that the invoker (Alice/Bob)
     * has ASYMMETRIC read; the seed data must be present regardless.
     */
    public void insertIntoNamedGraph(final String database,
                                     final String graphIri,
                                     final String triples) {
        final String update = "INSERT DATA { GRAPH <" + graphIri + "> { " + triples + " } }";
        try (Connection conn = ConnectionConfiguration.to(database)
                .server(serverUrl)
                .credentials(ADMIN_USER, ADMIN_PASS)
                .connect()) {
            conn.begin();
            conn.update(update).execute();
            conn.commit();
        }
    }

    // ---- internals ------------------------------------------------------

    private AdminConnection openAdmin() {
        return AdminConnectionConfiguration.toServer(serverUrl)
                .credentials(ADMIN_USER, ADMIN_PASS)
                .connect();
    }

    /**
     * Wrap {@link PermissionManager#addUserPerm(Permission)} with
     * idempotent swallow-on-duplicate semantics — the underlying manager
     * throws {@link ResourceExistsException} on a re-grant, which for
     * the test-provisioning shape is a no-op.
     */
    private void addUserPermQuietly(final String username,
                                    final ActionType action,
                                    final SecurityResourceType resourceType,
                                    final ImmutableList<String> resourceNames) {
        try (AdminConnection admin = openAdmin()) {
            final PermissionManager perms = admin.getPermissionManager();
            try {
                perms.addUserPerm(new Permission(username, action, resourceType, resourceNames));
            } catch (ResourceExistsException already) {
                // Already granted — leave as-is.
            }
        }
    }
}
