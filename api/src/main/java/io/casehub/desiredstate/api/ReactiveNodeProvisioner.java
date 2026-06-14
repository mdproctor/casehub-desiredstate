package io.casehub.desiredstate.api;

import io.smallrye.mutiny.Uni;

public interface ReactiveNodeProvisioner {
    Uni<ProvisionResult> provision(DesiredNode node, ProvisionContext context);
    Uni<DeprovisionResult> deprovision(DesiredNode node, DeprovisionContext context);
}
