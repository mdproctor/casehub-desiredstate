package io.casehub.desiredstate.example.spatial.world;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.example.spatial.terrain.FogOfWar;
import io.casehub.desiredstate.example.spatial.terrain.TerrainGrid;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class BattlefieldWorld {
    private final TerrainGrid grid;
    private final FogOfWar fog;
    private final Map<NodeId, UnitPlacement> units = new HashMap<>();
    private final Map<NodeId, ScoutPlacement> scouts = new HashMap<>();
    private final Map<NodeId, Boolean> zones = new HashMap<>();

    public BattlefieldWorld(TerrainGrid grid, int visionRange) {
        this.grid = Objects.requireNonNull(grid);
        this.fog = new FogOfWar(grid, visionRange);
    }

    public TerrainGrid grid() { return grid; }
    public FogOfWar fog() { return fog; }

    public void revealCell(int row, int col) { fog.reveal(row, col); }
    public boolean isRevealed(int row, int col) { return fog.isRevealed(row, col); }

    public void placeUnit(NodeId id, int row, int col, int strength) {
        units.put(id, new UnitPlacement(row, col, strength));
    }

    public void removeUnit(NodeId id) { units.remove(id); }

    public boolean isUnitPlaced(NodeId id) { return units.containsKey(id); }

    public int unitStrength(NodeId id) {
        var placement = units.get(id);
        if (placement == null) throw new IllegalArgumentException("Unit not placed: " + id);
        return placement.strength();
    }

    public void updateUnitStrength(NodeId id, int newStrength) {
        var placement = units.get(id);
        if (placement == null) throw new IllegalArgumentException("Unit not placed: " + id);
        units.put(id, new UnitPlacement(placement.row(), placement.col(), newStrength));
    }

    public void placeScout(NodeId id, int row, int col, int visionRange) {
        scouts.put(id, new ScoutPlacement(row, col));
        fog.reveal(row, col, visionRange);
    }

    public void removeScout(NodeId id) { scouts.remove(id); }
    public boolean isScoutPlaced(NodeId id) { return scouts.containsKey(id); }

    public void activateZone(NodeId id) { zones.put(id, true); }
    public void deactivateZone(NodeId id) { zones.remove(id); }
    public boolean isZoneActive(NodeId id) { return zones.containsKey(id); }

    public Map<NodeId, UnitPlacement> placedUnits() { return Map.copyOf(units); }
    public Map<NodeId, ScoutPlacement> placedScouts() { return Map.copyOf(scouts); }
    public Set<NodeId> activeZones() { return Set.copyOf(zones.keySet()); }

    public record UnitPlacement(int row, int col, int strength) {}
    public record ScoutPlacement(int row, int col) {}
}
