package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Mock NodeProvisioner for testing. Records all provision/deprovision calls.
 * Configurable results via setter methods (default: Success).
 */
public class MockNodeProvisioner implements NodeProvisioner {

    /** All nodes provisioned, in order. Public for test assertions. */
    public final CopyOnWriteArrayList<DesiredNode> provisioned = new CopyOnWriteArrayList<>();

    /** All nodes deprovisioned, in order. Public for test assertions. */
    public final CopyOnWriteArrayList<DesiredNode> deprovisioned = new CopyOnWriteArrayList<>();

    private Function<DesiredNode, ProvisionResult> provisionBehavior = node -> new ProvisionResult.Success();
    private Function<DesiredNode, DeprovisionResult> deprovisionBehavior = node -> new DeprovisionResult.Success();

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        provisioned.add(node);
        return provisionBehavior.apply(node);
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        deprovisioned.add(node);
        return deprovisionBehavior.apply(node);
    }

    /**
     * Set custom provision behavior. Default: Success.
     */
    public void setProvisionBehavior(Function<DesiredNode, ProvisionResult> behavior) {
        this.provisionBehavior = behavior;
    }

    /**
     * Set custom deprovision behavior. Default: Success.
     */
    public void setDeprovisionBehavior(Function<DesiredNode, DeprovisionResult> behavior) {
        this.deprovisionBehavior = behavior;
    }

    /**
     * Reset all recorded nodes and restore default success behavior.
     */
    public void clear() {
        provisioned.clear();
        deprovisioned.clear();
        provisionBehavior = node -> new ProvisionResult.Success();
        deprovisionBehavior = node -> new DeprovisionResult.Success();
    }
}
