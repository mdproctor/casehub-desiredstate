package io.casehub.desiredstate.example.spatial.world;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.*;
import java.time.Duration;
import java.util.Set;

public class BattlefieldProvisioner implements NodeProvisioner {
    private final BattlefieldWorld world;

    public BattlefieldProvisioner(BattlefieldWorld world) {
        this.world = world;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(SpatialNodeTypes.CELL, SpatialNodeTypes.UNIT,
                      SpatialNodeTypes.SCOUT, SpatialNodeTypes.ZONE);
    }

    @Override
    public Duration resyncInterval() { return Duration.ofMinutes(1); }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        var spec = node.spec();
        if (spec instanceof CellSpec cellSpec) {
            world.revealCell(cellSpec.row(), cellSpec.col());
            return new ProvisionResult.Success();
        }
        if (spec instanceof UnitSpec unitSpec) {
            var cellSpec = (CellSpec) context.graph().nodes()
                .get(unitSpec.cellId()).spec();
            world.placeUnit(node.id(), cellSpec.row(), cellSpec.col(),
                           unitSpec.strength());
            return new ProvisionResult.Success();
        }
        if (spec instanceof ScoutSpec scoutSpec) {
            var cellSpec = (CellSpec) context.graph().nodes()
                .get(scoutSpec.cellId()).spec();
            world.placeScout(node.id(), cellSpec.row(), cellSpec.col(),
                           scoutSpec.visionRange());
            return new ProvisionResult.Success();
        }
        if (spec instanceof ZoneSpec) {
            world.activateZone(node.id());
            return new ProvisionResult.Success();
        }
        return new ProvisionResult.Failed("Unknown spec type: " + spec.getClass());
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        var spec = node.spec();

        // Handle typed nodes
        if (spec instanceof CellSpec) {
            return new DeprovisionResult.Success();
        }
        if (spec instanceof UnitSpec) {
            world.removeUnit(node.id());
            return new DeprovisionResult.Success();
        }
        if (spec instanceof ScoutSpec) {
            world.removeScout(node.id());
            return new DeprovisionResult.Success();
        }
        if (spec instanceof ZoneSpec) {
            world.deactivateZone(node.id());
            return new DeprovisionResult.Success();
        }

        // Handle orphaned nodes (unknown spec) by inferring type from NodeId
        var idValue = node.id().value();
        if (idValue.startsWith("unit-")) {
            world.removeUnit(node.id());
            return new DeprovisionResult.Success();
        }
        if (idValue.startsWith("scout-")) {
            world.removeScout(node.id());
            return new DeprovisionResult.Success();
        }
        if (idValue.startsWith("zone-")) {
            world.deactivateZone(node.id());
            return new DeprovisionResult.Success();
        }
        if (idValue.startsWith("cell-")) {
            return new DeprovisionResult.Success();
        }

        return new DeprovisionResult.Failed("Unknown spec type and cannot infer from ID: " + spec.getClass() + ", " + idValue);
    }
}
