package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.AbstractStardogModule;
import com.complexible.stardog.Kernel;
import com.complexible.stardog.KernelModule;
import com.complexible.stardog.StardogException;
import com.complexible.stardog.plan.eval.service.Service;
import com.complexible.stardog.security.SecurityResourceTypes;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public final class WebFunctionServiceModule extends AbstractStardogModule {
    @Override
    protected void configure() {

        SecurityResourceTypes.register(WebFunctionResourceType.INSTANCE);
        // Capability-policy Phase 1 — register the second resource type so
        // Stardog's AuthorizingSecurityManager understands
        // `web-function-callback:execute:<interface>/<method>` grants issued
        // through role-permission admin commands. Registration is idempotent;
        // starting an enforcer is opt-in via the capability master gate.
        SecurityResourceTypes.register(WebFunctionCallbackResourceType.INSTANCE);

        Multibinder<Service> services =
                Multibinder.newSetBinder(binder(), Service.class);
        services.addBinding()
                .to(WebFunctionService.class)
                .in(Singleton.class);
        // Compose Wave B — plan-composition SPARQL SERVICE trigger.
        // Registration is idempotent; the service IRI is reserved so
        // Stardog's planner discovers it, but MVP evaluate throws with
        // an actionable message pointing at the Java-callable
        // ComposeAdmin.compose entry point.
        services.addBinding()
                .to(ai.tegmentum.stardog.kibble.webfunctions.compose.WebFunctionComposeService.class)
                .in(Singleton.class);

        // Fuel metering Phase 2 — wire the Kernel-backed fuel state store
        // + UserFuelPolicy at plugin install time. Mirrors QueryLogModule's
        // KernelModule multibinding (see the ecosystem-precedent section
        // of fuel-implementation.md §7a.2). The starter defers real work
        // until install(Kernel), so it's a no-op for tests that spin up
        // the module without a Kernel present (they wire InMemory directly
        // via UserFuelPolicy.install).
        Multibinder<KernelModule> kernelModules =
                Multibinder.newSetBinder(binder(), KernelModule.class);
        kernelModules.addBinding().to(FuelPolicyStarter.class);
        // Capability-policy Phase 1b — install the enforcer + wire the
        // audit ring from `webfunctions.capability.*` system properties at
        // Kernel-install time. Mirrors FuelPolicyStarter's shape; the
        // starter no-ops when the master gate is off.
        kernelModules.addBinding().to(CapabilityPolicyStarter.class);
        // Phase 6 — durable disk backing for the two attribution rings.
        // No-op when `webfunctions.audit.disk.enabled=false` (the default);
        // in-memory ring behavior is untouched. When enabled, opens one
        // rotating-file NDJSON sink per ring under the configured
        // directory and installs a JVM shutdown hook so pending rows
        // drain to disk on plugin shutdown.
        kernelModules.addBinding().to(AuditSinkStarter.class);
        // Wave A — sink registry (in-memory) for the sink-callbacks /
        // sink-query-callbacks / document-sink-callbacks host callbacks.
        // Config-driven: reads webfunctions.sink.names and registers each
        // name into the singleton {@link SinkRegistry}. No-op when the
        // property is unset (registry stays empty; every sink call
        // returns `no-such-sink`).
        kernelModules.addBinding().to(SinkRegistryStarter.class);
        // Wave C — fulltext registry (in-memory) for the fulltext-callbacks
        // host callbacks (insert-documents / delete-documents /
        // search-index). Config-driven: reads webfunctions.fulltext.indexes
        // and registers each name into the singleton
        // {@link InMemoryFulltextRegistry}. No-op when the property is
        // unset (registry stays empty; every fulltext call returns
        // `no-such-index`).
        kernelModules.addBinding().to(FulltextRegistryStarter.class);
        // Wave B — SQLite JDBC-backed tracker-sink registry. Opens the
        // physical SQLite database at
        // webfunctions.tracker.sqlite.path and seeds the sink allowlist
        // from webfunctions.tracker.sqlite.sinks. No-op when the path
        // property is unset (backend stays closed; every tracker-sink-
        // callbacks dispatch returns `no-such-sink`).
        kernelModules.addBinding().to(SqliteTrackerBackendStarter.class);
    }

    /**
     * Kernel-install-time bootstrap for the Phase 2 fuel-metering layer.
     * Constructs the {@link KernelBackedFuelStateStore} against the
     * injected {@link Kernel}, initializes it (ensures the fuel database
     * exists, schedules the write-behind flush), and installs a
     * {@link UserFuelPolicy} keyed off {@link WebFunctionConfig#fuelEnabled()}
     * + {@link WebFunctionConfig#fuelPerUserMonthly()}. Deployments
     * running with fuel disabled or unlimited quotas get no persistent
     * store created — the initialize path is only hit on first
     * loadUser/saveUser call, so the whole Phase-2 apparatus stays inert
     * for existing users who don't opt in.
     */
    static final class FuelPolicyStarter implements KernelModule {

        @Inject
        FuelPolicyStarter() {}

        @Override
        public void install(final Kernel theKernel) throws StardogException {
            // Skip entirely when Phase-2 opt-in isn't configured — no
            // database created, no scheduler thread, no cache. Phase 1's
            // defensive layer continues to function without this.
            if (!WebFunctionConfig.fuelEnabled()) return;
            if (WebFunctionConfig.fuelPerUserMonthly() <= 0L) return;

            final KernelBackedFuelStateStore store =
                    KernelBackedFuelStateStore.wire(theKernel);
            UserFuelPolicy.install(new UserFuelPolicy(
                    store,
                    WebFunctionConfig.fuelPerUserMonthly(),
                    WebFunctionConfig.fuelPerInvocationMax()));
        }
    }

    /**
     * Kernel-install-time bootstrap for the Phase 1 capability-policy layer.
     * Wires the audit ring capacity + enable flag, installs the resolver's
     * anonymous-subject policy from config, and constructs / installs a
     * {@link CapabilityEnforcer} instance guarded by the master gate.
     *
     * <p>No-op when {@link WebFunctionConfig#isCapabilityEnabled()} is false
     * — the enforcer stays uninstalled and {@link CapabilityEnforcer#activePolicy()}
     * returns empty, so the hot path bypasses all Phase 1 work.
     *
     * <p>The starter reads all keys through {@link WebFunctionConfig}, so
     * unit tests that mutate system properties before boot get the intended
     * behavior without a re-install cycle. Live-tuning of audit capacity /
     * enable state after boot goes through
     * {@link CapabilityAttributionRing#INSTANCE} directly.
     */
    static final class CapabilityPolicyStarter implements KernelModule {

        @Inject
        CapabilityPolicyStarter() {}

        @Override
        public void install(final Kernel theKernel) throws StardogException {
            // Idempotent config wiring — set every time so a later
            // enable / anonymous-policy / unknown-extension-policy flip
            // in system properties takes effect on the next boot cycle
            // without extra plumbing.
            CapabilityPolicyResolver.setAnonymousPolicy(WebFunctionConfig.getAnonymousPolicy());
            CapabilityPolicyResolver.setUnknownExtensionPolicy(
                    WebFunctionConfig.unknownExtensionPolicy());
            CapabilityAttributionRing.INSTANCE.setEnabled(WebFunctionConfig.isAuditEnabled());
            CapabilityAttributionRing.INSTANCE.setCapacity(WebFunctionConfig.getAuditCapacity());

            if (!WebFunctionConfig.isCapabilityEnabled()) return;

            // Bootstrap the Kernel-backed policy store (creates the
            // system-webfunctions-capability database as super-user if
            // absent) and install it into the resolver so every
            // instantiation-time resolve() consults it.
            final KernelBackedCapabilityPolicyStore store =
                    KernelBackedCapabilityPolicyStore.wire(theKernel);
            CapabilityPolicyResolver.setPolicyStore(store);

            CapabilityEnforcer.install(CapabilityEnforcer.create());
            CapabilityEnforcer.setEnabled(true);
        }
    }

    /**
     * Kernel-install-time bootstrap for the Phase 6 audit disk backing.
     * Constructs one {@link NdjsonRotatingFileAuditSink} per ring type
     * (fuel, capability) rooted under
     * {@link WebFunctionConfig#auditDiskDirectory()}, wires each into
     * the singleton ring, and registers a JVM shutdown hook that closes
     * both sinks so any queued rows drain to disk on shutdown.
     *
     * <p>No-op when {@link WebFunctionConfig#auditDiskEnabled()} is false
     * (the default). Existing in-memory ring behavior is fully preserved
     * — the rings continue to snapshot() from memory; the sink is an
     * additive write target, not a replacement.
     *
     * <p>On sink construction failure (bad directory, permission denied)
     * the starter logs a warning and falls back to the noop sink for
     * that ring — the plugin still boots and in-memory ring behavior
     * still functions.
     */
    static final class AuditSinkStarter implements KernelModule {

        private static final Logger LOG = LoggerFactory.getLogger(AuditSinkStarter.class);

        @Inject
        AuditSinkStarter() {}

        @Override
        public void install(final Kernel theKernel) throws StardogException {
            if (!WebFunctionConfig.auditDiskEnabled()) return;

            final Path dir = Path.of(WebFunctionConfig.auditDiskDirectory());
            final long rotateBytes    = WebFunctionConfig.auditDiskRotateBytes();
            final int maxFiles        = WebFunctionConfig.auditDiskMaxFiles();
            final int queueCapacity   = WebFunctionConfig.auditDiskQueueCapacity();
            final NdjsonRotatingFileAuditSink.FsyncPolicy fsync
                    = WebFunctionConfig.auditDiskFsyncPolicy();
            final boolean gzip        = WebFunctionConfig.auditDiskGzipRotated();

            final AuditSink fuelSink = openSinkOrNoop(
                    new NdjsonRotatingFileAuditSink.Config(
                            dir, "audit-fuel", rotateBytes, maxFiles,
                            queueCapacity, fsync, gzip),
                    "audit-fuel");
            final AuditSink capSink = openSinkOrNoop(
                    new NdjsonRotatingFileAuditSink.Config(
                            dir, "audit-capability", rotateBytes, maxFiles,
                            queueCapacity, fsync, gzip),
                    "audit-capability");

            AttributionRing.INSTANCE.setSink(fuelSink);
            CapabilityAttributionRing.INSTANCE.setSink(capSink);

            // JVM shutdown hook drains both sinks. The KernelModule
            // interface doesn't have a shutdown callback in Stardog 12,
            // so the JVM hook is the only sink-agnostic drain point.
            // Both sinks' close() are idempotent so a manual close from
            // a test tear-down doesn't collide with the hook.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { fuelSink.close(); } catch (RuntimeException e) {
                    LOG.warn("Audit sink close (fuel) failed: {}", e.toString());
                }
                try { capSink.close(); } catch (RuntimeException e) {
                    LOG.warn("Audit sink close (capability) failed: {}", e.toString());
                }
            }, "webfunctions-audit-shutdown"));
        }

        private static AuditSink openSinkOrNoop(
                final NdjsonRotatingFileAuditSink.Config config,
                final String label) {
            try {
                return new NdjsonRotatingFileAuditSink(config);
            } catch (IOException ioe) {
                LOG.warn("Audit sink open failed for {} in {}: {} — "
                                + "falling back to noop sink (in-memory ring still active)",
                        label, config.directory, ioe.toString());
                return NoopAuditSink.INSTANCE;
            }
        }
    }

    /**
     * Kernel-install-time bootstrap for the Wave A sink registry. Reads
     * the comma-separated {@link WebFunctionConfig#PROP_SINK_NAMES}
     * property and registers each name into the singleton
     * {@link SinkRegistry}, making it discoverable to
     * {@code sink-callbacks::list-sinks} and routable by
     * {@code emit-quad(s)}, {@code scan-sink-quads}, and the
     * {@code document-sink-callbacks} triad.
     *
     * <p>No-op when the property is unset — the registry stays empty and
     * every sink-family callback surfaces the interface's
     * {@code no-such-sink} arm for any name a guest requests.
     *
     * <p>Startup discipline: idempotent registration is NOT supported —
     * a duplicate name in the property surfaces as
     * {@link IllegalStateException} (thrown by
     * {@link SinkRegistry#register(String)}) and aborts install. This
     * catches config typos loudly rather than silently dropping
     * accumulated state. When the plugin is re-installed inside a single
     * JVM (tests spinning the module up twice), the starter first
     * {@link SinkRegistry#reset()}s so re-registration succeeds.
     */
    static final class SinkRegistryStarter implements KernelModule {

        private static final Logger LOG = LoggerFactory.getLogger(SinkRegistryStarter.class);

        @Inject
        SinkRegistryStarter() {}

        @Override
        public void install(final Kernel theKernel) throws StardogException {
            final java.util.List<String> names = WebFunctionConfig.getSinkNames();
            if (names.isEmpty()) return;

            // Reset first so a re-install inside a single JVM (unit-test
            // harnesses that spin up the module twice, or ops-side "re-
            // read config" flows) doesn't collide with prior registrations.
            SinkRegistry.INSTANCE.reset();
            for (final String name : names) {
                SinkRegistry.INSTANCE.register(name);
            }
            LOG.info("web-function sink registry: registered {} sink(s): {}",
                    names.size(), names);
        }
    }

    /**
     * Kernel-install-time bootstrap for the Wave C fulltext index
     * registry. Reads the comma-separated
     * {@link WebFunctionConfig#PROP_FULLTEXT_INDEXES} property and
     * registers each name into the singleton
     * {@link InMemoryFulltextRegistry}, making it routable by
     * {@code fulltext-callbacks::insert-documents},
     * {@code delete-documents}, and {@code search-index}.
     *
     * <p>No-op when the property is unset — the registry stays empty and
     * every fulltext callback surfaces the interface's
     * {@code no-such-index} arm for any name a guest requests. Same
     * startup + duplicate-name discipline as {@link SinkRegistryStarter}
     * — duplicate entries in the property abort install via
     * {@link InMemoryFulltextRegistry#register(String)}'s
     * {@link IllegalStateException}.
     */
    static final class FulltextRegistryStarter implements KernelModule {

        private static final Logger LOG = LoggerFactory.getLogger(FulltextRegistryStarter.class);

        @Inject
        FulltextRegistryStarter() {}

        @Override
        public void install(final Kernel theKernel) throws StardogException {
            final java.util.List<String> names =
                    WebFunctionConfig.getFulltextIndexNames();
            if (names.isEmpty()) return;

            // Reset first so a re-install inside a single JVM (test
            // harnesses that spin up the module twice, or ops-side
            // "re-read config" flows) doesn't collide with prior
            // registrations.
            InMemoryFulltextRegistry.INSTANCE.reset();
            for (final String name : names) {
                InMemoryFulltextRegistry.INSTANCE.register(name);
            }
            LOG.info("web-function fulltext registry: registered {} index(es): {}",
                    names.size(), names);
        }
    }

    /**
     * Kernel-install-time bootstrap for the Wave B SQLite tracker-sink
     * backend. Reads the SQLite database path from
     * {@link WebFunctionConfig#getTrackerSqlitePath()} and the sink
     * allowlist from {@link WebFunctionConfig#getTrackerSqliteSinks()},
     * then calls {@link SqliteTrackerBackend#open(String, java.util.List)}
     * on the singleton so the tracker-sink-callbacks handlers can route
     * through it.
     *
     * <p>No-op when the path property is unset — the backend stays
     * closed and every tracker-sink-callbacks dispatch surfaces
     * {@code no-such-sink} because the allowlist is empty. Mirrors the
     * {@link SinkRegistryStarter} / {@link FulltextRegistryStarter}
     * shape.
     *
     * <p>Startup discipline: re-install inside a single JVM (unit test
     * harnesses that spin up the module twice, or ops-side "re-read
     * config" flows) is handled by the singleton's internal
     * close-then-reopen path in {@link SqliteTrackerBackend#open}.
     */
    static final class SqliteTrackerBackendStarter implements KernelModule {

        private static final Logger LOG = LoggerFactory.getLogger(SqliteTrackerBackendStarter.class);

        @Inject
        SqliteTrackerBackendStarter() {}

        @Override
        public void install(final Kernel theKernel) throws StardogException {
            final java.util.Optional<String> pathOpt = WebFunctionConfig.getTrackerSqlitePath();
            if (pathOpt.isEmpty()) return;

            final String path = pathOpt.get();
            final java.util.List<String> sinks = WebFunctionConfig.getTrackerSqliteSinks();
            SqliteTrackerBackend.INSTANCE.open(path, sinks);
            LOG.info("web-function tracker-sink backend: opened '{}' with {} sink(s): {}",
                    path, sinks.size(), sinks);
        }
    }
}