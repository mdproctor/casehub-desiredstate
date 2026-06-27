package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simple sequential transition executor.
 * Executes removals first, then additions, calling the NodeProvisioner for each step.
 * Delegates requiresHuman nodes to the HumanNodeHandler.
 */
@DefaultBean
@ApplicationScoped
public class SimpleTransitionExecutor implements TransitionExecutor {

    private static final String INSTRUMENTATION_NAME = "io.casehub.desiredstate";

    private final NodeProvisioner provisioner;
    private final HumanNodeHandler humanNodeHandler;

    public SimpleTransitionExecutor(NodeProvisioner provisioner, HumanNodeHandler humanNodeHandler) {
        this.provisioner = provisioner;
        this.humanNodeHandler = humanNodeHandler;
    }

    @Override
    public Uni<TransitionResult> execute(TransitionPlan plan, String tenancyId) {
        return Uni.createFrom().item(() -> {
            Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();

            // Execute removals first
            for (OrderedStep step : plan.removals()) {
                StepOutcome outcome = executeDeprovision(step.node(), plan.before(), tenancyId);
                outcomes.put(step.node().id(), outcome);
            }

            // Then execute additions
            for (OrderedStep step : plan.additions()) {
                StepOutcome outcome = executeProvision(step.node(), plan.after(), tenancyId);
                outcomes.put(step.node().id(), outcome);
            }

            return new TransitionResult(outcomes);
        });
    }

    private StepOutcome executeProvision(DesiredNode node, DesiredStateGraph graph, String tenancyId) {
        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("provision")
                .setAttribute(AttributeKey.stringKey("desiredstate.node.id"), node.id().value())
                .setAttribute(AttributeKey.stringKey("desiredstate.node.type"), node.type().value())
                .setAttribute(AttributeKey.booleanKey("desiredstate.requires.human"), node.requiresHuman())
                .startSpan();
        try (Scope scope = span.makeCurrent()) {
            ProvisionContext context = new ProvisionContext(tenancyId, graph);
            if (node.requiresHuman()) {
                return humanNodeHandler.onProvision(node, context);
            }

            ProvisionResult result = provisioner.provision(node, context);

            return switch (result) {
                case ProvisionResult.Success ignored -> new StepOutcome.Succeeded();
                case ProvisionResult.Failed f -> {
                    span.setStatus(StatusCode.ERROR, f.reason());
                    yield new StepOutcome.Failed(f.reason());
                }
                case ProvisionResult.PendingApproval pa ->
                    new StepOutcome.Skipped("pending approval: " + pa.planReference());
            };
        } finally {
            span.end();
        }
    }

    private StepOutcome executeDeprovision(DesiredNode node, DesiredStateGraph graph, String tenancyId) {
        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("deprovision")
                .setAttribute(AttributeKey.stringKey("desiredstate.node.id"), node.id().value())
                .setAttribute(AttributeKey.stringKey("desiredstate.node.type"), node.type().value())
                .startSpan();
        try (Scope scope = span.makeCurrent()) {
            DeprovisionContext context = new DeprovisionContext(tenancyId, graph);
            DeprovisionResult result = provisioner.deprovision(node, context);

            return switch (result) {
                case DeprovisionResult.Success ignored -> new StepOutcome.Succeeded();
                case DeprovisionResult.Failed f -> {
                    span.setStatus(StatusCode.ERROR, f.reason());
                    yield new StepOutcome.Failed(f.reason());
                }
                case DeprovisionResult.PendingApproval pa ->
                    new StepOutcome.Skipped("pending approval: " + pa.planReference());
            };
        } finally {
            span.end();
        }
    }
}
