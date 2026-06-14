package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FaultPolicyEngineTest {

    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void noPolicies_returnsEmptyMutations() {
        FaultPolicyEngine engine = new FaultPolicyEngine(List.of());

        DesiredNode node = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("N1"), false
        );
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.NODE_DESTROYED, "detail");

        List<GraphMutation> mutations = engine.evaluate(event, graph);

        assertTrue(mutations.isEmpty());
    }

    @Test
    void singlePolicy_returnsMutations() {
        FaultPolicy policy = (event, current) -> List.of(
            new GraphMutation.RemoveNode(event.node())
        );

        FaultPolicyEngine engine = new FaultPolicyEngine(List.of(policy));

        DesiredNode node = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("N1"), false
        );
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.NODE_DESTROYED, "detail");

        List<GraphMutation> mutations = engine.evaluate(event, graph);

        assertEquals(1, mutations.size());
        assertTrue(mutations.get(0) instanceof GraphMutation.RemoveNode);
        assertEquals(NodeId.of("n1"), ((GraphMutation.RemoveNode) mutations.get(0)).id());
    }

    @Test
    void multiplePolicies_mergesMutations() {
        FaultPolicy policy1 = (event, current) -> List.of(
            new GraphMutation.RemoveNode(NodeId.of("n1"))
        );

        FaultPolicy policy2 = (event, current) -> List.of(
            new GraphMutation.UpdateNode(NodeId.of("n2"), new TestSpec("updated"))
        );

        FaultPolicyEngine engine = new FaultPolicyEngine(List.of(policy1, policy2));

        DesiredNode node1 = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("N1"), false
        );
        DesiredNode node2 = new DesiredNode(
            NodeId.of("n2"), NodeType.of("test"), new TestSpec("N2"), false
        );
        DesiredStateGraph graph = factory.of(List.of(node1, node2), List.of());

        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.NODE_DESTROYED, "detail");

        List<GraphMutation> mutations = engine.evaluate(event, graph);

        assertEquals(2, mutations.size());
    }

    @Test
    void sameMutationFromTwoPolicies_deduplicated() {
        FaultPolicy policy1 = (event, current) -> List.of(
            new GraphMutation.RemoveNode(NodeId.of("n1"))
        );

        FaultPolicy policy2 = (event, current) -> List.of(
            new GraphMutation.RemoveNode(NodeId.of("n1"))
        );

        FaultPolicyEngine engine = new FaultPolicyEngine(List.of(policy1, policy2));

        DesiredNode node = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("N1"), false
        );
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.NODE_DESTROYED, "detail");

        List<GraphMutation> mutations = engine.evaluate(event, graph);

        assertEquals(1, mutations.size());
    }

    @Test
    void conflictingMutations_throwsException() {
        FaultPolicy policy1 = (event, current) -> List.of(
            new GraphMutation.RemoveNode(NodeId.of("n1"))
        );

        FaultPolicy policy2 = (event, current) -> List.of(
            new GraphMutation.UpdateNode(NodeId.of("n1"), new TestSpec("updated"))
        );

        FaultPolicyEngine engine = new FaultPolicyEngine(List.of(policy1, policy2));

        DesiredNode node = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("N1"), false
        );
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.NODE_DESTROYED, "detail");

        ConflictingMutationException ex = assertThrows(
            ConflictingMutationException.class,
            () -> engine.evaluate(event, graph)
        );

        assertEquals(NodeId.of("n1"), ex.getNodeId());
    }

    @Test
    void dependencyMutations_noConflict() {
        FaultPolicy policy1 = (event, current) -> List.of(
            new GraphMutation.AddDependency(new Dependency(NodeId.of("n1"), NodeId.of("n2")))
        );

        FaultPolicy policy2 = (event, current) -> List.of(
            new GraphMutation.AddDependency(new Dependency(NodeId.of("n1"), NodeId.of("n3")))
        );

        FaultPolicyEngine engine = new FaultPolicyEngine(List.of(policy1, policy2));

        DesiredNode node1 = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("N1"), false
        );
        DesiredStateGraph graph = factory.of(List.of(node1), List.of());

        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.DEPENDENCY_UNAVAILABLE, "detail");

        List<GraphMutation> mutations = engine.evaluate(event, graph);

        assertEquals(2, mutations.size());
        assertTrue(mutations.stream().allMatch(m -> m instanceof GraphMutation.AddDependency));
    }

    // Helper test spec
    record TestSpec(String value) implements NodeSpec {}
}
