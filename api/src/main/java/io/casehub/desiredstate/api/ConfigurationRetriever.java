package io.casehub.desiredstate.api;

import java.util.List;

public interface ConfigurationRetriever {
    List<RetrievedConfiguration> retrieve(RetrievalContext context, int maxResults);
}
