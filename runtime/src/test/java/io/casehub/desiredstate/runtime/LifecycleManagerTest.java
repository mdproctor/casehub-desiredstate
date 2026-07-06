package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class LifecycleManagerTest {

    private ReconciliationLoop loop;
    private LifecycleManager manager;
    private TrackingActualStateAdapter adapter;
    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        adapter = new TrackingActualStateAdapter();
        factory = new DefaultDesiredStateGraphFactory();
        loop = new ReconciliationLoop(
            new TransitionPlanner(), new ImmediateSuccessExecutor(), adapter,
            new FaultPolicyEngine(List.of()),
            () -> Multi.createFrom().nothing(),
            Duration.ofMillis(50), Duration.ofMillis(200));
        manager = new LifecycleManager(loop);
    }

    @AfterEach
    void tearDown() {
        manager.stop("t1");
    }

    @Test
    void singleGraph_startsReconciliationDirectly() throws Exception {
        DesiredNode node = new DesiredNode(NodeId.of("a"), NodeType.of("t"), new TestSpec(), false);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.makePresent(NodeId.of("a"));

        manager.start("t1", CompilationResult.single(graph));

        Thread.sleep(300);
        assertThat(loop.getDesired("t1")).isSameAs(graph);
    }

    @Test
    void lifecycle_transitionsOnCompletion() throws Exception {
        DesiredNode buildNode = new DesiredNode(NodeId.of("build"), NodeType.of("t"), new TestSpec(), false);
        DesiredNode defendNode = new DesiredNode(NodeId.of("defend"), NodeType.of("t"), new TestSpec(), false);

        DesiredStateGraph buildGraph = ImmutableDesiredStateGraph.empty().withNode(buildNode);
        DesiredStateGraph defendGraph = ImmutableDesiredStateGraph.empty().withNode(defendNode);

        adapter.makePresent(NodeId.of("build"));
        adapter.makePresent(NodeId.of("defend"));

        CompilationResult lifecycle = CompilationResult.lifecycle(List.of(
            new Phase("build", buildGraph, CompletionCondition.allPresent()),
            new Phase("defend", defendGraph, CompletionCondition.never())
        ));

        manager.start("t1", lifecycle);

        // Wait for transition — build phase completes (all present), defend phase starts
        Thread.sleep(500);
        DesiredStateGraph current = loop.getDesired("t1");
        assertThat(current.nodes()).containsKey(NodeId.of("defend"));
    }

    @Test
    void lifecycle_staysOnPhaseUntilComplete() throws Exception {
        DesiredNode buildNode = new DesiredNode(NodeId.of("build"), NodeType.of("t"), new TestSpec(), false);
        DesiredNode defendNode = new DesiredNode(NodeId.of("defend"), NodeType.of("t"), new TestSpec(), false);

        DesiredStateGraph buildGraph = ImmutableDesiredStateGraph.empty().withNode(buildNode);
        DesiredStateGraph defendGraph = ImmutableDesiredStateGraph.empty().withNode(defendNode);

        // build node is ABSENT — phase should not complete
        adapter.makeAbsent(NodeId.of("build"));

        CompilationResult lifecycle = CompilationResult.lifecycle(List.of(
            new Phase("build", buildGraph, CompletionCondition.allPresent()),
            new Phase("defend", defendGraph, CompletionCondition.never())
        ));

        manager.start("t1", lifecycle);

        Thread.sleep(300);
        DesiredStateGraph current = loop.getDesired("t1");
        assertThat(current.nodes()).containsKey(NodeId.of("build"));
        assertThat(current.nodes()).doesNotContainKey(NodeId.of("defend"));
    }

    @Test
    void stop_cleansUpLifecycleState() {
        DesiredNode node = new DesiredNode(NodeId.of("a"), NodeType.of("t"), new TestSpec(), false);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.makePresent(NodeId.of("a"));

        manager.start("t1", CompilationResult.single(graph));
        manager.stop("t1");

        assertThat(loop.activeTenantCount()).isZero();
    }

    private record TestSpec() implements NodeSpec {}

    private static class TrackingActualStateAdapter implements ActualStateAdapter {
        private final Map<NodeId, NodeStatus> statuses = new HashMap<>();
        void makePresent(NodeId id) { statuses.put(id, NodeStatus.PRESENT); }
        void makeAbsent(NodeId id) { statuses.put(id, NodeStatus.ABSENT); }
        @Override
        public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
            return new ActualState(Map.copyOf(statuses));
        }
    }

    private static class ImmediateSuccessExecutor implements TransitionExecutor {
        @Override
        public Uni<TransitionResult> execute(TransitionPlan plan, String tenancyId) {
            Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();
            for (OrderedStep step : plan.removals()) outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            for (OrderedStep step : plan.additions()) outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            return Uni.createFrom().item(new TransitionResult(outcomes));
        }
    }
}
