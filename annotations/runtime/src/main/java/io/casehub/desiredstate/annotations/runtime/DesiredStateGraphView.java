package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.annotations.runtime.graph.MutableGraphView;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.DesiredStateGraph;

import java.util.Map;
import java.util.Set;

public class DesiredStateGraphView implements MutableGraphView<DesiredNode> {
    private final DesiredStateGraph graph;
    private final DesiredStateGraphAdapter adapter;

    public DesiredStateGraphView(DesiredStateGraph graph, DesiredStateGraphAdapter adapter) {
        this.graph = graph;
        this.adapter = adapter;
    }

    public DesiredStateGraph graph() { return graph; }

    @Override public Map<String, DesiredNode> nodes() { return adapter.nodes(graph); }
    @Override public DesiredNode node(String id) { return adapter.node(graph, id); }
    @Override public String nodeId(DesiredNode node) { return adapter.nodeId(node); }
    @Override public String nodeType(DesiredNode node) { return adapter.nodeType(node); }

    @Override public Set<String> dependenciesOf(String nodeId) {
        return adapter.dependenciesOf(graph, nodeId);
    }

    @Override public Set<String> dependentsOf(String nodeId) {
        return adapter.dependentsOf(graph, nodeId);
    }

    @Override
    public MutableGraphView<DesiredNode> withMutation(GraphMutation<DesiredNode> mutation) {
        return new DesiredStateGraphView(adapter.applyMutation(graph, mutation), adapter);
    }
}
