package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@DefaultBean
@ApplicationScoped
public class NoOpConfigurationAdapter implements ConfigurationAdapter {
    @Override
    public Optional<AdaptedConfiguration> adapt(RetrievedConfiguration retrieved, RetrievalContext context) {
        return Optional.empty();
    }
}
