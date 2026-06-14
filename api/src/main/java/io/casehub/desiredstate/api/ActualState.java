package io.casehub.desiredstate.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of the actual state: the observed status of each known node.
 * Produced by an {@link io.casehub.desiredstate.api.spi.ActualStateAdapter}.
 */
public record ActualState(Map<NodeId, NodeStatus> statuses) {

    public ActualState {
        Objects.requireNonNull(statuses, "statuses must not be null");
        statuses = Map.copyOf(statuses);
    }

    /**
     * Returns the status of a node, or empty if the node was not observed.
     */
    public Optional<NodeStatus> statusOf(NodeId nodeId) {
        return Optional.ofNullable(statuses.get(nodeId));
    }
}
