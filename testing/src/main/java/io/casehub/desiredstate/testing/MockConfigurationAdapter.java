package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.*;

import java.util.*;

public class MockConfigurationAdapter implements ConfigurationAdapter {

    private AdaptedConfiguration defaultResult;
    private final Map<String, AdaptedConfiguration> resultsBySourceId = new HashMap<>();
    private final List<RetrievedConfiguration> receivedConfigurations = new ArrayList<>();

    @Override
    public Optional<AdaptedConfiguration> adapt(RetrievedConfiguration retrieved, RetrievalContext context) {
        receivedConfigurations.add(retrieved);
        AdaptedConfiguration specific = resultsBySourceId.get(retrieved.sourceId());
        if (specific != null) return Optional.of(specific);
        return Optional.ofNullable(defaultResult);
    }

    public void setDefaultResult(AdaptedConfiguration result) {
        this.defaultResult = result;
    }

    public void setResultForSource(String sourceId, AdaptedConfiguration result) {
        resultsBySourceId.put(sourceId, result);
    }

    public List<RetrievedConfiguration> receivedConfigurations() {
        return List.copyOf(receivedConfigurations);
    }

    public void clear() {
        defaultResult = null;
        resultsBySourceId.clear();
        receivedConfigurations.clear();
    }
}
