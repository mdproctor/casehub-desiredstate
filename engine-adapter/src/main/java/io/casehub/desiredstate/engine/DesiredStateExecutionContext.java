package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.DesiredStateGraph;
import java.util.Objects;

public record DesiredStateExecutionContext(DesiredStateGraph graph, String tenancyId) {
    public DesiredStateExecutionContext {
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
    }
}
