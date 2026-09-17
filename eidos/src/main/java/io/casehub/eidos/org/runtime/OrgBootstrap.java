package io.casehub.eidos.org.runtime;

import io.casehub.eidos.org.api.OrgRegistry;
import io.casehub.eidos.org.api.spi.OrgRegistrar;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class OrgBootstrap {

    @Inject
    OrgRegistry registry;
    @Inject
    @Any
    Instance<OrgRegistrar> registrars;

    void onStartup(@Observes StartupEvent ev) {
        for (var registrar : registrars) {
            var def = registrar.organization();
            def.units().forEach(registry::registerUnit);
            def.relationships().forEach(registry::addRelationship);
        }
    }
}
