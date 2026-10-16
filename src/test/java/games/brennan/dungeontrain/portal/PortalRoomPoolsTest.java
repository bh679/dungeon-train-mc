package games.brennan.dungeontrain.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which of a dimensional carriage room's authored pools the template-container pass rolls. A cell it
 * wrongly skips keeps the loot baked into the room's {@code .nbt} — the same chest on every reseed and
 * in every copy — and a cell it wrongly takes overrides the author's variant pick.
 */
class PortalRoomPoolsTest {

    private static final Vec3i SIZE = new Vec3i(9, 7, 7);

    @Test
    @DisplayName("A template chest with a pool and no variant cell is rolled")
    void rollsPlainTemplateChest() {
        BlockPos chest = new BlockPos(3, 1, 4);
        assertEquals(List.of(chest), PortalRoomPools.cellsToRoll(Set.of(chest), Set.of(), SIZE, p -> false));
    }

    @Test
    @DisplayName("A pool on a variant cell is left to the variant pass")
    void skipsVariantCells() {
        BlockPos cell = new BlockPos(3, 1, 4);
        assertTrue(PortalRoomPools.cellsToRoll(Set.of(cell), Set.of(cell), SIZE, p -> false).isEmpty());
    }

    @Test
    @DisplayName("Pools outside the room, or under the corridor mask, are not rolled")
    void skipsOutsideAndMasked() {
        BlockPos outside = new BlockPos(9, 1, 1);
        BlockPos negative = new BlockPos(-1, 1, 1);
        BlockPos masked = new BlockPos(0, 1, 3);
        BlockPos kept = new BlockPos(4, 1, 3);
        List<BlockPos> rolled = PortalRoomPools.cellsToRoll(Set.of(outside, negative, masked, kept), Set.of(),
            SIZE, masked::equals);
        assertEquals(List.of(kept), rolled);
    }
}
