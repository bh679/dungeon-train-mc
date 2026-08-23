package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the index maths behind {@code /dt editor offset|rotate|flip}.
 *
 * <p>The property that matters most is that every transform is a <b>bijection
 * over the plot box</b>: {@code EditorPlotTransformer} writes one destination
 * per source and nothing else, so a mapping that collided would silently drop
 * part of the author's build, and one that escaped the box would write outside
 * the plot. {@link #bijections} asserts it for all three shapes at once.</p>
 *
 * <p>Pure maths only — no world, no block states, so no NeoForge bootstrap.</p>
 */
final class EditorPlotTransformTest {

    /** Deliberately not a cube, and not square in X/Z, so axis mix-ups show up. */
    private static final Vec3i BOX = new Vec3i(13, 9, 7);

    // ─── wrap ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("wrap: negatives and over-runs fold back into the box")
    void wrap() {
        assertEquals(0, EditorPlotTransform.wrap(0, 7));
        assertEquals(6, EditorPlotTransform.wrap(6, 7));
        assertEquals(0, EditorPlotTransform.wrap(7, 7));
        assertEquals(1, EditorPlotTransform.wrap(8, 7));
        assertEquals(6, EditorPlotTransform.wrap(-1, 7), "floorMod, not the % remainder");
        assertEquals(0, EditorPlotTransform.wrap(-7, 7));
        assertEquals(5, EditorPlotTransform.wrap(-30, 7));
    }

    // ─── offset ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Offset slides by the delta and wraps at the far face")
    void offsetSlidesAndWraps() {
        EditorPlotTransform t = EditorPlotTransform.offset(1, 0, 0);
        assertEquals(new BlockPos(1, 4, 3), t.destination(0, 4, 3, BOX));
        assertEquals(new BlockPos(0, 4, 3), t.destination(12, 4, 3, BOX),
            "the far column re-enters at the near face");
    }

    @Test
    @DisplayName("Offset by a whole box length is the identity mapping")
    void offsetByBoxLength() {
        EditorPlotTransform t = EditorPlotTransform.offset(13, -9, 14);
        assertEquals(new BlockPos(5, 2, 6), t.destination(5, 2, 6, BOX));
    }

    @Test
    @DisplayName("A zero offset is rejected as an identity, a non-zero one is not")
    void offsetIdentity() {
        assertTrue(EditorPlotTransform.offset(0, 0, 0).isIdentity());
        assertTrue(!EditorPlotTransform.offset(0, -1, 0).isIdentity());
    }

    // ─── rotate ────────────────────────────────────────────────────────

    @Test
    @DisplayName("A quarter turn is rejected on a non-square plot, 180 is not")
    void quarterTurnNeedsSquare() {
        assertNotNull(EditorPlotTransform.rotation(90).rejection(BOX));
        assertNotNull(EditorPlotTransform.rotation(270).rejection(BOX));
        assertNull(EditorPlotTransform.rotation(180).rejection(BOX));
        assertNull(EditorPlotTransform.rotation(90).rejection(new Vec3i(7, 9, 7)));
    }

    @Test
    @DisplayName("Rotate 90 is clockwise seen from above: the NW corner lands NE")
    void rotate90IsClockwise() {
        Vec3i square = new Vec3i(7, 9, 7);
        EditorPlotTransform t = EditorPlotTransform.rotation(90);
        // +X is east and +Z is south, so (0,0) is the north-west corner and the
        // clockwise cycle is NW -> NE -> SE -> SW -> NW.
        assertEquals(new BlockPos(6, 0, 0), t.destination(0, 0, 0, square), "NW -> NE");
        assertEquals(new BlockPos(6, 0, 6), t.destination(6, 0, 0, square), "NE -> SE");
        assertEquals(new BlockPos(0, 0, 6), t.destination(6, 0, 6, square), "SE -> SW");
        assertEquals(new BlockPos(0, 0, 0), t.destination(0, 0, 6, square), "SW -> NW");
    }

    @Test
    @DisplayName("Rotate 270 undoes rotate 90")
    void quarterTurnsAreInverses() {
        Vec3i square = new Vec3i(7, 9, 7);
        EditorPlotTransform cw = EditorPlotTransform.rotation(90);
        EditorPlotTransform ccw = EditorPlotTransform.rotation(270);
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                BlockPos there = cw.destination(x, 3, z, square);
                BlockPos back = ccw.destination(there.getX(), there.getY(), there.getZ(), square);
                assertEquals(new BlockPos(x, 3, z), back);
            }
        }
    }

    @Test
    @DisplayName("Rotate 180 leaves height alone and flips both horizontal axes")
    void rotate180() {
        EditorPlotTransform t = EditorPlotTransform.rotation(180);
        assertEquals(new BlockPos(12, 4, 6), t.destination(0, 4, 0, BOX));
        assertEquals(new BlockPos(0, 4, 0), t.destination(12, 4, 6, BOX));
    }

    @Test
    @DisplayName("A full turn is an identity and is rejected as one")
    void fullTurnIsIdentity() {
        assertTrue(EditorPlotTransform.rotation(360).isIdentity());
    }

    // ─── flip ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Flip reflects its own axis and leaves the other two alone")
    void flipReflectsOneAxis() {
        assertEquals(new BlockPos(11, 4, 3),
            EditorPlotTransform.flip(Direction.Axis.X).destination(1, 4, 3, BOX));
        assertEquals(new BlockPos(1, 4, 3),
            EditorPlotTransform.flip(Direction.Axis.Y).destination(1, 4, 3, BOX));
        assertEquals(new BlockPos(1, 0, 3),
            EditorPlotTransform.flip(Direction.Axis.Y).destination(1, 8, 3, BOX));
        assertEquals(new BlockPos(1, 4, 3),
            EditorPlotTransform.flip(Direction.Axis.Z).destination(1, 4, 3, BOX));
        assertEquals(new BlockPos(1, 4, 6),
            EditorPlotTransform.flip(Direction.Axis.Z).destination(1, 4, 0, BOX));
    }

    @Test
    @DisplayName("A flip applied twice is the identity")
    void flipIsItsOwnInverse() {
        for (Direction.Axis axis : Direction.Axis.values()) {
            EditorPlotTransform t = EditorPlotTransform.flip(axis);
            BlockPos there = t.destination(2, 7, 1, BOX);
            assertEquals(new BlockPos(2, 7, 1),
                t.destination(there.getX(), there.getY(), there.getZ(), BOX), axis.getName());
        }
    }

    // ─── the shared invariant ──────────────────────────────────────────

    @Test
    @DisplayName("Every transform is a bijection of the box onto itself")
    void bijections() {
        Vec3i square = new Vec3i(7, 9, 7);
        assertBijection(EditorPlotTransform.offset(3, -2, 5), BOX);
        assertBijection(EditorPlotTransform.offset(-40, 0, 22), BOX);
        assertBijection(EditorPlotTransform.rotation(180), BOX);
        assertBijection(EditorPlotTransform.rotation(90), square);
        assertBijection(EditorPlotTransform.rotation(270), square);
        for (Direction.Axis axis : Direction.Axis.values()) {
            assertBijection(EditorPlotTransform.flip(axis), BOX);
        }
    }

    /** No two cells share a destination, and no destination escapes the box. */
    private static void assertBijection(EditorPlotTransform t, Vec3i size) {
        Set<BlockPos> seen = new HashSet<>();
        for (int x = 0; x < size.getX(); x++) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    BlockPos to = t.destination(x, y, z, size);
                    assertTrue(to.getX() >= 0 && to.getX() < size.getX()
                            && to.getY() >= 0 && to.getY() < size.getY()
                            && to.getZ() >= 0 && to.getZ() < size.getZ(),
                        t.label() + ": " + to + " escaped " + size);
                    assertTrue(seen.add(to), t.label() + ": two cells both map to " + to);
                }
            }
        }
        assertEquals(size.getX() * size.getY() * size.getZ(), seen.size(),
            t.label() + ": every cell should be covered");
    }
}
