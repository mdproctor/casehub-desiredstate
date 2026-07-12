package io.casehub.desiredstate.api;

import java.util.Map;
import java.util.Objects;

public record RetrievedConfiguration(
    DesiredStateGraph graph,
    double confidence,
    String sourceId,
    Map<String, String> metadata
) {
    public RetrievedConfiguration {
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be 0.0-1.0, got: " + confidence);
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
