package io.casehub.desiredstate.api;

import java.util.List;

public sealed interface CompilationResult {

    record SingleGraph(DesiredStateGraph graph) implements CompilationResult {
        public SingleGraph {
            java.util.Objects.requireNonNull(graph, "Graph must not be null");
        }
    }

    record Lifecycle(List<Phase> phases) implements CompilationResult {
        public Lifecycle {
            java.util.Objects.requireNonNull(phases, "Phases must not be null");
        }
    }

    static CompilationResult single(DesiredStateGraph graph) {
        return new SingleGraph(graph);
    }

    static CompilationResult lifecycle(List<Phase> phases) {
        if (phases.isEmpty()) throw new IllegalArgumentException("Lifecycle must have at least one phase");
        return new Lifecycle(List.copyOf(phases));
    }
}
