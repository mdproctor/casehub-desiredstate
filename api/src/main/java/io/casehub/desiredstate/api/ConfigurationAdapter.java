package io.casehub.desiredstate.api;

import java.util.Optional;

public interface ConfigurationAdapter {
    Optional<AdaptedConfiguration> adapt(RetrievedConfiguration retrieved, RetrievalContext context);
}
