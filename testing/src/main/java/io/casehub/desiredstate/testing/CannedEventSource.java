package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.EventSource;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.StateEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;

/**
 * Canned EventSource for testing. Wraps a BroadcastProcessor for imperative event emission.
 * Includes convenience methods for Nefarious Dungeons events.
 */
public class CannedEventSource implements EventSource {

    private final BroadcastProcessor<StateEvent> processor = BroadcastProcessor.create();

    @Override
    public Multi<StateEvent> stream() {
        return processor;
    }

    /**
     * Emit a custom StateEvent.
     */
    public void emit(StateEvent event) {
        processor.onNext(event);
    }

    /**
     * Emit a PRESENT event (node now exists).
     */
    public void present(NodeId nodeId, String detail) {
        emit(new StateEvent(nodeId, NodeStatus.PRESENT, detail));
    }

    /**
     * Emit an ABSENT event (node no longer exists).
     */
    public void absent(NodeId nodeId, String detail) {
        emit(new StateEvent(nodeId, NodeStatus.ABSENT, detail));
    }

    /**
     * Emit a DRIFTED event (node exists but diverged from spec).
     */
    public void drifted(NodeId nodeId, String detail) {
        emit(new StateEvent(nodeId, NodeStatus.DRIFTED, detail));
    }

    /**
     * Convenience: Hero raided a resource node (now ABSENT).
     */
    public void heroRaid(NodeId nodeId) {
        absent(nodeId, "Raided by hero");
    }

    /**
     * Convenience: Cave-in destroyed a room (now ABSENT).
     */
    public void caveIn(NodeId nodeId) {
        absent(nodeId, "Cave-in");
    }

    /**
     * Convenience: Creature revolt damaged a room (DRIFTED).
     */
    public void creatureRevolt(NodeId nodeId) {
        drifted(nodeId, "Creature revolt");
    }

    /**
     * Complete the stream (no more events).
     */
    public void complete() {
        processor.onComplete();
    }

    /**
     * Fail the stream with an error.
     */
    public void fail(Throwable throwable) {
        processor.onError(throwable);
    }
}
