package io.casehub.desiredstate.example.spatial.render;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.example.spatial.terrain.TerrainType;
import io.casehub.desiredstate.example.spatial.world.BattlefieldWorld;

public class GridRenderer {
    private final BattlefieldWorld world;
    private final long stepDelayMs;
    private boolean firstFrame = true;

    public GridRenderer(BattlefieldWorld world, long stepDelayMs) {
        this.world = world;
        this.stepDelayMs = stepDelayMs;
    }

    public GridRenderer(BattlefieldWorld world) {
        this(world, 500);
    }

    public String render(String annotation) {
        var grid = world.grid();
        var sb = new StringBuilder();
        sb.append(annotation).append('\n');
        sb.append("   ");
        for (int c = 0; c < grid.cols(); c++) sb.append("%2d ".formatted(c));
        sb.append('\n');

        var units = world.placedUnits();

        for (int r = 0; r < grid.rows(); r++) {
            sb.append("%2d ".formatted(r));
            for (int c = 0; c < grid.cols(); c++) {
                var cell = grid.cellAt(r, c);
                if (!world.isRevealed(r, c)) {
                    sb.append("░░ ");
                } else {
                    var unitOnCell = findUnitAt(r, c);
                    if (unitOnCell != null) {
                        var strength = world.unitStrength(unitOnCell);
                        sb.append("%2d ".formatted(strength));
                    } else if (cell.terrainType() == TerrainType.CLIFF) {
                        sb.append("## ");
                    } else if (cell.height() > 0) {
                        sb.append("▲%d ".formatted(cell.height()));
                    } else {
                        sb.append(" . ");
                    }
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public void printFrame(String annotation) {
        if (!firstFrame) {
            System.out.print("\033[H\033[2J");
        }
        firstFrame = false;
        System.out.print(render(annotation));
        System.out.flush();
        if (stepDelayMs > 0) {
            try { Thread.sleep(stepDelayMs); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private NodeId findUnitAt(int row, int col) {
        for (var entry : world.placedUnits().entrySet()) {
            var placement = entry.getValue();
            if (placement.row() == row && placement.col() == col) {
                return entry.getKey();
            }
        }
        return null;
    }
}
