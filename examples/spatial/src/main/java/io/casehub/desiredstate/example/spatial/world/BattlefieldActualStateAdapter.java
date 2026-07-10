package io.casehub.desiredstate.example.spatial.world;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class BattlefieldActualStateAdapter implements ActualStateAdapter {
    private final BattlefieldWorld world;

    public BattlefieldActualStateAdapter(BattlefieldWorld world) {
        this.world = world;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(SpatialNodeTypes.CELL, SpatialNodeTypes.UNIT,
                      SpatialNodeTypes.SCOUT, SpatialNodeTypes.ZONE);
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        var statuses = new HashMap<NodeId, NodeStatus>();

        // Report status of all desired nodes
        for (var entry : desired.nodes().entrySet()) {
            var nodeId = entry.getKey();
            var node = entry.getValue();
            statuses.put(nodeId, statusOf(node));
        }

        // Report all orphaned nodes (exist in world but not in desired graph)
        // Units
        for (var unitId : world.placedUnits().keySet()) {
            if (!desired.nodes().containsKey(unitId)) {
                statuses.put(unitId, NodeStatus.PRESENT);
            }
        }

        // Scouts
        var placedScouts = world.placedScouts();
        for (var scoutId : placedScouts.keySet()) {
            if (!desired.nodes().containsKey(scoutId)) {
                statuses.put(scoutId, NodeStatus.PRESENT);
            }
        }

        // Zones
        var activeZones = world.activeZones();
        for (var zoneId : activeZones) {
            if (!desired.nodes().containsKey(zoneId)) {
                statuses.put(zoneId, NodeStatus.PRESENT);
            }
        }

        return new ActualState(statuses);
    }

    private NodeStatus statusOf(DesiredNode node) {
        var spec = node.spec();
        if (spec instanceof CellSpec cellSpec) {
            return world.isRevealed(cellSpec.row(), cellSpec.col())
                ? NodeStatus.PRESENT : NodeStatus.ABSENT;
        }
        if (spec instanceof UnitSpec unitSpec) {
            if (!world.isUnitPlaced(node.id())) return NodeStatus.ABSENT;
            return world.unitStrength(node.id()) == unitSpec.strength()
                ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
        }
        if (spec instanceof ScoutSpec) {
            return world.isScoutPlaced(node.id())
                ? NodeStatus.PRESENT : NodeStatus.ABSENT;
        }
        if (spec instanceof ZoneSpec zoneSpec) {
            if (!world.isZoneActive(node.id())) return NodeStatus.ABSENT;
            return isZoneConsistent(node.id(), zoneSpec)
                ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
        }
        return NodeStatus.UNKNOWN;
    }

    private boolean isZoneConsistent(NodeId zoneId, ZoneSpec zoneSpec) {
        for (var entry : zoneSpec.allocation().entrySet()) {
            var cellId = entry.getKey();
            var expectedStrength = zoneSpec.strengthFor(cellId);
            var unitId = unitIdForCell(cellId);
            if (unitId == null) return false;
            if (!world.isUnitPlaced(unitId)) return false;
            if (world.unitStrength(unitId) != expectedStrength) return false;
        }
        return true;
    }

    private NodeId unitIdForCell(NodeId cellId) {
        return NodeId.of("unit-" + cellId.value());
    }
}
