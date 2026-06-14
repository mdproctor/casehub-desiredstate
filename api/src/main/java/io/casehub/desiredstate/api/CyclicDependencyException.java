package io.casehub.desiredstate.api;

import java.util.List;

public class CyclicDependencyException extends RuntimeException {
    private final List<NodeId> cycle;

    public CyclicDependencyException(List<NodeId> cycle) {
        super("Cyclic dependency detected: " +
              cycle.stream().map(NodeId::value).reduce((a, b) -> a + " → " + b).orElse(""));
        this.cycle = List.copyOf(cycle);
    }

    public List<NodeId> getCycle() {
        return cycle;
    }
}
