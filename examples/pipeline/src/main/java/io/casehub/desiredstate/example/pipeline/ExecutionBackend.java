package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;

public interface ExecutionBackend {
    boolean handles(DesiredNode node);
    ProvisionResult provision(DesiredNode node, ProvisionContext context);
    DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context);
}
