package ai.tegmentum.stardog.kibble.webfunctions;

import org.apache.shiro.authz.Permission;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Capability wave Phase 1e — orchestration + audit ring.
 *
 * <p>Covers enforcement happy path (method allowed + host allowed +
 * Shiro allowed → grant recorded), method-denied path, host-allowlist-
 * denied path, permission-denied path (no Shiro subject / Shiro denies),
 * ring rollover, and the activePolicy short-circuit.
 */
public class TestCapabilityEnforcer {

    private CapabilityEnforcer enforcer;

    @Before
    public void setUp() {
        // Fresh audit ring per test.
        CapabilityAttributionRing.INSTANCE.clear();
        CapabilityAttributionRing.INSTANCE.setEnabled(true);
        CapabilityAttributionRing.INSTANCE.setCapacity(CapabilityAttributionRing.DEFAULT_CAPACITY);
        // Fresh enforcer per test.
        enforcer = CapabilityEnforcer.create();
        CapabilityEnforcer.install(enforcer);
        CapabilityEnforcer.setEnabled(true);
    }

    @After
    public void tearDown() {
        CapabilityEnforcer.install(null);
        CapabilityEnforcer.setEnabled(false);
        CapabilityAttributionRing.INSTANCE.clear();
        // Shiro thread-context leaks between tests otherwise.
        ThreadContext.unbindSubject();
    }

    @Test
    public void activePolicyEmptyWhenGateDisabled() {
        CapabilityEnforcer.setEnabled(false);
        assertThat(CapabilityEnforcer.activePolicy()).isEmpty();
        CapabilityEnforcer.setEnabled(true);
        assertThat(CapabilityEnforcer.activePolicy()).contains(enforcer);
    }

    @Test
    public void activePolicyEmptyWhenNothingInstalled() {
        CapabilityEnforcer.install(null);
        assertThat(CapabilityEnforcer.activePolicy()).isEmpty();
    }

    @Test
    public void happyPathWritesGrantedRow() {
        // Grant permits graph-callbacks/execute-query — no HTTP host
        // check (not http-callbacks); Shiro allow via a bound subject
        // that returns success from checkPermission.
        final CapabilityGrant grant = new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("graph-callbacks"),
                Map.of("graph-callbacks",
                        MethodPolicy.allowOnly("graph-callbacks", Set.of("execute-query"))),
                HostAllowlist.ALLOW_NONE,
                "alice",
                CapabilityModel.INVOKER_SUBJECT);
        ThreadContext.bind(alwaysAllowSubject("alice"));

        enforcer.perCallback(null, grant, "graph-callbacks", "execute-query", "");

