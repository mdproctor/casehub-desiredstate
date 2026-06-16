package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.EventSource;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.StateEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;

/**
 * Event source for the pipeline simulation. Emits {@link StateEvent}s that the
 * reconciliation loop uses to detect drift and trigger fault policies.
 * <p>
 * Test code calls the simulation methods ({@link #stageFailure}, {@link #schemaDrift},
 * etc.) to inject events into the stream.
 */
public class PipelineEventSource implements EventSource {

    private MultiEmitter<? super StateEvent> emitter;

    private final Multi<StateEvent> multi = Multi.createFrom().<StateEvent>emitter(e -> this.emitter = e)
        .broadcast().toAllSubscribers();

    @Override
    public Multi<StateEvent> stream() {
        return multi;
    }

    // --- Simulation methods ---

    /**
     * Simulates a stage failure — the stage is now ABSENT.
     */
    public void stageFailure(String stageId) {
        emit(new StateEvent(NodeId.of(stageId), NodeStatus.ABSENT, "Stage failed"));
    }

    /**
     * Simulates schema drift — the schema definition has diverged.
     */
    public void schemaDrift(String schemaId) {
        emit(new StateEvent(NodeId.of(schemaId), NodeStatus.DRIFTED, "Schema version mismatch"));
    }

    /**
     * Simulates a throughput drop — the stage is degraded.
     */
    public void throughputDrop(String stageId) {
        emit(new StateEvent(NodeId.of(stageId), NodeStatus.DRIFTED, "Throughput below threshold"));
    }

    /**
     * Simulates data arrival — the source is now PRESENT.
     */
    public void dataArrival(String sourceId) {
        emit(new StateEvent(NodeId.of(sourceId), NodeStatus.PRESENT, "Data arrived"));
    }

    private void emit(StateEvent event) {
        if (emitter != null) {
            emitter.emit(event);
        }
    }
}
