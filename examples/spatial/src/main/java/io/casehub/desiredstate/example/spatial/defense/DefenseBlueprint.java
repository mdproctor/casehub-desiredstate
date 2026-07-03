package io.casehub.desiredstate.example.spatial.defense;

import java.util.*;

public record DefenseBlueprint(
    int baseRow, int baseCol,
    List<int[]> scoutPositions,
    List<ZoneDefinition> zones
) {
    public record ZoneDefinition(String name, Map<String, Double> allocation, int totalForce) {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int baseRow, baseCol;
        private final List<int[]> scouts = new ArrayList<>();
        private final List<ZoneDefinition> zones = new ArrayList<>();

        public Builder basePosition(int row, int col) {
            this.baseRow = row; this.baseCol = col; return this;
        }

        public Builder scout(int row, int col) {
            scouts.add(new int[]{row, col}); return this;
        }

        public Builder zone(String name, Map<String, Double> allocation, int totalForce) {
            zones.add(new ZoneDefinition(name, allocation, totalForce)); return this;
        }

        public DefenseBlueprint build() {
            return new DefenseBlueprint(baseRow, baseCol,
                List.copyOf(scouts), List.copyOf(zones));
        }
    }
}
