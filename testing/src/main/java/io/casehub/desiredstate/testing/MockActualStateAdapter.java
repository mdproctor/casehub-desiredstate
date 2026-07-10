package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock ActualStateAdapter for testing. Maintains an in-memory map of NodeId → NodeStatus.
 */
public class MockActualStateAdapter implements ActualStateAdapter {

    private final ConcurrentHashMap<NodeId, NodeStatus> statuses = new ConcurrentHashMap<>();
    private Set<NodeType> handledTypes = Set.of();

    @Override
    public Set<NodeType> handledTypes() {
        return handledTypes;
    }

    public void setHandledTypes(Set<NodeType> types) {
        this.handledTypes = Set.copyOf(types);
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        // Return snapshot of current statuses
        return new ActualState(new HashMap<>(statuses));
    }

    /**
     * Set the status of a specific node.
     */
    public void setStatus(NodeId nodeId, NodeStatus status) {
        statuses.put(nodeId, status);
    }

    /**
     * Mark all nodes in the desired graph as PRESENT.
     */
    public void setAllPresent(DesiredStateGraph desired) {
        desired.nodes().keySet().forEach(nodeId -> statuses.put(nodeId, NodeStatus.PRESENT));
    }

    /**
     * Clear all recorded statuses.
     */
    public void clear() {
        statuses.clear();
        handledTypes = Set.of();
    }

    /**
     * Direct access to the status map for test assertions.
     */
    public Map<NodeId, NodeStatus> statuses() {
        return Map.copyOf(statuses);
    }
}
