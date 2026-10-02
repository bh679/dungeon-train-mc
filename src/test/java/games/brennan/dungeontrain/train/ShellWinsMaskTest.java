package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.portal.PortalCorridorMask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ShellWinsMask#runs}: the carriage's own cells, and only those, are masked. */
final class ShellWinsMaskTest {

    @Test
    @DisplayName("an end wall with a doorway masks the wall and leaves the doorway and the room open")
    void endWallWithDoorway() {
        // A 25×5×5 Group interior with a wall across x = 0 and x = 24, open at z = 2 for y 0..2.
        Vec3i size = new Vec3i(25, 5, 5);
        ShellWinsMask.Filled wall = (x, y, z) -> (x == 0 || x == 24) && !(z == 2 && y <= 2);
        BlockPos origin = new BlockPos(100, 64, -3);
        PortalCorridorMask mask = new PortalCorridorMask(ShellWinsMask.runs(size, wall, origin));

        for (int x = 0; x < 25; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 5; z++) {
                    assertEquals(wall.at(x, y, z), mask.covers(100 + x, 64 + y, -3 + z),
                        "cell " + x + "," + y + "," + z);
                }
            }
        }
        assertFalse(mask.covers(99, 64, -3), "nothing outside the interior is masked");
    }

    @Test
    @DisplayName("touching cells along X share one box; an empty interior masks nothing")
    void runsAreCoalesced() {
        List<BoundingBox> row = ShellWinsMask.runs(new Vec3i(7, 1, 1), (x, y, z) -> x != 3, BlockPos.ZERO);
        assertEquals(2, row.size(), "x 0..2 and x 4..6");
        assertEquals(0, row.get(0).minX());
        assertEquals(2, row.get(0).maxX());
        assertEquals(4, row.get(1).minX());
        assertEquals(6, row.get(1).maxX());

        assertTrue(ShellWinsMask.runs(new Vec3i(7, 5, 5), (x, y, z) -> false, BlockPos.ZERO).isEmpty());
    }
}
