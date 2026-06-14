package io.casehub.desiredstate.api;

import java.util.Objects;

/**
 * Context passed to a provisioner when deprovisioning a node.
 * Carries tenancy identity and the full desired-state graph for reference.
 */
public record DeprovisionContext(String tenancyId, DesiredStateGraph graph) {

    public DeprovisionContext {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
        Objects.requireNonNull(graph, "graph must not be null");
    }
}
