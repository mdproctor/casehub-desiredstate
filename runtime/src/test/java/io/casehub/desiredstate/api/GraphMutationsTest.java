package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphMutationsTest {

    private static final NodeType REVIEW = NodeType.of("review");

    record TestSpec(String detail) implements NodeSpec { @Override public NodeType nodeType() { return NodeType.of("test"); } }

    @Test
    void addNodeDependingOn_returnsAddNodeAndAddEdge() {
        DesiredNode node      = new DesiredNode(NodeId.of("review-n1"), new TestSpec("test"), HumanGating.NONE);
        NodeId      dependsOn = NodeId.of("n1");

        List<GraphMutation<DesiredNode>> mutations = GraphMutations.addNodeDependingOn(node, dependsOn);

        assertThat(mutations).hasSize(2);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.AddNode.class);
        GraphMutation.AddNode<DesiredNode> addNode = (GraphMutation.AddNode<DesiredNode>) mutations.get(0);
        assertThat(addNode.node()).isEqualTo(node);

        assertThat(mutations.get(1)).isInstanceOf(GraphMutation.AddEdge.class);
        GraphMutation.AddEdge<?> addEdge = (GraphMutation.AddEdge<?>) mutations.get(1);
        assertThat(addEdge.from()).isEqualTo("review-n1");
        assertThat(addEdge.to()).isEqualTo("n1");
    }
}
