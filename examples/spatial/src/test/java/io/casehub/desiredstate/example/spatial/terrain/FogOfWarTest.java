package io.casehub.desiredstate.example.spatial.terrain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FogOfWarTest {

    private TerrainGrid grid;

    @BeforeEach
    void setUp() {
        grid = TerrainGrid.create(10, 10, (r, c) -> 0, (r, c) -> TerrainType.OPEN);
    }

    @Test
    void initialState_nothingRevealed() {
        var fog = new FogOfWar(grid, 2);

        assertThat(fog.isRevealed(0, 0)).isFalse();
        assertThat(fog.isRevealed(5, 5)).isFalse();
        assertThat(fog.revealedCells()).isEmpty();
    }

    @Test
    void reveal_centerCell_visionRange2_revealsCorrectCells() {
        var fog = new FogOfWar(grid, 2);

        var newlyRevealed = fog.reveal(5, 5);

        // Manhattan distance <= 2 from (5,5): 13 cells
        // (5,5), (4,5),(6,5),(5,4),(5,6), (3,5),(7,5),(5,3),(5,7),
        // (4,4),(4,6),(6,4),(6,6)
        assertThat(newlyRevealed).hasSize(13);
        assertThat(fog.isRevealed(5, 5)).isTrue();
        assertThat(fog.isRevealed(5, 7)).isTrue();  // distance 2
        assertThat(fog.isRevealed(5, 8)).isFalse(); // distance 3
    }

    @Test
    void reveal_cornerCell_visionRange2_clipsToGridBounds() {
        var fog = new FogOfWar(grid, 2);

        var newlyRevealed = fog.reveal(0, 0);

        // (0,0), (0,1), (0,2), (1,0), (1,1), (2,0) = 6 cells
        assertThat(newlyRevealed).hasSize(6);
        assertThat(fog.isRevealed(0, 0)).isTrue();
        assertThat(fog.isRevealed(2, 0)).isTrue();
        assertThat(fog.isRevealed(3, 0)).isFalse();
    }

    @Test
    void reveal_overlappingVision_returnsOnlyNewCells() {
        var fog = new FogOfWar(grid, 2);

        var first = fog.reveal(5, 5);
        var second = fog.reveal(5, 6);

        // Second reveal should only return cells not already revealed
        assertThat(second).allSatisfy(cell ->
            assertThat(first).doesNotContain(cell));
    }

    @Test
    void reveal_visionRange1_revealsImmediateNeighborsOnly() {
        var fog = new FogOfWar(grid, 1);

        var revealed = fog.reveal(5, 5);

        // (5,5), (4,5), (6,5), (5,4), (5,6) = 5 cells
        assertThat(revealed).hasSize(5);
    }

    @Test
    void revealedCells_accumulatesAcrossMultipleReveals() {
        var fog = new FogOfWar(grid, 1);

        fog.reveal(0, 0);
        fog.reveal(9, 9);

        var all = fog.revealedCells();
        assertThat(all).contains(grid.cellAt(0, 0), grid.cellAt(9, 9));
    }
}
