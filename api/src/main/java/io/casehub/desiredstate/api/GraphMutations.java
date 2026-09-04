package io.casehub.desiredstate.api;

import java.util.List;

public final class GraphMutations {
    private GraphMutations() {}

    public static List<GraphMutation<DesiredNode>> addNodeDependingOn(DesiredNode node, NodeId dependsOn) {
        return List.of(
                new GraphMutation.AddNode<>(node.id().value(), node),
                new GraphMutation.AddEdge<>(node.id().value(), dependsOn.value())
                      );
    }
}
