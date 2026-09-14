package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualStateAdapterRouter;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.logging.Logger;

@ApplicationScoped
public class SituationRecompilerDispatch {

    private static final Logger LOG = Logger.getLogger(SituationRecompilerDispatch.class.getName());

    @Inject SituationRecompilerEngine engine;
    @Inject LifecycleManager lifecycleManager;
    @Inject ReconciliationLoop reconciliationLoop;
    @Inject ActualStateAdapterRouter actualStateRouter;
    @Inject DesiredStateGraphFactory graphFactory;

    void onSituationChange(@ObservesAsync SituationChangeEvent event) {
        String tenancyId = event.tenancyId();
        DesiredStateGraph currentGraph = reconciliationLoop.getDesired(tenancyId);
        if (currentGraph == null) {
            return;
        }

        var actual = actualStateRouter.readActual(currentGraph, tenancyId);

        switch (event.changeType()) {
            case TRIGGERED -> {
                ActiveSituation situation = toActiveSituation(event);
                Optional<CompilationResult> result = engine.recompile(
                        tenancyId, currentGraph, actual, situation, graphFactory);
                applyResult(tenancyId, result);
            }
            case RESOLVED -> {
                Optional<CompilationResult> result = engine.situationResolved(
                        tenancyId, event.situationId(), currentGraph, actual, graphFactory);
                applyResult(tenancyId, result);
            }
            default -> { }
        }
    }

    private void applyResult(String tenancyId, Optional<CompilationResult> result) {
        if (result.isEmpty()) {
            return;
        }
        lifecycleManager.updateDesired(tenancyId, result.get());
        reconciliationLoop.requestReconciliation(tenancyId);
        LOG.fine(() -> "Situation recompilation applied for tenant " + tenancyId);
    }

    static ActiveSituation toActiveSituation(SituationChangeEvent event) {
        SituationContext ctx = event.context();
        double confidence = ctx.detections().isEmpty() ? 0.5
                : ctx.detections().getLast().result().confidence();
        return new ActiveSituation(
                event.situationId(),
                event.correlationKey(),
                event.tenancyId(),
                confidence,
                event.metadata(),
                ctx.firstSignal(),
                ctx.lastSignal(),
                ctx.triggerCount());
    }
}
