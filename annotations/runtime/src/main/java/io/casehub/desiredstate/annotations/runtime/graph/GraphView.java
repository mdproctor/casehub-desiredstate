package io.casehub.desiredstate.annotations.runtime.graph;

import java.util.Map;
import java.util.Set;

public interface GraphView<N> {
    Map<String, N> nodes();
    N node(String id);
    String nodeId(N node);
    String nodeType(N node);
    Set<String> dependenciesOf(String nodeId);
    Set<String> dependentsOf(String nodeId);
}
