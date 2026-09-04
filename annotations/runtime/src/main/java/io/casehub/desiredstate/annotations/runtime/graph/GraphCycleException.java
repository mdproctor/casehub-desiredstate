package io.casehub.desiredstate.annotations.runtime.graph;

import java.util.List;

public class GraphCycleException extends RuntimeException {
    private final List<String> cycle;

    public GraphCycleException(List<String> cycle) {
        super("Cyclic dependency detected: " + String.join(" → ", cycle));
        this.cycle = List.copyOf(cycle);
    }

    public List<String> getCycle() { return cycle; }
}
