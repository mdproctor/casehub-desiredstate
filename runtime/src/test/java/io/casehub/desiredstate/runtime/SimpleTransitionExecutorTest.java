package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SimpleTransitionExecutorTest {

    private DesiredStateGraphFactory factory;
    private MockNodeProvisioner mockProvisioner;
    private SimpleTransitionExecutor executor;

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
        mockProvisioner = new MockNodeProvisioner();
        executor = new SimpleTransitionExecutor(mockProvisioner);
    }

    @Test
    void executesRemovalsThenAdditionsInOrder() {
        DesiredNode nodeToRemove = new DesiredNode(
            NodeId.of("old"), NodeType.of("test"), new TestSpec("old"), false
        );
        DesiredNode nodeToAdd = new DesiredNode(
            NodeId.of("new"), NodeType.of("test"), new TestSpec("new"), false
        );

        DesiredStateGraph graph = factory.of(List.of(nodeToAdd), List.of());

        TransitionPlan plan = new TransitionPlan(
            List.of(new OrderedStep(nodeToRemove, StepAction.DEPROVISION)),
            List.of(new OrderedStep(nodeToAdd, StepAction.PROVISION)),
            graph,
            graph
        );

        TransitionResult result = executor.execute(plan)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        // Both should succeed
        assertEquals(2, result.outcomes().size());
        assertTrue(result.outcomes().get(NodeId.of("old")) instanceof StepOutcome.Succeeded);
        assertTrue(result.outcomes().get(NodeId.of("new")) instanceof StepOutcome.Succeeded);

        // Verify order: deprovision called first, then provision
        assertEquals(2, mockProvisioner.callOrder.size());
        assertEquals("deprovision:old", mockProvisioner.callOrder.get(0));
        assertEquals("provision:new", mockProvisioner.callOrder.get(1));
    }

    @Test
    void skipsHumanNodes() {
        DesiredNode humanNode = new DesiredNode(
            NodeId.of("h1"), NodeType.of("test"), new TestSpec("human"), true
        );
        DesiredNode normalNode = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("normal"), false
        );

        DesiredStateGraph graph = factory.of(List.of(humanNode, normalNode), List.of());

        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(
                new OrderedStep(humanNode, StepAction.PROVISION),
                new OrderedStep(normalNode, StepAction.PROVISION)
            ),
            graph,
            graph
        );

        TransitionResult result = executor.execute(plan)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        // Human node skipped, normal node provisioned
        StepOutcome humanOutcome = result.outcomes().get(NodeId.of("h1"));
        assertTrue(humanOutcome instanceof StepOutcome.Skipped);
        assertEquals("requires human", ((StepOutcome.Skipped) humanOutcome).reason());

        assertTrue(result.outcomes().get(NodeId.of("n1")) instanceof StepOutcome.Succeeded);

        // Only normal node provisioned
        assertEquals(1, mockProvisioner.callOrder.size());
        assertEquals("provision:n1", mockProvisioner.callOrder.get(0));
    }

    @Test
    void provisionFailure_recordsFailedOutcome() {
        DesiredNode node = new DesiredNode(
            NodeId.of("failing"), NodeType.of("test"), new TestSpec("fail"), false
        );

        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        // Configure mock to fail
        mockProvisioner.shouldFail = true;

        TransitionPlan plan = new TransitionPlan(
            List.of(),
            List.of(new OrderedStep(node, StepAction.PROVISION)),
            graph,
            graph
        );

        TransitionResult result = executor.execute(plan)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        StepOutcome outcome = result.outcomes().get(NodeId.of("failing"));
        assertTrue(outcome instanceof StepOutcome.Failed);
        assertEquals("mock failure", ((StepOutcome.Failed) outcome).reason());
    }

    @Test
    void deprovisionFailure_recordsFailedOutcome() {
        DesiredNode node = new DesiredNode(
            NodeId.of("failing"), NodeType.of("test"), new TestSpec("fail"), false
        );

        DesiredStateGraph graph = factory.empty();

        // Configure mock to fail
        mockProvisioner.shouldFail = true;

        TransitionPlan plan = new TransitionPlan(
            List.of(new OrderedStep(node, StepAction.DEPROVISION)),
            List.of(),
            graph,
            graph
        );

        TransitionResult result = executor.execute(plan)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        StepOutcome outcome = result.outcomes().get(NodeId.of("failing"));
        assertTrue(outcome instanceof StepOutcome.Failed);
        assertEquals("mock failure", ((StepOutcome.Failed) outcome).reason());
    }

    // Helper test spec
    record TestSpec(String value) implements NodeSpec {}

    // Mock provisioner for testing
    static class MockNodeProvisioner implements NodeProvisioner {
        List<String> callOrder = new java.util.ArrayList<>();
        boolean shouldFail = false;

        @Override
        public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
            callOrder.add("provision:" + node.id().value());
            if (shouldFail) {
                return new ProvisionResult.Failed("mock failure");
            }
            return new ProvisionResult.Success();
        }

        @Override
        public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
            callOrder.add("deprovision:" + node.id().value());
            if (shouldFail) {
                return new DeprovisionResult.Failed("mock failure");
            }
            return new DeprovisionResult.Success();
        }
    }
}
