package io.casehub.desiredstate.example.spatial.terrain;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class FogOfWar {
    private final TerrainGrid grid;
    private final int visionRange;
    private final Set<Long> revealed = new HashSet<>();

    public FogOfWar(TerrainGrid grid, int visionRange) {
        this.grid = Objects.requireNonNull(grid);
        if (visionRange < 1)
            throw new IllegalArgumentException("visionRange must be >= 1, got: " + visionRange);
        this.visionRange = visionRange;
    }

    public Set<TerrainCell> reveal(int row, int col) {
        return reveal(row, col, visionRange);
    }

    public Set<TerrainCell> reveal(int row, int col, int range) {
        var newlyRevealed = new HashSet<TerrainCell>();
        for (int r = row - range; r <= row + range; r++) {
            for (int c = col - range; c <= col + range; c++) {
                if (r < 0 || r >= grid.rows() || c < 0 || c >= grid.cols()) continue;
                int distance = Math.abs(r - row) + Math.abs(c - col);
                if (distance > range) continue;
                long key = encodeKey(r, c);
                if (revealed.add(key)) {
                    newlyRevealed.add(grid.cellAt(r, c));
                }
            }
        }
        return Set.copyOf(newlyRevealed);
    }

    public boolean isRevealed(int row, int col) {
        return revealed.contains(encodeKey(row, col));
    }

    public Set<TerrainCell> revealedCells() {
        return revealed.stream()
            .map(key -> grid.cellAt(decodeRow(key), decodeCol(key)))
            .collect(Collectors.toUnmodifiableSet());
    }

    public int visionRange() { return visionRange; }

    private long encodeKey(int row, int col) {
        return (long) row * grid.cols() + col;
    }

    private int decodeRow(long key) { return (int) (key / grid.cols()); }
    private int decodeCol(long key) { return (int) (key % grid.cols()); }
}
