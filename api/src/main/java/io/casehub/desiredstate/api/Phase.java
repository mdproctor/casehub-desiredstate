package io.casehub.desiredstate.api;

import java.util.Objects;

public record Phase(String id, DesiredStateGraph graph, CompletionCondition completionCondition) {
    public Phase {
        Objects.requireNonNull(id, "Phase id must not be null");
        Objects.requireNonNull(graph, "Phase graph must not be null");
        Objects.requireNonNull(completionCondition, "Phase completionCondition must not be null");
    }

    public boolean isTerminal() {
        return completionCondition instanceof CompletionCondition.Never;
    }
}
