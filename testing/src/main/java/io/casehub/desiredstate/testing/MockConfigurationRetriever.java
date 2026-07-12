package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.ConfigurationRetriever;
import io.casehub.desiredstate.api.RetrievalContext;
import io.casehub.desiredstate.api.RetrievedConfiguration;

import java.util.ArrayList;
import java.util.List;

public class MockConfigurationRetriever implements ConfigurationRetriever {

    private List<RetrievedConfiguration> results = List.of();
    private final List<RetrievalContext> receivedContexts = new ArrayList<>();

    @Override
    public List<RetrievedConfiguration> retrieve(RetrievalContext context, int maxResults) {
        receivedContexts.add(context);
        return results.size() > maxResults ? results.subList(0, maxResults) : results;
    }

    public void setResults(List<RetrievedConfiguration> results) {
        this.results = List.copyOf(results);
    }

    public List<RetrievalContext> receivedContexts() {
        return List.copyOf(receivedContexts);
    }

    public void clear() {
        results = List.of();
        receivedContexts.clear();
    }
}
