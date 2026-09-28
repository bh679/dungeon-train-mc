package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure geometry of {@link EditorLayerSweep}: plot boxes, unions and box subtraction. */
final class EditorLayerSweepTest {

    @Test
    @DisplayName("plotBox wraps the footprint in its 1-block cage")
    void plotBoxIncludesCage() {
        BoundingBox box = EditorLayerSweep.plotBox(new BlockPos(10, 230, 20), new Vec3i(4, 3, 2));
        assertEquals(new BoundingBox(9, 229, 19, 14, 233, 22), box);
    }

    @Test
    @DisplayName("subtract leaves the box whole when the cut misses it, nothing when it swallows it")
    void subtractTrivialCases() {
        BoundingBox from = new BoundingBox(0, 0, 0, 15, 15, 15);
        assertEquals(List.of(from), EditorLayerSweep.subtract(from, null));
        assertEquals(List.of(from), EditorLayerSweep.subtract(from, new BoundingBox(20, 0, 0, 30, 15, 15)));
        assertTrue(EditorLayerSweep.subtract(from, new BoundingBox(-1, -1, -1, 16, 16, 16)).isEmpty());
    }

    @Test
    @DisplayName("subtract covers every cell outside the cut exactly once")
    void subtractPartitionsTheRemainder() {
        BoundingBox from = new BoundingBox(0, 0, 0, 15, 15, 15);
        BoundingBox cut = new BoundingBox(4, -3, 6, 9, 5, 40);   // pokes out of the box on Y and Z
        List<BoundingBox> parts = EditorLayerSweep.subtract(from, cut);
        assertTrue(parts.size() <= 6, "at most six pieces");
        for (int x = 0; x <= 15; x++) {
            for (int y = 0; y <= 15; y++) {
                for (int z = 0; z <= 15; z++) {
                    int covering = 0;
                    for (BoundingBox p : parts) {
                        if (p.isInside(x, y, z)) covering++;
                    }
                    boolean inCut = cut.isInside(x, y, z);
                    assertEquals(inCut ? 0 : 1, covering, "cell " + x + "," + y + "," + z);
                }
            }
        }
        for (BoundingBox p : parts) {
            assertFalse(p.intersects(cut), "piece overlaps the cut: " + p);
        }
    }

    @Test
    @DisplayName("unionOf is the smallest box around all of them, and null for none")
    void unionOf() {
        assertEquals(null, EditorLayerSweep.unionOf(List.of()));
        BoundingBox a = new BoundingBox(0, 0, 0, 1, 1, 1);
        BoundingBox b = new BoundingBox(5, -2, 3, 6, 0, 9);
        assertEquals(new BoundingBox(0, -2, 0, 6, 1, 9), EditorLayerSweep.unionOf(List.of(a, b)));
        assertEquals(new BoundingBox(0, 0, 0, 1, 1, 1), a);   // the input is not mutated
    }
}
