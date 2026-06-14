package io.casehub.desiredstate.api;

import java.util.Objects;

public record StateEvent(NodeId node, NodeStatus newStatus, String detail) {
    public StateEvent {
        Objects.requireNonNull(node, "StateEvent.node must not be null");
        Objects.requireNonNull(newStatus, "StateEvent.newStatus must not be null");
    }
}
