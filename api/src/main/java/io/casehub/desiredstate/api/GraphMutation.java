package io.casehub.desiredstate.api;

public sealed interface GraphMutation<N> {
    default String targetNodeId() {
        return switch (this) {
            case AddNode<?> m -> m.id();
            case RemoveNode<?> m -> m.id();
            case UpdateNode<?> m -> m.id();
            case AddEdge<?> ignored -> null;
            case RemoveEdge<?> ignored -> null;
        };
    }

    record AddNode<N>(String id, N node) implements GraphMutation<N> {}

    record RemoveNode<N>(String id) implements GraphMutation<N> {}

    record UpdateNode<N>(String id, N adaptedNode) implements GraphMutation<N> {}

    record AddEdge<N>(String from, String to) implements GraphMutation<N> {}

    record RemoveEdge<N>(String from, String to) implements GraphMutation<N> {}
}
