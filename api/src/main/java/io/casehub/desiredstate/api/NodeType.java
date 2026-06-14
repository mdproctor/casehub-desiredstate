package io.casehub.desiredstate.api;

import java.util.Objects;

/**
 * Classifies a node by its provisioning domain (e.g. "vm", "dns-record", "human-task").
 */
public record NodeType(String value) {

    public NodeType {
        Objects.requireNonNull(value, "NodeType value must not be null");
    }

    public static NodeType of(String value) {
        return new NodeType(value);
    }
}
