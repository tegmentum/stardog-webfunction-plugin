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

public final class WebFunctionServiceModule extends AbstractStardogModule {
    @Override
    protected void configure() {

        SecurityResourceTypes.register(WebFunctionResourceType.INSTANCE);

        Multibinder.newSetBinder(binder(), Service.class)
                .addBinding()
                .to(WebFunctionService.class)
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
}