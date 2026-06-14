package io.casehub.desiredstate.api;

public class DanglingDependencyException extends RuntimeException {
    private final NodeId from;
    private final NodeId missingTo;

    public DanglingDependencyException(NodeId from, NodeId missingTo) {
        super("Dangling dependency: " + from.value() + " depends on " +
              missingTo.value() + " which is not in the graph");
        this.from = from;
        this.missingTo = missingTo;
    }

    public NodeId getFrom() {
        return from;
    }

    public NodeId getMissingTo() {
        return missingTo;
    }
}
