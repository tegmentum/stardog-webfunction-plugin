package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.AbstractStardogModule;
import com.complexible.stardog.plan.eval.service.Service;
import com.complexible.stardog.security.SecurityResourceTypes;
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
    }
}