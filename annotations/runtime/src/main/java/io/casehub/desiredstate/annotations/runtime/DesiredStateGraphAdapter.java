package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.annotations.runtime.graph.GraphCycleException;
import io.casehub.desiredstate.annotations.runtime.graph.GraphReader;
import io.casehub.desiredstate.annotations.runtime.graph.GraphWriter;
import io.casehub.desiredstate.api.CyclicDependencyException;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class DesiredStateGraphAdapter
        implements GraphReader<DesiredStateGraph, DesiredNode>,
                   GraphWriter<DesiredStateGraph, DesiredNode> {

    @Override
    public Map<String, DesiredNode> nodes(DesiredStateGraph graph) {
        Map<String, DesiredNode> result = new LinkedHashMap<>();
        for (var entry : graph.nodes().entrySet()) {
            result.put(entry.getKey().value(), entry.getValue());
        }
        return result;
    }

    @Override
    public DesiredNode node(DesiredStateGraph graph, String id) {
        return graph.nodes().get(NodeId.of(id));
    }

    @Override
    public String nodeId(DesiredNode node) {return node.id().value();}

    @Override
    public String nodeType(DesiredNode node) {return node.type().value();}

    @Override
    public Set<String> dependenciesOf(DesiredStateGraph graph, String nodeId) {
        return graph.dependenciesOf(NodeId.of(nodeId)).stream()
                    .map(NodeId::value).collect(Collectors.toSet());
    }

    @Override
    public Set<String> dependentsOf(DesiredStateGraph graph, String nodeId) {
        return graph.dependentsOf(NodeId.of(nodeId)).stream()
                    .map(NodeId::value).collect(Collectors.toSet());
    }

    @Override
    public DesiredStateGraph applyMutation(DesiredStateGraph graph, GraphMutation<DesiredNode> mutation) {
        try {
            return graph.withMutation(mutation);
        } catch (CyclicDependencyException e) {
            throw new GraphCycleException(
                    e.getCycle().stream().map(NodeId::value).toList());
        }
    }
}
