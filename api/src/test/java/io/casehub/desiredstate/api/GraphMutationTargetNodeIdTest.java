package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphMutationTargetNodeIdTest {

    record TestSpec() implements NodeSpec {
        @Override
        public NodeType nodeType() {return NodeType.of("test");}
    }

    @Test
    void addNodeTargetNodeId() {
        var node     = new DesiredNode(NodeId.of("a"), new TestSpec(), HumanGating.NONE);
        var mutation = new GraphMutation.AddNode<>(node.id().value(), node);
        assertThat(mutation.targetNodeId()).isEqualTo("a");
    }

    @Test
    void removeNodeTargetNodeId() {
        var mutation = new GraphMutation.RemoveNode<>("b");
        assertThat(mutation.targetNodeId()).isEqualTo("b");
    }

    @Test
    void updateNodeTargetNodeId() {
        var node     = new DesiredNode(NodeId.of("c"), new TestSpec(), HumanGating.NONE);
        var mutation = new GraphMutation.UpdateNode<>("c", node);
        assertThat(mutation.targetNodeId()).isEqualTo("c");
    }

    @Test
    void addEdgeTargetNodeIdIsNull() {
        var mutation = new GraphMutation.AddEdge<>("a", "b");
        assertThat(mutation.targetNodeId()).isNull();
    }

    @Test
    void removeEdgeTargetNodeIdIsNull() {
        var mutation = new GraphMutation.RemoveEdge<>("a", "b");
        assertThat(mutation.targetNodeId()).isNull();
    }
}
