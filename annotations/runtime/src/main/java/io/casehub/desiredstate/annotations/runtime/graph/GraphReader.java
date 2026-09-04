package io.casehub.desiredstate.annotations.runtime.graph;

import java.util.Map;
import java.util.Set;

public interface GraphReader<G, N> {
    Map<String, N> nodes(G graph);
    N node(G graph, String id);
    String nodeId(N node);
    String nodeType(N node);
    Set<String> dependenciesOf(G graph, String nodeId);
    Set<String> dependentsOf(G graph, String nodeId);
}
