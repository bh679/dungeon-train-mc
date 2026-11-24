package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frame plots of different sizes in one row: no overlaps, and all ending on the same Z. */
class ChunkFramePlotLayoutTest {

    @Test
    @DisplayName("Mixed one-chunk and 3x3 frame plots stand a gap apart along X and end on one Z line")
    void mixedSizes() {
        List<Vec3i> sizes = List.of(new Vec3i(18, 34, 18), new Vec3i(50, 34, 50), new Vec3i(50, 34, 50),
            new Vec3i(18, 34, 18));
        List<Integer> before = new ArrayList<>();
        int lastMaxX = Integer.MIN_VALUE;
        Integer endZ = null;
        for (Vec3i size : sizes) {
            BlockPos o = ChunkFrameEditor.slotOrigin(before, size);
            // A cage block either side and a gap between neighbours.
            assertTrue(o.getX() - 1 > lastMaxX + 1, "plot at " + o + " clears the one before");
            lastMaxX = o.getX() + size.getX() - 1;
            int maxZ = o.getZ() + size.getZ() - 1;
            if (endZ == null) endZ = maxZ;
            assertEquals(endZ.intValue(), maxZ, "every plot ends on the same Z");
            before.add(size.getX());
        }
        assertEquals(ChunkFrameEditor.FIRST_PLOT_X, ChunkFrameEditor.slotOrigin(List.of(), new Vec3i(50, 34, 50)).getX());
    }
}
