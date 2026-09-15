package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualStateAdapterRouter;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
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
    @Inject SituationSource situationSource;

    void onColdStart(@Observes StartupEvent ev) {
        for (String tenancyId : reconciliationLoop.tenantIds()) {
            var activeSituations = situationSource.activeSituations(tenancyId);
            if (activeSituations.isEmpty()) {
                continue;
            }
            DesiredStateGraph current = reconciliationLoop.getDesired(tenancyId);
            var actual = actualStateRouter.readActual(current, tenancyId);
            for (ActiveSituation situation : activeSituations) {
                var result = engine.recompile(tenancyId, current, actual, situation, graphFactory);
                if (result.isPresent()) {
                    applyResult(tenancyId, result);
                    current = reconciliationLoop.getDesired(tenancyId);
                    actual = actualStateRouter.readActual(current, tenancyId);
                }
            }
            LOG.info("Cold-start recovery: processed " + activeSituations.size()
                    + " active situation(s) for tenant " + tenancyId);
        }
    }

    void onSituationChange(@ObservesAsync SituationChangeEvent event) {
        String tenancyId = event.tenancyId();
        if (!reconciliationLoop.tenantIds().contains(tenancyId)) {
            return;
        }
        DesiredStateGraph currentGraph = reconciliationLoop.getDesired(tenancyId);

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
