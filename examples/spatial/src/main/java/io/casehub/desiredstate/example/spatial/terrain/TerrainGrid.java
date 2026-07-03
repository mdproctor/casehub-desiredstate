package io.casehub.desiredstate.example.spatial.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

public class TerrainGrid {
    private final TerrainCell[][] cells;
    private final int rows;
    private final int cols;

    private TerrainGrid(TerrainCell[][] cells, int rows, int cols) {
        this.cells = cells;
        this.rows = rows;
        this.cols = cols;
    }

    public static TerrainGrid create(int rows, int cols,
                                     BiFunction<Integer, Integer, Integer> heightFn,
                                     BiFunction<Integer, Integer, TerrainType> typeFn) {
        var cells = new TerrainCell[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                cells[r][c] = new TerrainCell(r, c, heightFn.apply(r, c), typeFn.apply(r, c));
            }
        }
        return new TerrainGrid(cells, rows, cols);
    }

    public TerrainCell cellAt(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols)
            throw new IndexOutOfBoundsException(
                "(%d,%d) out of bounds for %dx%d grid".formatted(row, col, rows, cols));
        return cells[row][col];
    }

    public List<TerrainCell> adjacentCells(int row, int col) {
        var result = new ArrayList<TerrainCell>(4);
        if (row > 0) result.add(cells[row - 1][col]);
        if (row < rows - 1) result.add(cells[row + 1][col]);
        if (col > 0) result.add(cells[row][col - 1]);
        if (col < cols - 1) result.add(cells[row][col + 1]);
        return List.copyOf(result);
    }

    public int rows() { return rows; }
    public int cols() { return cols; }
}
