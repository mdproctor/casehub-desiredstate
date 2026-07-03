package io.casehub.desiredstate.example.spatial.terrain;

import java.util.Objects;

public record TerrainCell(int row, int col, int height, TerrainType terrainType) {
    public TerrainCell {
        if (height < 0 || height > 2)
            throw new IllegalArgumentException("Height must be 0, 1, or 2, got: " + height);
        Objects.requireNonNull(terrainType, "terrainType must not be null");
    }
}
