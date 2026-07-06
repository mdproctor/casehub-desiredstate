package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.NodeId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory simulation of the expansion world. Tracks the state of all structures
 * (probes, nexuses, pylons, cannons) and defense postures (patrol, monitor, response).
 */
public class ExpansionWorld {

    public enum StructureState {
        BUILDING,
        BUILT,
        DESTROYED,
        PATROLLING,
        MONITORING,
        RESPONDING
    }

    private final Map<NodeId, StructureState> structures = new ConcurrentHashMap<>();

    /**
     * Start building a structure.
     */
    public void build(NodeId id) {
        structures.put(id, StructureState.BUILDING);
    }

    /**
     * Mark a structure as completed.
     */
    public void complete(NodeId id) {
        structures.put(id, StructureState.BUILT);
    }

    /**
     * Destroy a structure.
     */
    public void destroy(NodeId id) {
        structures.put(id, StructureState.DESTROYED);
    }

    /**
     * Set a structure to patrolling state.
     */
    public void setPatrolling(NodeId id) {
        structures.put(id, StructureState.PATROLLING);
    }

    /**
     * Set a structure to monitoring state.
     */
    public void setMonitoring(NodeId id) {
        structures.put(id, StructureState.MONITORING);
    }

    /**
     * Set a structure to responding state.
     */
    public void setResponding(NodeId id) {
        structures.put(id, StructureState.RESPONDING);
    }

    /**
     * Get the current state of a structure.
     */
    public StructureState state(NodeId id) {
        return structures.get(id);
    }

    /**
     * Check if a structure is built (BUILT state).
     */
    public boolean isBuilt(NodeId id) {
        return structures.get(id) == StructureState.BUILT;
    }
}
