package io.casehub.desiredstate.api;

import java.util.Objects;

/**
 * A directed dependency edge: node {@code from} depends on node {@code to}.
 * The runtime guarantees {@code to} is provisioned before {@code from}.
 */
public record Dependency(NodeId from, NodeId to) {

    public Dependency {
        Objects.requireNonNull(from, "Dependency 'from' must not be null");
        Objects.requireNonNull(to, "Dependency 'to' must not be null");
    }
}
