package io.casehub.desiredstate.api;

import io.casehub.ras.api.ActiveSituation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class RetrievalContextTest {

    private final DesiredStateGraph graph = emptyGraph();
    private final ActualState actual = new ActualState(Map.of());

    @Test
    void forFault_shouldPopulateFaultEvent() {
        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout");
        RetrievalContext ctx = RetrievalContext.forFault(graph, actual, event);

        assertThat(ctx.faultEvent()).isEqualTo(event);
        assertThat(ctx.situation()).isNull();
        assertThat(ctx.currentGraph()).isSameAs(graph);
        assertThat(ctx.actualState()).isSameAs(actual);
    }

    @Test
    void forSituation_shouldPopulateSituation() {
        ActiveSituation situation = new ActiveSituation(
            "sit-1", "zone-A", "tenant-1", 0.95,
            Map.of(), Instant.now().minusSeconds(60), Instant.now(), 3);
        RetrievalContext ctx = RetrievalContext.forSituation(graph, actual, situation);

        assertThat(ctx.situation()).isEqualTo(situation);
        assertThat(ctx.faultEvent()).isNull();
    }

    @Test
    void forFault_shouldRejectNullGraph() {
        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "x");
        assertThatThrownBy(() -> RetrievalContext.forFault(null, actual, event))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void forFault_shouldRejectNullActual() {
        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "x");
        assertThatThrownBy(() -> RetrievalContext.forFault(graph, null, event))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void forFault_shouldRejectNullEvent() {
        assertThatThrownBy(() -> RetrievalContext.forFault(graph, actual, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void forSituation_shouldRejectNullSituation() {
        assertThatThrownBy(() -> RetrievalContext.forSituation(graph, actual, null))
            .isInstanceOf(NullPointerException.class);
    }

    static DesiredStateGraph emptyGraph() {
        return new DesiredStateGraph() {
            public Map<NodeId, DesiredNode> nodes() { return Map.of(); }
            public java.util.Set<Dependency> dependencies() { return java.util.Set.of(); }
            public java.util.Set<NodeId> dependenciesOf(NodeId n) { return java.util.Set.of(); }
            public java.util.Set<NodeId> dependentsOf(NodeId n) { return java.util.Set.of(); }
            public java.util.Set<NodeId> roots() { return java.util.Set.of(); }
            public java.util.Set<NodeId> leaves() { return java.util.Set.of(); }
            public int version() { return 0; }
            public boolean isEmpty() { return true; }
            public DesiredStateGraph withNode(DesiredNode node) { return this; }
            public DesiredStateGraph withoutNode(NodeId id) { return this; }
            public DesiredStateGraph withDependency(Dependency dep) { return this; }
            public DesiredStateGraph withoutDependency(Dependency dep) { return this; }
            public DesiredStateGraph withMutation(GraphMutation m) { return this; }
            public DesiredStateGraph overlay(DesiredStateGraph o) { return this; }
            public DesiredStateGraph connect(DesiredStateGraph o) { return this; }
        };
    }
}
