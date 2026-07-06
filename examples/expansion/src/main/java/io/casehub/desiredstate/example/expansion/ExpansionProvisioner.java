package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.*;

import java.util.Set;

/**
 * Provisions and deprovisions expansion structures and defense postures.
 * Handles all 7 node types: probe, nexus, pylon, cannon, patrol, monitor, response.
 */
public class ExpansionProvisioner implements NodeProvisioner {

    private final ExpansionWorld world;

    public ExpansionProvisioner(ExpansionWorld world) {
        this.world = world;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(
            ExpansionNodeTypes.PROBE,
            ExpansionNodeTypes.NEXUS,
            ExpansionNodeTypes.PYLON,
            ExpansionNodeTypes.CANNON,
            ExpansionNodeTypes.PATROL,
            ExpansionNodeTypes.MONITOR,
            ExpansionNodeTypes.RESPONSE
        );
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        NodeType type = node.type();

        // Build-phase structures: probe, nexus, pylon, cannon
        if (type.equals(ExpansionNodeTypes.PROBE) ||
            type.equals(ExpansionNodeTypes.NEXUS) ||
            type.equals(ExpansionNodeTypes.PYLON) ||
            type.equals(ExpansionNodeTypes.CANNON)) {
            world.build(node.id());
            world.complete(node.id());
            return new ProvisionResult.Success();
        }

        // Defense posture types: patrol, monitor, response
        if (type.equals(ExpansionNodeTypes.PATROL)) {
            world.setPatrolling(node.id());
            return new ProvisionResult.Success();
        }

        if (type.equals(ExpansionNodeTypes.MONITOR)) {
            world.setMonitoring(node.id());
            return new ProvisionResult.Success();
        }

        if (type.equals(ExpansionNodeTypes.RESPONSE)) {
            world.setResponding(node.id());
            return new ProvisionResult.Success();
        }

        return new ProvisionResult.Failed("Unknown node type: " + type.value());
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        world.destroy(node.id());
        return new DeprovisionResult.Success();
    }
}
