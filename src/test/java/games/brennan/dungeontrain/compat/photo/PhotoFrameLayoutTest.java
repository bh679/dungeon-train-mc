package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.compat.PaintingBlockLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PhotoFrameLayout} — the cells of a block photo frame, and that they survive
 * {@link PaintingBlockLayout#relayout} (the template-transform path they share with Fast Paintings).
 */
final class PhotoFrameLayoutTest {

    private static final BlockPos ANCHOR = new BlockPos(10, 64, -4);
    private static final Object DATA = "frame";

    @Test
    @DisplayName("sizes 0..2 are 1×1, 2×2, 3×3; out-of-range sizes clamp")
    void widths() {
        assertEquals(1, PhotoFrameLayout.width(0));
        assertEquals(2, PhotoFrameLayout.width(1));
        assertEquals(3, PhotoFrameLayout.width(2));
        assertEquals(3, PhotoFrameLayout.width(7));
        assertEquals(1, PhotoFrameLayout.width(-1));
    }

    @Test
    @DisplayName("every cell points back at the master; master first, no duplicates")
    void cellsResolveToMaster() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (int size = 0; size <= PhotoFrameLayout.MAX_SIZE; size++) {
                BlockPos master = PhotoFrameLayout.masterForAnchor(ANCHOR, size);
                List<PhotoFrameLayout.Cell> cells = PhotoFrameLayout.cells(master, facing, size);
                int w = PhotoFrameLayout.width(size);
                assertEquals(w * w, cells.size());
                assertEquals(new PhotoFrameLayout.Cell(master, 0, 0), cells.get(0));
                Set<BlockPos> seen = new HashSet<>();
                for (PhotoFrameLayout.Cell c : cells) {
                    assertTrue(seen.add(c.pos()), "duplicate cell " + c);
                    assertEquals(master, PhotoFrameLayout.masterOf(c.pos(), facing, c.xOffset(), c.yOffset()));
                }
            }
        }
    }

    @Test
    @DisplayName("the clicked anchor is the bottom cell at the clockwise end, like Exposure's entity")
    void anchorIsBottomClockwiseCell() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (int size = 0; size <= PhotoFrameLayout.MAX_SIZE; size++) {
                BlockPos master = PhotoFrameLayout.masterForAnchor(ANCHOR, size);
                int w = PhotoFrameLayout.width(size);
                assertEquals(ANCHOR, PhotoFrameLayout.cellAt(master, facing, 0, w - 1));
                // The rest of the frame is above and counter-clockwise of the anchor.
                for (PhotoFrameLayout.Cell c : PhotoFrameLayout.cells(master, facing, size)) {
                    assertTrue(c.pos().getY() >= ANCHOR.getY());
                    BlockPos d = c.pos().subtract(ANCHOR);
                    Direction ccw = facing.getCounterClockWise();
                    int along = d.getX() * ccw.getStepX() + d.getZ() * ccw.getStepZ();
                    assertTrue(along >= 0 && along < w, "cell " + c + " off the run for " + facing);
                }
            }
        }
    }

    @Test
    @DisplayName("the wall is behind the picture")
    void wallBehind() {
        assertEquals(ANCHOR.south(), PhotoFrameLayout.wallBehind(ANCHOR, Direction.NORTH));
        assertEquals(ANCHOR.west(), PhotoFrameLayout.wallBehind(ANCHOR, Direction.EAST));
    }

    @Test
    @DisplayName("a 3×3 frame re-lays consistently under the Z mirror and a 90° rotation")
    void largeFrameSurvivesRelayout() {
        assertRelaysConsistently(Mirror.LEFT_RIGHT, Rotation.NONE, p -> new BlockPos(p.getX(), p.getY(), -p.getZ()));
        assertRelaysConsistently(Mirror.NONE, Rotation.CLOCKWISE_90, p -> new BlockPos(-p.getZ(), p.getY(), p.getX()));
        assertRelaysConsistently(Mirror.FRONT_BACK, Rotation.NONE, p -> new BlockPos(-p.getX(), p.getY(), p.getZ()));
    }

    /** Move a 3×3 frame's cells as vanilla would, re-lay them, and check every cell finds the one master. */
    private static void assertRelaysConsistently(Mirror mirror, Rotation rotation, UnaryOperator<BlockPos> move) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockPos master = PhotoFrameLayout.masterForAnchor(ANCHOR, 2);
            List<PaintingBlockLayout.Cell> moved = PhotoFrameLayout.cells(master, facing, 2).stream()
                .map(c -> new PaintingBlockLayout.Cell(move.apply(c.pos()), facing, c.xOffset(), c.yOffset(),
                    c.xOffset() == 0 && c.yOffset() == 0 ? DATA : null))
                .toList();

            List<PaintingBlockLayout.Cell> out = PaintingBlockLayout.relayout(moved, mirror, rotation, false);

            Direction expected = rotation.rotate(mirror.mirror(facing));
            PaintingBlockLayout.Cell newMaster = null;
            for (PaintingBlockLayout.Cell c : out) {
                assertEquals(expected, c.facing());
                if (c.master()) {
                    assertNull(newMaster, "two masters");
                    newMaster = c;
                }
            }
            assertNotNull(newMaster);
            for (PaintingBlockLayout.Cell c : out) {
                assertEquals(newMaster.pos(), PhotoFrameLayout.masterOf(c.pos(), c.facing(), c.xOffset(), c.yOffset()),
                    "cell " + c + " after " + mirror + "/" + rotation + " from " + facing);
            }
        }
    }
}