        final List<CapabilityAuditRow> rows = CapabilityAttributionRing.INSTANCE.snapshot();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).outcome()).isEqualTo(CapabilityAuditRow.Outcome.GRANTED);
        assertThat(rows.get(0).interfaceName()).isEqualTo("graph-callbacks");
        assertThat(rows.get(0).method()).isEqualTo("execute-query");
        assertThat(rows.get(0).userId()).isEqualTo("alice");
    }

    @Test
    public void interfaceDeniedThrowsAndWritesDeniedRow() {
        final CapabilityGrant grant = new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("http-callbacks"),           // graph-callbacks NOT granted
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                "alice",
                CapabilityModel.INVOKER_SUBJECT);

        final Throwable thrown = catchThrowable(() -> enforcer.perCallback(
                null, grant, "graph-callbacks", "execute-query", ""));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        final WfCapabilityError.PerCallDenied err = (WfCapabilityError.PerCallDenied) thrown;
        assertThat(err.reason()).isEqualTo(
                WfCapabilityError.PerCallDenied.REASON_INTERFACE_DENIED);

        final List<CapabilityAuditRow> rows = CapabilityAttributionRing.INSTANCE.snapshot();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).outcome()).isEqualTo(CapabilityAuditRow.Outcome.DENIED);
        assertThat(rows.get(0).denyReason()).isEqualTo(
                WfCapabilityError.PerCallDenied.REASON_INTERFACE_DENIED);
    }

    @Test
    public void methodDeniedThrowsAndWritesDeniedRow() {
        final CapabilityGrant grant = new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("graph-callbacks"),
                Map.of("graph-callbacks",
                        MethodPolicy.allowOnly("graph-callbacks", Set.of("execute-query"))),
                HostAllowlist.ALLOW_NONE,
                "alice",
                CapabilityModel.INVOKER_SUBJECT);

        final Throwable thrown = catchThrowable(() -> enforcer.perCallback(
                null, grant, "graph-callbacks", "execute-update", ""));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        assertThat(((WfCapabilityError.PerCallDenied) thrown).reason())
                .isEqualTo(WfCapabilityError.PerCallDenied.REASON_METHOD_DENIED);

        final List<CapabilityAuditRow> rows = CapabilityAttributionRing.INSTANCE.snapshot();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).denyReason()).isEqualTo(
                WfCapabilityError.PerCallDenied.REASON_METHOD_DENIED);
    }

    @Test
    public void hostAllowlistDeniedThrowsAndWritesDeniedRow() {
        // http-callbacks with a specific allowlist; guest tries a
        // hostname not on the list.
        final CapabilityGrant grant = new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("http-callbacks"),
                Map.of(),
                new HostAllowlist(List.of("api.acme.com")),
                "alice",
                CapabilityModel.INVOKER_SUBJECT);

        final Throwable thrown = catchThrowable(() -> enforcer.perCallback(
                null, grant, "http-callbacks", "http-get", "api.evil.com"));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        assertThat(((WfCapabilityError.PerCallDenied) thrown).reason())
                .isEqualTo(WfCapabilityError.PerCallDenied.REASON_HOST_DENIED);
        assertThat(((WfCapabilityError.PerCallDenied) thrown).argumentsSummary())
                .isEqualTo("api.evil.com");

        final List<CapabilityAuditRow> rows = CapabilityAttributionRing.INSTANCE.snapshot();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).argumentsSummary()).isEqualTo("api.evil.com");
    }

    @Test
    public void permissionDeniedWhenNoShiroSubjectBound() {
        // Grant allows the tuple, but no Shiro subject → ShiroUtils.check
        // fails and the enforcer maps it to permission-denied.
        final CapabilityGrant grant = new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("graph-callbacks"),
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                "",
                CapabilityModel.INVOKER_SUBJECT);
        ThreadContext.unbindSubject();

        final Throwable thrown = catchThrowable(() -> enforcer.perCallback(
                null, grant, "graph-callbacks", "execute-query", ""));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        assertThat(((WfCapabilityError.PerCallDenied) thrown).reason())
                .isEqualTo(WfCapabilityError.PerCallDenied.REASON_PERMISSION_DENIED);
    }

    @Test
    public void emptyHostAllowlistNoRestrictionUnderPhase5Semantics() {
        // Phase 5 semantic change: empty HostAllowlist means "no
        // restriction beyond coarser interface + method checks".
        // Before Phase 5, ALLOW_NONE on http-callbacks denied every URL.
        // After Phase 5, it allows every URL (only NON-EMPTY allowlists
        // impose restrictions).
        final CapabilityGrant grant = new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("http-callbacks"),
                Map.of(),
                HostAllowlist.ALLOW_NONE,          // empty ⇒ no restriction
                "alice",
                CapabilityModel.INVOKER_SUBJECT);
        ThreadContext.bind(alwaysAllowSubject("alice"));

        enforcer.perCallback(null, grant, "http-callbacks", "http-get", "any.host.com");

        final List<CapabilityAuditRow> rows = CapabilityAttributionRing.INSTANCE.snapshot();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).outcome()).isEqualTo(CapabilityAuditRow.Outcome.GRANTED);
    }

    @Test
    public void enforceHttpPathNoOpOnEmptyAllowlist() {
        // Empty HttpPathAllowlist ⇒ no restriction, no audit row, no throw.
        final CapabilityGrant grant = new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("http-callbacks"),
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                HttpPathAllowlist.ALLOW_NONE,
                WasmCalleeAllowlist.ALLOW_NONE,
                "alice",
                CapabilityModel.INVOKER_SUBJECT);
        enforcer.enforceHttpPath(grant, "file:///ext.wasm", "http-get",
                "https://api.evil.com/anything");
        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
    }

    @Test
    public void enforceHttpPathAllowsMatchingPrefix() {
        final CapabilityGrant grant = new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("http-callbacks"),
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                new HttpPathAllowlist(List.of("api.acme.com/public/")),
                WasmCalleeAllowlist.ALLOW_NONE,
                "alice",
                CapabilityModel.INVOKER_SUBJECT);
        enforcer.enforceHttpPath(grant, "file:///ext.wasm", "http-get",
                "https://api.acme.com/public/orders");
        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
    }

    @Test
    public void enforceHttpPathDeniesOnMismatch() {
        final CapabilityGrant grant = new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("http-callbacks"),
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                new HttpPathAllowlist(List.of("api.acme.com/public/")),
                WasmCalleeAllowlist.ALLOW_NONE,
                "alice",
                CapabilityModel.INVOKER_SUBJECT);
        final Throwable thrown = catchThrowable(() -> enforcer.enforceHttpPath(
                grant, "file:///ext.wasm", "http-get",
                "https://api.acme.com/private/secrets"));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        final WfCapabilityError.PerCallDenied err = (WfCapabilityError.PerCallDenied) thrown;
        assertThat(err.reason()).isEqualTo(
                WfCapabilityError.PerCallDenied.REASON_HTTP_PATH_DENIED);
        assertThat(err.interfaceName()).isEqualTo("http-callbacks");
        assertThat(err.argumentsSummary()).isEqualTo("api.acme.com/private/secrets");

        final List<CapabilityAuditRow> rows = CapabilityAttributionRing.INSTANCE.snapshot();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).outcome()).isEqualTo(CapabilityAuditRow.Outcome.DENIED);
        assertThat(rows.get(0).denyReason()).isEqualTo(
                WfCapabilityError.PerCallDenied.REASON_HTTP_PATH_DENIED);
    }

    @Test
    public void enforceWasmCalleeNoOpOnEmptyAllowlist() {
        final CapabilityGrant grant = new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("wasm-callbacks"),
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                HttpPathAllowlist.ALLOW_NONE,
                WasmCalleeAllowlist.ALLOW_NONE,
                "alice",
                CapabilityModel.INVOKER_SUBJECT);
        enforcer.enforceWasmCallee(grant, "file:///ext.wasm", "invoke-wasm",
                "ipfs://QmAnyCallee");
        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
    }

    @Test
    public void enforceWasmCalleeAllowsExactMatch() {
        final CapabilityGrant grant = new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("wasm-callbacks"),
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                HttpPathAllowlist.ALLOW_NONE,
                WasmCalleeAllowlist.of(List.of("ipfs://QmCallee")),
                "alice",
                CapabilityModel.INVOKER_SUBJECT);
        enforcer.enforceWasmCallee(grant, "file:///ext.wasm", "invoke-wasm",
                "ipfs://QmCallee");
        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
    }

    @Test
    public void enforceWasmCalleeDeniesOnMismatch() {
        final CapabilityGrant grant = new CapabilityGrant(
                "file:///ext.wasm",
                Set.of("wasm-callbacks"),
                Map.of(),
                HostAllowlist.ALLOW_NONE,
                HttpPathAllowlist.ALLOW_NONE,
                WasmCalleeAllowlist.of(List.of("ipfs://QmCallee")),
                "alice",
                CapabilityModel.INVOKER_SUBJECT);
        final Throwable thrown = catchThrowable(() -> enforcer.enforceWasmCallee(
                grant, "file:///ext.wasm", "invoke-wasm-service",
                "ipfs://QmEvil"));
        assertThat(thrown).isInstanceOf(WfCapabilityError.PerCallDenied.class);
        final WfCapabilityError.PerCallDenied err = (WfCapabilityError.PerCallDenied) thrown;
        assertThat(err.reason()).isEqualTo(
                WfCapabilityError.PerCallDenied.REASON_WASM_CALLEE_DENIED);
        assertThat(err.interfaceName()).isEqualTo("wasm-callbacks");
        assertThat(err.method()).isEqualTo("invoke-wasm-service");
        assertThat(err.argumentsSummary()).isEqualTo("ipfs://QmEvil");
    }

    @Test
    public void enforcePhase5MethodsNoOpOnNullGrant() {
        // Defensive path — null grant returns without throwing (matches
        // enforceCapability's null-ctx guard shape in HostCallbacks).
        enforcer.enforceHttpPath(null, "file:///ext.wasm", "http-get",
                "https://api.evil.com/anything");
        enforcer.enforceWasmCallee(null, "file:///ext.wasm", "invoke-wasm",
                "ipfs://QmAny");
        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
    }

    @Test
    public void hostAndPathFromUrlStripsSchemeAndUserinfo() {
        assertThat(CapabilityEnforcer.hostAndPathFromUrl(
                "https://api.acme.com/public/orders"))
                .isEqualTo("api.acme.com/public/orders");
        assertThat(CapabilityEnforcer.hostAndPathFromUrl(
                "http://user:pw@api.acme.com/path"))
                .isEqualTo("api.acme.com/path");
        assertThat(CapabilityEnforcer.hostAndPathFromUrl(
                "https://api.acme.com")).isEqualTo("api.acme.com");
        assertThat(CapabilityEnforcer.hostAndPathFromUrl("")).isEmpty();
        assertThat(CapabilityEnforcer.hostAndPathFromUrl(null)).isEmpty();
        // Unparseable input returns the raw string — the allowlist will
        // then reject it (no pattern will start with "not a url").
        assertThat(CapabilityEnforcer.hostAndPathFromUrl("not a url"))
                .isEqualTo("not a url");
    }

    @Test
    public void ringBoundsRolloverPreservesRecentRows() {
        // Tight capacity; drive more denials through than fit.
        CapabilityAttributionRing.INSTANCE.setCapacity(3);
        for (int i = 0; i < 5; i++) {
            CapabilityAttributionRing.recordDenied(
                    "alice", "file:///ext-" + i + ".wasm",
                    "http-callbacks", "http-get",
                    "host-" + i,
                    WfCapabilityError.PerCallDenied.REASON_HOST_DENIED);
        }
        final List<CapabilityAuditRow> rows = CapabilityAttributionRing.INSTANCE.snapshot();
        assertThat(rows).hasSize(3);
        // Oldest-first — the surviving indices are the last three.
        assertThat(rows.get(0).extensionUri()).isEqualTo("file:///ext-2.wasm");
        assertThat(rows.get(2).extensionUri()).isEqualTo("file:///ext-4.wasm");
    }

    @Test
    public void ringDisabledIsNoOp() {
        CapabilityAttributionRing.INSTANCE.setEnabled(false);
        CapabilityAttributionRing.recordGranted(
                "alice", "file:///ext.wasm", "graph-callbacks", "execute-query", "");
        assertThat(CapabilityAttributionRing.INSTANCE.snapshot()).isEmpty();
        assertThat(CapabilityAttributionRing.INSTANCE.size()).isZero();
    }

    @Test
    public void ringConvenienceHelpersProduceStableRowShape() {
        CapabilityAttributionRing.recordGranted(
                "alice", "file:///ext.wasm", "http-callbacks", "http-get", "api.acme.com");
        CapabilityAttributionRing.recordDenied(
                "alice", "file:///ext.wasm", "http-callbacks", "http-get", "api.evil.com",
                WfCapabilityError.PerCallDenied.REASON_HOST_DENIED);
        final List<CapabilityAuditRow> rows = CapabilityAttributionRing.INSTANCE.snapshot();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).outcome()).isEqualTo(CapabilityAuditRow.Outcome.GRANTED);
        assertThat(rows.get(0).denyReason()).isEmpty();
        assertThat(rows.get(1).outcome()).isEqualTo(CapabilityAuditRow.Outcome.DENIED);
        assertThat(rows.get(1).denyReason()).isEqualTo(
                WfCapabilityError.PerCallDenied.REASON_HOST_DENIED);
        // Timestamps monotonically non-decreasing.
        assertThat(rows.get(1).timestamp())
                .isAfterOrEqualTo(rows.get(0).timestamp());
    }

    @Test
    public void auditRowRejectsNullFields() {
        assertThat(catchThrowable(() -> new CapabilityAuditRow(
                null, "", "", "", "", "", "", CapabilityAuditRow.Outcome.GRANTED, "")))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new CapabilityAuditRow(
                Instant.now(), "", "", "", "", "", "", null, "")))
                .isInstanceOf(NullPointerException.class);
        assertThat(catchThrowable(() -> new CapabilityAuditRow(
                Instant.now(), "", "", "", "", "", "", CapabilityAuditRow.Outcome.GRANTED, null)))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Java-Proxy Subject stub whose {@code checkPermission} does nothing
     * (so ShiroUtils.check returns true) and {@code isAuthenticated}
     * returns true — Stardog's ShiroUtils.check short-circuits false
     * when isAuthenticated is false, so we need the authenticated flag
     * to be true.
     */
    private static Subject alwaysAllowSubject(final String principal) {
        return (Subject) Proxy.newProxyInstance(
                Subject.class.getClassLoader(),
                new Class[]{Subject.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getPrincipal":     return principal;
                        case "isAuthenticated":  return true;
                        case "isRemembered":     return false;
                        case "checkPermission":  return null;  // void — no throw = allow
                        case "isPermitted":
                            if (args != null && args.length == 1 && args[0] instanceof Permission) {
                                return Boolean.TRUE;
                            }
                            return Boolean.TRUE;
                        default:
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            if (method.getReturnType() == long.class) return 0L;
                            return null;
                    }
                });
    }
}
