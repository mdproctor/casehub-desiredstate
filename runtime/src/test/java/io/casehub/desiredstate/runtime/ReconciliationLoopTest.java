package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.MultiEmitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationLoopTest {

    private DesiredStateGraphFactory factory;
    private TestActualStateAdapter actualAdapter;
    private TestTransitionExecutor testExecutor;
    private TransitionPlanner planner;
    private FaultPolicyEngine faultEngine;
    private TestEventSource testEventSource;
    private ReconciliationLoop loop;

    // Short timers for testing: 50ms debounce, 1 hour resync (avoid interference)
    private static final Duration TEST_DEBOUNCE = Duration.ofMillis(50);
    private static final Duration TEST_RESYNC = Duration.ofHours(1);

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
        actualAdapter = new TestActualStateAdapter();
        testExecutor = new TestTransitionExecutor();
        planner = new TransitionPlanner();
        faultEngine = new FaultPolicyEngine(List.of());
        testEventSource = new TestEventSource();

        loop = new ReconciliationLoop(
            planner, testExecutor, actualAdapter, faultEngine, testEventSource,
            TEST_DEBOUNCE, TEST_RESYNC);
    }

    @AfterEach
    void tearDown() {
        // Stop all loops to clean up threads
        loop.stop("test-tenant");
    }

    @Test
    void start_triggersInitialReconciliation() {
        // Desired: two nodes. Actual: empty (all UNKNOWN).
        DesiredNode nodeA = node("a");
        DesiredNode nodeB = node("b");
        DesiredStateGraph desired = factory.of(List.of(nodeA, nodeB), List.of());

        // Actual returns empty — all nodes are UNKNOWN → should plan additions
        actualAdapter.setStatuses(Map.of());

        loop.start("test-tenant", desired);

        // Wait for the initial reconciliation to produce an executed plan
        await().atMost(Duration.ofSeconds(2)).until(() -> !testExecutor.executedPlans.isEmpty());

        TransitionPlan plan = testExecutor.executedPlans.get(0);
        assertFalse(plan.isEmpty(), "Plan should have additions for unknown nodes");
        assertEquals(2, plan.additions().size(), "Both nodes should be planned for addition");
        assertTrue(plan.removals().isEmpty(), "No removals expected");
    }

    @Test
    void eventDriven_triggersReconciliation() {
        // Start with an already-reconciled state (actual matches desired — no diff)
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of(NodeId.of("a"), NodeStatus.PRESENT));

        loop.start("test-tenant", desired);

        // Initial reconciliation should produce an empty plan (no diff), which is not executed
        // because TransitionPlanner produces it but ReconciliationLoop skips empty plans.
        // Wait a beat for the initial cycle to complete.
        await().atMost(Duration.ofSeconds(1)).pollDelay(Duration.ofMillis(100)).until(() -> true);

        // Clear any executed plans from the initial cycle
        testExecutor.executedPlans.clear();

        // Now simulate a state change: node "a" drifts, adapter reports it as ABSENT
        actualAdapter.setStatuses(Map.of());

        // Push an event to trigger reconciliation
        testEventSource.emit(new StateEvent(NodeId.of("a"), NodeStatus.ABSENT, "node lost"));

        // Wait for the debounced event-driven reconciliation
        await().atMost(Duration.ofSeconds(2)).until(() -> !testExecutor.executedPlans.isEmpty());

        TransitionPlan plan = testExecutor.executedPlans.get(0);
        assertFalse(plan.isEmpty());
        assertEquals(1, plan.additions().size());
        assertEquals(NodeId.of("a"), plan.additions().get(0).node().id());
    }

    @Test
    void updateDesired_nextCycleUsesNewGraph() {
        // Start with one node
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of());

        loop.start("test-tenant", desired);

        // Wait for initial reconciliation
        await().atMost(Duration.ofSeconds(2)).until(() -> !testExecutor.executedPlans.isEmpty());

        // Clear plans from initial reconciliation
        testExecutor.executedPlans.clear();

        // Update desired to have two nodes
        DesiredNode nodeB = node("b");
        DesiredStateGraph newDesired = factory.of(List.of(nodeA, nodeB), List.of());
        loop.updateDesired("test-tenant", newDesired);

        // Simulate node "a" now exists, but "b" is new
        actualAdapter.setStatuses(Map.of(NodeId.of("a"), NodeStatus.PRESENT));

        // Trigger event to force a reconciliation cycle
        testEventSource.emit(new StateEvent(NodeId.of("b"), NodeStatus.ABSENT, "new node"));

        // Wait for the event-driven reconciliation
        await().atMost(Duration.ofSeconds(2)).until(() -> !testExecutor.executedPlans.isEmpty());

        TransitionPlan plan = testExecutor.executedPlans.get(0);
        assertFalse(plan.isEmpty());
        // Only node "b" should be added (node "a" is already PRESENT)
        assertEquals(1, plan.additions().size());
        assertEquals(NodeId.of("b"), plan.additions().get(0).node().id());
    }

    @Test
    void stop_preventsSubsequentReconciliation() {
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of());

        loop.start("test-tenant", desired);

        // Wait for initial reconciliation
        await().atMost(Duration.ofSeconds(2)).until(() -> !testExecutor.executedPlans.isEmpty());

        // Stop the loop
        loop.stop("test-tenant");

        // Clear executed plans
        int planCountAfterStop = testExecutor.executedPlans.size();

        // Push events — they should be ignored because the subscription is cancelled
        testEventSource.emit(new StateEvent(NodeId.of("a"), NodeStatus.ABSENT, "ignored"));

        // Wait briefly and verify no new plans were executed
        await().during(Duration.ofMillis(200)).atMost(Duration.ofMillis(500))
            .until(() -> testExecutor.executedPlans.size() == planCountAfterStop);
    }

    @Test
    void faultFeedback_appliesMutationsToDesiredGraph() {
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of());

        // Configure executor to fail node "a"
        testExecutor.failNodes.add(NodeId.of("a"));

        // Configure fault policy to add a replacement node on failure
        DesiredNode replacement = new DesiredNode(
            NodeId.of("a-replacement"), NodeType.of("test"), new TestSpec("replacement"), false);
        FaultPolicy addReplacementPolicy = (event, current) -> {
            if (event.node().equals(NodeId.of("a"))) {
                return List.of(new GraphMutation.AddNode(replacement));
            }
            return List.of();
        };
        faultEngine = new FaultPolicyEngine(List.of(addReplacementPolicy));

        loop = new ReconciliationLoop(
            planner, testExecutor, actualAdapter, faultEngine, testEventSource,
            TEST_DEBOUNCE, TEST_RESYNC);

        loop.start("test-tenant", desired);

        // Wait for the initial reconciliation (which will fail node "a")
        await().atMost(Duration.ofSeconds(2)).until(() -> !testExecutor.executedPlans.isEmpty());

        // The fault feedback should have added "a-replacement" to the desired graph.
        // Trigger another reconciliation to see the mutation take effect.
        // Actual: "a" is still absent (failed to provision)
        actualAdapter.setStatuses(Map.of());

        testExecutor.executedPlans.clear();
        testEventSource.emit(new StateEvent(NodeId.of("a"), NodeStatus.ABSENT, "still absent"));

        await().atMost(Duration.ofSeconds(2)).until(() -> !testExecutor.executedPlans.isEmpty());

        TransitionPlan secondPlan = testExecutor.executedPlans.get(0);
        // The second plan should include the replacement node
        Set<String> plannedNodeIds = new HashSet<>();
        for (OrderedStep step : secondPlan.additions()) {
            plannedNodeIds.add(step.node().id().value());
        }
        assertTrue(plannedNodeIds.contains("a-replacement"),
            "Fault feedback should have added replacement node to desired graph. Planned: " + plannedNodeIds);
    }

    // --- Test helpers ---

    private DesiredNode node(String id) {
        return new DesiredNode(NodeId.of(id), NodeType.of("test"), new TestSpec(id), false);
    }

    record TestSpec(String value) implements NodeSpec {}

    /**
     * Test double for ActualStateAdapter. Returns configurable statuses.
     */
    static class TestActualStateAdapter implements ActualStateAdapter {
        private volatile Map<NodeId, NodeStatus> statuses = Map.of();

        void setStatuses(Map<NodeId, NodeStatus> statuses) {
            this.statuses = Map.copyOf(statuses);
        }

        @Override
        public ActualState readActual(DesiredStateGraph desired) {
            return new ActualState(statuses);
        }
    }

    /**
     * Test double for TransitionExecutor. Records executed plans and returns success
     * (or failure for configured nodes).
     */
    static class TestTransitionExecutor implements TransitionExecutor {
        final CopyOnWriteArrayList<TransitionPlan> executedPlans = new CopyOnWriteArrayList<>();
        final Set<NodeId> failNodes = ConcurrentHashMap.newKeySet();

        @Override
        public Uni<TransitionResult> execute(TransitionPlan plan) {
            return Uni.createFrom().item(() -> {
                executedPlans.add(plan);

                Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();
                for (OrderedStep step : plan.removals()) {
                    outcomes.put(step.node().id(), new StepOutcome.Succeeded());
                }
                for (OrderedStep step : plan.additions()) {
                    if (failNodes.contains(step.node().id())) {
                        outcomes.put(step.node().id(), new StepOutcome.Failed("test failure"));
                    } else {
                        outcomes.put(step.node().id(), new StepOutcome.Succeeded());
                    }
                }
                return new TransitionResult(outcomes);
            });
        }
    }

    /**
     * Test double for EventSource. Wraps a Multi with an emitter for pushing events.
     */
    static class TestEventSource implements EventSource {
        private final AtomicReference<MultiEmitter<? super StateEvent>> emitterRef = new AtomicReference<>();
        private final Multi<StateEvent> multi;

        TestEventSource() {
            this.multi = Multi.createFrom().emitter(emitter -> emitterRef.set(emitter));
        }

        void emit(StateEvent event) {
            MultiEmitter<? super StateEvent> emitter = emitterRef.get();
            if (emitter != null) {
                emitter.emit(event);
            }
        }

        @Override
        public Multi<StateEvent> stream() {
            return multi;
        }
    }
}
