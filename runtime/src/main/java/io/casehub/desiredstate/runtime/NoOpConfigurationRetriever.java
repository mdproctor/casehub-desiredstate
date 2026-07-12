package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ConfigurationRetriever;
import io.casehub.desiredstate.api.RetrievalContext;
import io.casehub.desiredstate.api.RetrievedConfiguration;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@DefaultBean
@ApplicationScoped
public class NoOpConfigurationRetriever implements ConfigurationRetriever {
    @Override
    public List<RetrievedConfiguration> retrieve(RetrievalContext context, int maxResults) {
        return List.of();
    }
}
