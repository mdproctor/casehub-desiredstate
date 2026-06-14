package io.casehub.desiredstate.api;

import java.util.Objects;

/**
 * Unique identifier for a node in the desired-state graph.
 */
public record NodeId(String value) {

    public NodeId {
        Objects.requireNonNull(value, "NodeId value must not be null");
    }

    public static NodeId of(String value) {
        return new NodeId(value);
    }
}
