package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;

import java.util.*;

final class GraphDiff {

    private GraphDiff() {}

    static List<GraphMutation> computeMutations(DesiredStateGraph current, DesiredStateGraph adapted) {
        List<GraphMutation> mutations = new ArrayList<>();

        Set<NodeType> adaptedTypes = new HashSet<>();
        for (DesiredNode node : adapted.nodes().values()) {
            adaptedTypes.add(node.type());
        }

        for (Map.Entry<NodeId, DesiredNode> entry : adapted.nodes().entrySet()) {
            NodeId id = entry.getKey();
            DesiredNode adaptedNode = entry.getValue();
            DesiredNode currentNode = current.nodes().get(id);

            if (currentNode == null) {
                mutations.add(new GraphMutation.AddNode(adaptedNode));
            } else if (!Objects.equals(currentNode.spec(), adaptedNode.spec())) {
                mutations.add(new GraphMutation.UpdateNode(id, adaptedNode.spec()));
            }
        }

        for (Map.Entry<NodeId, DesiredNode> entry : current.nodes().entrySet()) {
            NodeId id = entry.getKey();
            DesiredNode currentNode = entry.getValue();
            if (adaptedTypes.contains(currentNode.type()) && !adapted.nodes().containsKey(id)) {
                mutations.add(new GraphMutation.RemoveNode(id));
            }
        }

        Set<NodeId> allKnownNodes = new HashSet<>();
        allKnownNodes.addAll(current.nodes().keySet());
        allKnownNodes.addAll(adapted.nodes().keySet());

        for (Dependency dep : adapted.dependencies()) {
            if (!current.dependencies().contains(dep)) {
                if (allKnownNodes.contains(dep.from()) && allKnownNodes.contains(dep.to())) {
                    mutations.add(new GraphMutation.AddDependency(dep));
                }
            }
        }

        Set<NodeId> inScopeNodeIds = new HashSet<>();
        for (Map.Entry<NodeId, DesiredNode> entry : current.nodes().entrySet()) {
            if (adaptedTypes.contains(entry.getValue().type())) {
                inScopeNodeIds.add(entry.getKey());
            }
        }
        inScopeNodeIds.addAll(adapted.nodes().keySet());

        for (Dependency dep : current.dependencies()) {
            if (inScopeNodeIds.contains(dep.from()) && inScopeNodeIds.contains(dep.to())) {
                if (!adapted.dependencies().contains(dep)) {
                    mutations.add(new GraphMutation.RemoveDependency(dep));
                }
            }
        }

        return mutations;
    }
}
