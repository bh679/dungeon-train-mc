package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.editor.EditorStrayBlocks.PlotBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure half of {@link EditorStrayBlocks} — plot containment, the chunk-column
 * pre-filter, and the per-player toggle. The sweep itself needs a live {@code ServerLevel} and is
 * covered by the in-game Gate 2 flow.
 */
final class EditorStrayBlocksTest {

    /** A 10×5×4 plot at (100, 230, 50) — carriage-shaped, at the real plot floor. */
    private static PlotBox plot() {
        return new PlotBox(new BlockPos(100, 230, 50), new Vec3i(10, 5, 4));
    }

    @Test
    @DisplayName("contains: inclusive at the origin corner, exclusive at the far corner")
    void contains_bounds() {
        PlotBox box = plot();
        assertTrue(box.contains(100, 230, 50), "origin cell is inside");
        assertTrue(box.contains(109, 234, 53), "last cell on every axis is inside");
        assertFalse(box.contains(110, 230, 50), "one past the X extent is outside");
        assertFalse(box.contains(100, 235, 50), "one past the Y extent is outside");
        assertFalse(box.contains(100, 230, 54), "one past the Z extent is outside");
        assertFalse(box.contains(99, 230, 50), "one before the origin is outside — that is the cage line");
    }

    @Test
    @DisplayName("outsideAll: a cell inside any one plot is not a stray")
    void outsideAll_acrossPlots() {
        List<PlotBox> plots = List.of(
            plot(),
            new PlotBox(new BlockPos(200, 230, 50), new Vec3i(10, 5, 4)));

        assertFalse(EditorStrayBlocks.outsideAll(105, 231, 51, plots), "inside the first plot");
        assertFalse(EditorStrayBlocks.outsideAll(205, 231, 51, plots), "inside the second plot");
        // The gap between two plots — where a block placed against the outside of a cage lands.
        assertTrue(EditorStrayBlocks.outsideAll(112, 231, 51, plots), "in the gap between plots");
        assertTrue(EditorStrayBlocks.outsideAll(105, 236, 51, plots), "above the cage top");
    }

    @Test
    @DisplayName("outsideAll: everything is a stray when no plots are stamped")
    void outsideAll_noPlots() {
        assertTrue(EditorStrayBlocks.outsideAll(105, 231, 51, List.of()));
    }

    @Test
    @DisplayName("overlapsColumn: only the chunk columns the footprint actually reaches")
    void overlapsColumn() {
        // X 100..109 spans chunks 6 (96..111); Z 50..53 spans chunk 3 (48..63).
        PlotBox box = plot();
        assertTrue(box.overlapsColumn(6, 3));
        assertFalse(box.overlapsColumn(5, 3), "column entirely -X of the footprint");
        assertFalse(box.overlapsColumn(7, 3), "column entirely +X of the footprint");
        assertFalse(box.overlapsColumn(6, 2), "column entirely -Z of the footprint");
        assertFalse(box.overlapsColumn(6, 4), "column entirely +Z of the footprint");
    }

    @Test
    @DisplayName("overlapsColumn: a footprint straddling a chunk boundary reaches both columns")
    void overlapsColumn_straddling() {
        PlotBox box = new PlotBox(new BlockPos(110, 230, 60), new Vec3i(10, 5, 10));
        assertTrue(box.overlapsColumn(6, 3), "X 110..111 and Z 60..63 are in chunk (6, 3)");
        assertTrue(box.overlapsColumn(7, 4), "X 112..119 and Z 64..69 are in chunk (7, 4)");
    }

    @Test
    @DisplayName("toggle: on by default, remembered per player")
    void toggle() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(EditorStrayBlocks.isEnabled(a), "ghosts default on");

        EditorStrayBlocks.setEnabled(a, false);
        assertFalse(EditorStrayBlocks.isEnabled(a));
        assertTrue(EditorStrayBlocks.isEnabled(b), "one player's toggle does not move another's");

        EditorStrayBlocks.setEnabled(a, true);
        assertTrue(EditorStrayBlocks.isEnabled(a));
    }
}
