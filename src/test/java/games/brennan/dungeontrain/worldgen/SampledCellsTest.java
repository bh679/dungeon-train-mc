package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The record of which cells the End-band sampler wrote, read back by the void erosion. */
class SampledCellsTest {

    @Test
    @DisplayName("a marked cell is contained; its neighbours are not")
    void markAndContains() {
        SampledCells cells = SampledCells.forChunk(-64, 384);
        cells.mark(3, 70, 9);
        assertTrue(cells.contains(3, 70, 9));
        assertFalse(cells.contains(4, 70, 9));
        assertFalse(cells.contains(3, 71, 9));
        assertFalse(cells.contains(3, 70, 8));
        assertEquals(1, cells.count());
        assertFalse(cells.isEmpty());
    }

    @Test
    @DisplayName("the chunk's Y span is honoured end to end, and out-of-range marks are ignored")
    void bounds() {
        SampledCells cells = SampledCells.forChunk(-64, 384);
        cells.mark(0, -64, 0);
        cells.mark(15, 319, 15);
        cells.mark(0, -65, 0);
        cells.mark(0, 320, 0);
        cells.mark(16, 0, 0);
        cells.mark(0, 0, -1);
        assertTrue(cells.contains(0, -64, 0));
        assertTrue(cells.contains(15, 319, 15));
        assertFalse(cells.contains(0, -65, 0));
        assertFalse(cells.contains(0, 320, 0));
        assertEquals(2, cells.count());
    }

    @Test
    @DisplayName("NONE exempts nothing; ALL exempts everything")
    void sentinels() {
        assertTrue(SampledCells.NONE.isEmpty());
        assertFalse(SampledCells.NONE.contains(0, 64, 0));
        assertFalse(SampledCells.NONE.isAll());

        assertTrue(SampledCells.ALL.isAll());
        assertFalse(SampledCells.ALL.isEmpty());
        assertTrue(SampledCells.ALL.contains(0, 64, 0));
        assertTrue(SampledCells.ALL.contains(15, -64, 15));
        assertEquals(-1, SampledCells.ALL.count());
    }

    @Test
    @DisplayName("cells in different columns of the same Y never alias")
    void noAliasing() {
        SampledCells cells = SampledCells.forChunk(0, 16);
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                if ((dx + dz) % 2 == 0) cells.mark(dx, 5, dz);
            }
        }
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                assertEquals((dx + dz) % 2 == 0, cells.contains(dx, 5, dz), dx + "," + dz);
            }
        }
        assertEquals(128, cells.count());
    }
}
