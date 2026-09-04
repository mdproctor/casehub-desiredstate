package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.annotations.runtime.graph.GraphCycleException;
import io.casehub.desiredstate.annotations.runtime.graph.MutableGraphView;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesiredStateGraphViewTest {

    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
    private final DesiredStateGraphAdapter adapter = new DesiredStateGraphAdapter();

    record Spec(String typeValue) implements NodeSpec {
        @Override public NodeType nodeType() { return NodeType.of(typeValue); }
    }

    private DesiredStateGraphView viewOf(DesiredStateGraph graph) {
        return new DesiredStateGraphView(graph, adapter);
    }

    @Test
    void nodesReturnsStringKeyedMap() {
        DesiredNode a = new DesiredNode(NodeId.of("a"), new Spec("type-a"), HumanGating.NONE);
        DesiredNode b = new DesiredNode(NodeId.of("b"), new Spec("type-b"), HumanGating.NONE);
        var graph = factory.of(List.of(a, b), List.of());
        var view = viewOf(graph);

        assertThat(view.nodes()).hasSize(2);
        assertThat(view.nodes().get("a")).isSameAs(a);
        assertThat(view.nodes().get("b")).isSameAs(b);
    }

    @Test
    void nodeByIdReturnsCorrectNode() {
        DesiredNode a = new DesiredNode(NodeId.of("a"), new Spec("type-a"), HumanGating.NONE);
        var graph = factory.of(List.of(a), List.of());
        var view = viewOf(graph);

        assertThat(view.node("a")).isSameAs(a);
        assertThat(view.node("nonexistent")).isNull();
    }

    @Test
    void nodeIdExtractsStringId() {
        DesiredNode a = new DesiredNode(NodeId.of("my-node"), new Spec("type-a"), HumanGating.NONE);
        var view = viewOf(factory.of(List.of(a), List.of()));

        assertThat(view.nodeId(a)).isEqualTo("my-node");
    }

    @Test
    void nodeTypeExtractsTypeString() {
        DesiredNode a = new DesiredNode(NodeId.of("a"), new Spec("type-a"), HumanGating.NONE);
        var view = viewOf(factory.of(List.of(a), List.of()));

        assertThat(view.nodeType(a)).isEqualTo("type-a");
    }

    @Test
    void dependenciesOfReturnsStringIds() {
        DesiredNode a = new DesiredNode(NodeId.of("a"), new Spec("type-a"), HumanGating.NONE);
        DesiredNode b = new DesiredNode(NodeId.of("b"), new Spec("type-b"), HumanGating.NONE);
        var dep = new Dependency(NodeId.of("a"), NodeId.of("b"));
        var graph = factory.of(List.of(a, b), List.of(dep));
        var view = viewOf(graph);

        assertThat(view.dependenciesOf("a")).isEqualTo(Set.of("b"));
        assertThat(view.dependenciesOf("b")).isEmpty();
    }

    @Test
    void dependentsOfReturnsStringIds() {
        DesiredNode a = new DesiredNode(NodeId.of("a"), new Spec("type-a"), HumanGating.NONE);
        DesiredNode b = new DesiredNode(NodeId.of("b"), new Spec("type-b"), HumanGating.NONE);
        var dep = new Dependency(NodeId.of("a"), NodeId.of("b"));
        var graph = factory.of(List.of(a, b), List.of(dep));
        var view = viewOf(graph);

        assertThat(view.dependentsOf("b")).isEqualTo(Set.of("a"));
        assertThat(view.dependentsOf("a")).isEmpty();
    }

    @Test
    void withMutationReturnsNewViewWithUpdatedGraph() {
        DesiredNode a = new DesiredNode(NodeId.of("a"), new Spec("type-a"), HumanGating.NONE);
        var view = viewOf(factory.of(List.of(a), List.of()));

        DesiredNode b = new DesiredNode(NodeId.of("b"), new Spec("type-b"), HumanGating.NONE);
        MutableGraphView<DesiredNode> updated = view.withMutation(new GraphMutation.AddNode<>(b.id().value(), b));

        assertThat(view.nodes()).hasSize(1);
        assertThat(updated.nodes()).hasSize(2);
        assertThat(updated.node("b")).isNotNull();
    }

    @Test
    void withMutationPreservesGraphAccessor() {
        DesiredNode a = new DesiredNode(NodeId.of("a"), new Spec("type-a"), HumanGating.NONE);
        var graph = factory.of(List.of(a), List.of());
        var view = viewOf(graph);

        DesiredNode b = new DesiredNode(NodeId.of("b"), new Spec("type-b"), HumanGating.NONE);
        var updated = (DesiredStateGraphView) view.withMutation(new GraphMutation.AddNode<>(b.id().value(), b));

        assertThat(updated.graph()).isNotSameAs(graph);
        assertThat(updated.graph().nodes()).containsKey(NodeId.of("b"));
    }

    @Test
    void writerCatchesCyclicDependencyAndWraps() {
        DesiredNode a = new DesiredNode(NodeId.of("a"), new Spec("type-a"), HumanGating.NONE);
        DesiredNode b = new DesiredNode(NodeId.of("b"), new Spec("type-b"), HumanGating.NONE);
        var dep = new Dependency(NodeId.of("a"), NodeId.of("b"));
        var graph = factory.of(List.of(a, b), List.of(dep));
        var view = viewOf(graph);

        assertThatThrownBy(() -> view.withMutation(
                new GraphMutation.AddEdge<>("b", "a")))
            .isInstanceOf(GraphCycleException.class);
    }
}
