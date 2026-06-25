package io.casehub.desiredstate.engine;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.engine.flow.FlowWorkerFunction;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionExecutor;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.api.TransitionResult;
import io.serverlessworkflow.api.types.Workflow;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Engine-backed transition executor that translates a {@link TransitionPlan} into a
 * casehub-engine {@link CaseDefinition} with Worker(Workflow) phases for prune and grow,
 * then starts it via {@link CaseHubRuntime}.
 * <p>
 * Displaces {@code SimpleTransitionExecutor} (marked {@code @DefaultBean}) by classpath
 * presence when the engine-adapter module is on the classpath.
 * <p>
 * <b>V1 simplification:</b> Case completion is reported optimistically. Proper observation
 * of case completion events (waiting for the case to finish, collecting per-node outcomes)
 * is a follow-up. The returned {@link TransitionResult} marks all steps as
 * {@link StepOutcome.Succeeded} immediately after the case is started.
 */
@ApplicationScoped
public class CaseTransitionExecutor implements TransitionExecutor {

    private static final Logger LOG = Logger.getLogger(CaseTransitionExecutor.class);

    static final String NAMESPACE = "io.casehub.desiredstate";
    static final String CASE_VERSION = "1.0.0";

    private final TransitionWorkflowGenerator workflowGenerator;
    private final CaseHubRuntime caseHubRuntime;

    @Inject
    public CaseTransitionExecutor(TransitionWorkflowGenerator workflowGenerator,
                                  CaseHubRuntime caseHubRuntime) {
        this.workflowGenerator = workflowGenerator;
        this.caseHubRuntime = caseHubRuntime;
    }

    @Override
    public Uni<TransitionResult> execute(TransitionPlan plan) {
        if (plan.isEmpty()) {
            return Uni.createFrom().item(new TransitionResult(Map.of()));
        }

        return Uni.createFrom().completionStage(() -> {
            CaseDefinition caseDefinition = buildCaseDefinition(plan);

            Map<String, Object> inputData = Map.of(
                "removals", plan.removals().size(),
                "additions", plan.additions().size(),
                "graphVersion", plan.after().version()
            );

            return caseHubRuntime.startCase(caseDefinition, inputData);
        }).map(caseId -> {
            LOG.infof("Started desired-state transition case %s (removals=%d, additions=%d)",
                caseId, plan.removals().size(), plan.additions().size());

            // V1: optimistically report success for all steps.
            // Follow-up: observe case completion events and collect actual per-node outcomes.
            return buildOptimisticResult(plan, caseId);
        });
    }

    CaseDefinition buildCaseDefinition(TransitionPlan plan) {
        List<Worker> workers = new ArrayList<>(2);
        List<Binding> bindings = new ArrayList<>(2);

        Capability dispatchCapability = Capability.builder()
            .name("desiredstate-dispatch")
            .inputSchema("{}")
            .outputSchema("{}")
            .description("Dispatches desired-state node provision/deprovision actions")
            .build();

        if (!plan.removals().isEmpty()) {
            Workflow pruneWorkflow = workflowGenerator.generate(
                plan.removals(), NAMESPACE, "prune-phase", CASE_VERSION
            );

            Worker pruneWorker = Worker.builder()
                .name("prune")
                .capabilities(dispatchCapability)
                .function(new FlowWorkerFunction(pruneWorkflow))
                .description("Removes nodes no longer in the desired state (leaves before roots)")
                .build();

            workers.add(pruneWorker);
            bindings.add(buildBinding("prune-binding", dispatchCapability));
        }

        List<OrderedStep> automatedAdditions = new ArrayList<>();
        List<OrderedStep> humanAdditions = new ArrayList<>();
        for (OrderedStep step : plan.additions()) {
            if (step.node().requiresHuman()) {
                humanAdditions.add(step);
            } else {
                automatedAdditions.add(step);
            }
        }

        if (!automatedAdditions.isEmpty()) {
            Workflow growWorkflow = workflowGenerator.generate(
                automatedAdditions, NAMESPACE, "grow-phase", CASE_VERSION
            );

            Worker growWorker = Worker.builder()
                .name("grow")
                .capabilities(dispatchCapability)
                .function(new FlowWorkerFunction(growWorkflow))
                .description("Provisions new nodes in the desired state (roots before leaves)")
                .build();

            workers.add(growWorker);
            if (plan.removals().isEmpty()) {
                bindings.add(buildBinding("grow-binding", dispatchCapability));
            }
        }

        for (OrderedStep step : humanAdditions) {
            HumanTaskTarget humanTask = HumanTaskTarget.inline()
                .title("Review: " + step.node().id().value())
                .build();

            bindings.add(Binding.builder()
                .name("human-" + step.node().id().value())
                .humanTask(humanTask)
                .on(new ContextChangeTrigger("."))
                .build());
        }

        return CaseDefinition.builder()
            .namespace(NAMESPACE)
            .name("desired-state-transition")
            .version(CASE_VERSION)
            .title("Desired State Transition")
            .summary("Automated desired-state transition: " + plan.removals().size()
                + " removals, " + automatedAdditions.size() + " additions, "
                + humanAdditions.size() + " human tasks")
            .workers(workers)
            .bindings(bindings)
            .build();
    }

    private Binding buildBinding(String name, Capability capability) {
        return Binding.builder()
            .name(name)
            .capability(capability)
            .on(new ContextChangeTrigger("."))
            .build();
    }

    private TransitionResult buildOptimisticResult(TransitionPlan plan, UUID caseId) {
        Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();

        for (OrderedStep step : plan.removals()) {
            outcomes.put(step.node().id(), new StepOutcome.Succeeded());
        }
        for (OrderedStep step : plan.additions()) {
            if (step.node().requiresHuman()) {
                outcomes.put(step.node().id(), new StepOutcome.Skipped("routed to WorkItem"));
            } else {
                outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            }
        }

        return new TransitionResult(outcomes);
    }
}
