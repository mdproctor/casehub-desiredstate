package io.casehub.desiredstate.example.spatial.attack;

import java.util.*;

public record AttackBlueprint(
    int originRow, int originCol,
    List<int[]> scoutPositions,
    List<WaypointDef> waypoints,
    int totalForce
) {
    public record WaypointDef(int row, int col, int strength) {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int originRow, originCol;
        private final List<int[]> scouts = new ArrayList<>();
        private final List<WaypointDef> waypoints = new ArrayList<>();
        private int totalForce;

        public Builder origin(int row, int col) {
            this.originRow = row; this.originCol = col; return this;
        }

        public Builder scout(int row, int col) {
            scouts.add(new int[]{row, col}); return this;
        }

        public Builder waypoint(int row, int col, int strength) {
            waypoints.add(new WaypointDef(row, col, strength)); return this;
        }

        public Builder totalForce(int force) {
            this.totalForce = force; return this;
        }

        public AttackBlueprint build() {
            return new AttackBlueprint(originRow, originCol,
                List.copyOf(scouts), List.copyOf(waypoints), totalForce);
        }
    }
}
