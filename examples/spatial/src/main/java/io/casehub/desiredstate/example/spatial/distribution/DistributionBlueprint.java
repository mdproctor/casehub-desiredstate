package io.casehub.desiredstate.example.spatial.distribution;

import java.util.*;

public record DistributionBlueprint(
    List<FrontierCellDef> frontierCells,
    int totalForce,
    String zoneName
) {
    public record FrontierCellDef(int row, int col, double ratio) {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final List<FrontierCellDef> cells = new ArrayList<>();
        private int totalForce;
        private String zoneName = "frontier";

        public Builder frontierCell(int row, int col, double ratio) {
            cells.add(new FrontierCellDef(row, col, ratio)); return this;
        }

        public Builder totalForce(int force) {
            this.totalForce = force; return this;
        }

        public Builder zoneName(String name) {
            this.zoneName = name; return this;
        }

        public DistributionBlueprint build() {
            return new DistributionBlueprint(List.copyOf(cells), totalForce, zoneName);
        }
    }
}
