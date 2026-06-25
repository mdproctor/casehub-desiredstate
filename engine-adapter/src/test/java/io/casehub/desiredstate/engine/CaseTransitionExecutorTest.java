package io.casehub.desiredstate.engine;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.event.CaseEventLogRecord;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class CaseTransitionExecutorTest {

    private TransitionWorkflowGenerator workflowGenerator;
    private CaseTransitionExecutor executor;

    @BeforeEach
    void setUp() {
        workflowGenerator = new TransitionWorkflowGenerator();
        executor = new CaseTransitionExecutor(workflowGenerator, new StubCaseHubRuntime());
    }

    static class StubCaseHubRuntime implements CaseHubRuntime {
        @Override public CompletionStage<UUID> startCase(CaseDefinition definition) {
            return CompletableFuture.completedFuture(UUID.randomUUID());
        }
        @Override public CompletionStage<UUID> startCase(CaseDefinition definition, Object inputData) {
            return CompletableFuture.completedFuture(UUID.randomUUID());
        }
        @Override public CompletionStage<UUID> startCase(CaseDefinition definition, Object inputData, UUID parentCaseId, PropagationContext ctx) {
            return CompletableFuture.completedFuture(UUID.randomUUID());
        }
        @Override public CompletionStage<UUID> startCase(CaseDefinition definition, Object inputData, Map<String, Object> semanticData) {
            return CompletableFuture.completedFuture(UUID.randomUUID());
        }
        @Override public CompletionStage<UUID> startCase(CaseDefinition definition, Object inputData, Map<String, Object> semanticData, UUID parentCaseId, PropagationContext ctx) {
            return CompletableFuture.completedFuture(UUID.randomUUID());
        }
        @Override public CompletionStage<Void> signal(UUID caseId, String path, Object value) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void cancelCase(UUID caseId) {}
        @Override public void suspendCase(UUID caseId) {}
        @Override public void resumeCase(UUID caseId) {}
        @Override public CompletionStage<Object> query(UUID caseId, String path) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public <T> CompletionStage<T> query(UUID caseId, String path, Class<T> clazz) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<List<CaseEventLogRecord>> eventLog(UUID caseId) {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletionStage<List<CaseEventLogRecord>> eventLog(UUID caseId, Set<CaseHubEventType> eventTypes) {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletionStage<List<CaseEventLogRecord>> eventLog(UUID caseId, Set<CaseHubEventType> eventTypes, Set<EventStreamType> streamTypes) {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    @Test
    void humanNodes_getHumanTaskBindings() {
        DesiredNode automatedNode = new DesiredNode(
                NodeId.of("app"), NodeType.of("service"), new TestSpec(), false);
        DesiredNode humanNode = new DesiredNode(
                NodeId.of("human-review-app"), NodeType.of("human-review"), new TestSpec(), true);

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph graph = factory.of(List.of(automatedNode, humanNode), List.of());

        TransitionPlan plan = new TransitionPlan(
                List.of(),
                List.of(
                        new OrderedStep(automatedNode, StepAction.PROVISION),
                        new OrderedStep(humanNode, StepAction.PROVISION)
                ),
                graph, graph
        );

        CaseDefinition caseDefinition = executor.buildCaseDefinition(plan);

        long humanTaskBindings = caseDefinition.getBindings().stream()
                .filter(b -> b.target() instanceof HumanTaskTarget)
                .count();

        assertThat(humanTaskBindings)
                .as("Human nodes should produce humanTask bindings")
                .isEqualTo(1);

        HumanTaskTarget target = (HumanTaskTarget) caseDefinition.getBindings().stream()
                .filter(b -> b.target() instanceof HumanTaskTarget)
                .findFirst().orElseThrow()
                .target();

        assertThat(target.title()).contains("human-review-app");
    }

    @Test
    void humanNodes_excludedFromGrowWorkflow() {
        DesiredNode automatedNode = new DesiredNode(
                NodeId.of("app"), NodeType.of("service"), new TestSpec(), false);
        DesiredNode humanNode = new DesiredNode(
                NodeId.of("human-review-app"), NodeType.of("human-review"), new TestSpec(), true);

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph graph = factory.of(List.of(automatedNode, humanNode), List.of());

        TransitionPlan plan = new TransitionPlan(
                List.of(),
                List.of(
                        new OrderedStep(automatedNode, StepAction.PROVISION),
                        new OrderedStep(humanNode, StepAction.PROVISION)
                ),
                graph, graph
        );

        CaseDefinition caseDefinition = executor.buildCaseDefinition(plan);

        assertThat(caseDefinition.getWorkers())
                .as("Grow worker should only contain automated nodes")
                .hasSize(1);
    }

    @Test
    void humanNodes_markedAsSkippedInResult() {
        DesiredNode automatedNode = new DesiredNode(
                NodeId.of("app"), NodeType.of("service"), new TestSpec(), false);
        DesiredNode humanNode = new DesiredNode(
                NodeId.of("human-review-app"), NodeType.of("human-review"), new TestSpec(), true);

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph graph = factory.of(List.of(automatedNode, humanNode), List.of());

        TransitionPlan plan = new TransitionPlan(
                List.of(),
                List.of(
                        new OrderedStep(automatedNode, StepAction.PROVISION),
                        new OrderedStep(humanNode, StepAction.PROVISION)
                ),
                graph, graph
        );

        TransitionResult result = executor.execute(plan, "default")
                .await().indefinitely();

        assertThat(result.outcomes().get(NodeId.of("human-review-app")))
                .isInstanceOf(StepOutcome.Skipped.class);
        assertThat(((StepOutcome.Skipped) result.outcomes().get(NodeId.of("human-review-app"))).reason())
                .contains("WorkItem");
    }

    @Test
    void onlyAutomatedNodes_noHumanTaskBindings() {
        DesiredNode automatedNode = new DesiredNode(
                NodeId.of("app"), NodeType.of("service"), new TestSpec(), false);

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph graph = factory.of(List.of(automatedNode), List.of());

        TransitionPlan plan = new TransitionPlan(
                List.of(),
                List.of(new OrderedStep(automatedNode, StepAction.PROVISION)),
                graph, graph
        );

        CaseDefinition caseDefinition = executor.buildCaseDefinition(plan);

        long humanTaskBindings = caseDefinition.getBindings().stream()
                .filter(b -> b.target() instanceof HumanTaskTarget)
                .count();

        assertThat(humanTaskBindings).isZero();
    }

    @Test
    void humanOnlyPlan_noWorkersOnlyHumanBindings() {
        DesiredNode humanNode = new DesiredNode(
                NodeId.of("human-review-app"), NodeType.of("human-review"), new TestSpec(), true);

        DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph graph = factory.of(List.of(humanNode), List.of());

        TransitionPlan plan = new TransitionPlan(
                List.of(),
                List.of(new OrderedStep(humanNode, StepAction.PROVISION)),
                graph, graph
        );

        CaseDefinition caseDefinition = executor.buildCaseDefinition(plan);

        assertThat(caseDefinition.getWorkers()).isEmpty();
        assertThat(caseDefinition.getBindings()).hasSize(1);
        assertThat(caseDefinition.getBindings().get(0).target()).isInstanceOf(HumanTaskTarget.class);
    }

    private record TestSpec() implements NodeSpec {}
}
