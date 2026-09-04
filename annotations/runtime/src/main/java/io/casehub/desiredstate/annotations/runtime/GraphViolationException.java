package io.casehub.desiredstate.annotations.runtime;

import java.util.List;

public class GraphViolationException extends RuntimeException {

    private final List<String> affectedNodes;

    public GraphViolationException(String message) {
        super(message);
        this.affectedNodes = List.of();
    }

    public GraphViolationException(String message, String... nodes) {
        super(message);
        this.affectedNodes = List.of(nodes);
    }

    public List<String> affectedNodes() {
        return affectedNodes;
    }
}
