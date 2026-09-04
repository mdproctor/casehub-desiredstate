package io.casehub.desiredstate.api;

public class ConflictingMutationException extends RuntimeException {
    private final String           nodeId;
    private final GraphMutation<?> mutationA;
    private final GraphMutation<?> mutationB;

    public ConflictingMutationException(String nodeId, GraphMutation<?> mutationA, GraphMutation<?> mutationB) {
        super("Conflicting mutations for node " + nodeId + ": " + mutationA + " vs " + mutationB);
        this.nodeId    = nodeId;
        this.mutationA = mutationA;
        this.mutationB = mutationB;
    }

    public String getNodeId() {
        return nodeId;
    }

    public GraphMutation<?> getMutationA() {
        return mutationA;
    }

    public GraphMutation<?> getMutationB() {
        return mutationB;
    }
}
