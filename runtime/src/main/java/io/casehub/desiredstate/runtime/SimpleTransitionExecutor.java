package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simple sequential transition executor.
 * Executes removals first, then additions, calling the NodeProvisioner for each step.
 * Skips human nodes with a StepOutcome.Skipped result.
 */
@DefaultBean
@ApplicationScoped
public class SimpleTransitionExecutor implements TransitionExecutor {

    private static final String DEFAULT_TENANCY = "default";

    private final NodeProvisioner provisioner;

    public SimpleTransitionExecutor(NodeProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @Override
    public Uni<TransitionResult> execute(TransitionPlan plan) {
        return Uni.createFrom().item(() -> {
            Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();

            // Execute removals first
            for (OrderedStep step : plan.removals()) {
                StepOutcome outcome = executeDeprovision(step.node(), plan.before());
                outcomes.put(step.node().id(), outcome);
            }

            // Then execute additions
            for (OrderedStep step : plan.additions()) {
                StepOutcome outcome = executeProvision(step.node(), plan.after());
                outcomes.put(step.node().id(), outcome);
            }

            return new TransitionResult(outcomes);
        });
    }

    private StepOutcome executeProvision(DesiredNode node, DesiredStateGraph graph) {
        if (node.requiresHuman()) {
            return new StepOutcome.Skipped("requires human");
        }

        ProvisionContext context = new ProvisionContext(DEFAULT_TENANCY, graph);

        ProvisionResult result = provisioner.provision(node, context);

        return switch (result) {
            case ProvisionResult.Success ignored -> new StepOutcome.Succeeded();
            case ProvisionResult.Failed f -> new StepOutcome.Failed(f.reason());
        };
    }

    private StepOutcome executeDeprovision(DesiredNode node, DesiredStateGraph graph) {
        DeprovisionContext context = new DeprovisionContext(DEFAULT_TENANCY, graph);

        DeprovisionResult result = provisioner.deprovision(node, context);

        return switch (result) {
            case DeprovisionResult.Success ignored -> new StepOutcome.Succeeded();
            case DeprovisionResult.Failed f -> new StepOutcome.Failed(f.reason());
        };
    }
}
