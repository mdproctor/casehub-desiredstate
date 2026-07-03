package io.casehub.desiredstate.example.spatial.terrain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TerrainGridTest {

    @Test
    void constructGrid_10x10_allCellsAccessible() {
        var grid = TerrainGrid.create(10, 10, (r, c) -> 0, (r, c) -> TerrainType.OPEN);

        assertThat(grid.rows()).isEqualTo(10);
        assertThat(grid.cols()).isEqualTo(10);
        assertThat(grid.cellAt(0, 0)).isNotNull();
        assertThat(grid.cellAt(9, 9)).isNotNull();
    }

    @Test
    void constructGrid_withHeightVariation() {
        var grid = TerrainGrid.create(3, 3, (r, c) -> r, (r, c) -> TerrainType.OPEN);

        assertThat(grid.cellAt(0, 0).height()).isEqualTo(0);
        assertThat(grid.cellAt(1, 0).height()).isEqualTo(1);
        assertThat(grid.cellAt(2, 0).height()).isEqualTo(2);
    }

    @Test
    void cellAt_outOfBounds_throws() {
        var grid = TerrainGrid.create(10, 10, (r, c) -> 0, (r, c) -> TerrainType.OPEN);

        assertThatThrownBy(() -> grid.cellAt(-1, 0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> grid.cellAt(10, 0)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void adjacentCells_centerCell_returnsFourNeighbors() {
        var grid = TerrainGrid.create(10, 10, (r, c) -> 0, (r, c) -> TerrainType.OPEN);

        var adj = grid.adjacentCells(5, 5);
        assertThat(adj).hasSize(4);
        assertThat(adj).extracting(TerrainCell::row, TerrainCell::col)
            .containsExactlyInAnyOrder(
                tuple(4, 5), tuple(6, 5), tuple(5, 4), tuple(5, 6));
    }

    @Test
    void adjacentCells_cornerCell_returnsTwoNeighbors() {
        var grid = TerrainGrid.create(10, 10, (r, c) -> 0, (r, c) -> TerrainType.OPEN);

        var adj = grid.adjacentCells(0, 0);
        assertThat(adj).hasSize(2);
        assertThat(adj).extracting(TerrainCell::row, TerrainCell::col)
            .containsExactlyInAnyOrder(tuple(1, 0), tuple(0, 1));
    }

    @Test
    void adjacentCells_edgeCell_returnsThreeNeighbors() {
        var grid = TerrainGrid.create(10, 10, (r, c) -> 0, (r, c) -> TerrainType.OPEN);

        var adj = grid.adjacentCells(0, 5);
        assertThat(adj).hasSize(3);
    }

    @Test
    void terrainTypes_mixedGrid() {
        var grid = TerrainGrid.create(3, 3,
            (r, c) -> (r == 1 && c == 1) ? 2 : 0,
            (r, c) -> (r == 1 && c == 1) ? TerrainType.CLIFF : TerrainType.OPEN);

        assertThat(grid.cellAt(1, 1).terrainType()).isEqualTo(TerrainType.CLIFF);
        assertThat(grid.cellAt(0, 0).terrainType()).isEqualTo(TerrainType.OPEN);
    }
}
