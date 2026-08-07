package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalRoomLayoutTest {

    private static final CarriageDims DEFAULT_DIMS = CarriageDims.DEFAULT;   // 9 × 7 × 7

    @Test
    @DisplayName("At the default dims the room is exactly what it was hardcoded at: 11 × 7 × 13")
    void builtInSize_matchesTheOldConstants() {
        assertEquals(new Vec3i(11, 7, 13), PortalRoomLayout.builtInSize(DEFAULT_DIMS));
    }

    @Test
    @DisplayName("A corridor wider or taller than the built-in room raises the floor, never lowers it")
    void floors_coverTheCorridorMouth() {
        CarriageDims wide = CarriageDims.clamp(9, 21, 7);
        CarriageDims tall = CarriageDims.clamp(9, 7, 11);

        // Wide: the room must be wider than the corridor or the mouth's seal ring cannot close.
        assertTrue(PortalRoomLayout.minWidth(wide) > wide.width(),
            "room width " + PortalRoomLayout.minWidth(wide) + " must exceed corridor width " + wide.width());
        // Tall: the room must be at least as tall, or the corridor pokes through its ceiling.
        assertTrue(PortalRoomLayout.minHeight(tall) >= tall.height());

        // Never drops below the built-in figures.
        assertTrue(PortalRoomLayout.minWidth(DEFAULT_DIMS) >= 13);
        assertTrue(PortalRoomLayout.minHeight(DEFAULT_DIMS) >= 7);
    }

    @Test
    @DisplayName("Height can never reach the next portal pair's Y lane")
    void maxHeight_staysClearOfTheLaneAbove() {
        // eraseTwin sweeps one row past the structure's top, so a room of exactly TWIN_LANE_HEIGHT
        // would erase the floor of the lane above it.
        assertTrue(PortalRoomLayout.MAX_HEIGHT < PortalRoomLayout.TWIN_LANE_HEIGHT,
            "MAX_HEIGHT " + PortalRoomLayout.MAX_HEIGHT
                + " must stay under TWIN_LANE_HEIGHT " + PortalRoomLayout.TWIN_LANE_HEIGHT);
        // Even a world whose carriages are taller than a lane cannot produce an illegal floor.
        CarriageDims veryTall = CarriageDims.clamp(9, 7, CarriageDims.MAX_HEIGHT);
        assertTrue(PortalRoomLayout.minHeight(veryTall) <= PortalRoomLayout.MAX_HEIGHT);
    }

    @Test
    @DisplayName("Every axis is clamped rather than rejected, each into its own band")
    void clampSize_holdsEachAxisInBand() {
        Vec3i huge = PortalRoomLayout.clampSize(DEFAULT_DIMS, new Vec3i(9999, 9999, 9999));
        assertEquals(PortalRoomLayout.MAX_LENGTH, huge.getX());
        assertEquals(PortalRoomLayout.MAX_HEIGHT, huge.getY());
        assertEquals(PortalRoomLayout.MAX_WIDTH, huge.getZ());

        Vec3i tiny = PortalRoomLayout.clampSize(DEFAULT_DIMS, new Vec3i(-4, 0, 1));
        assertEquals(PortalRoomLayout.MIN_LENGTH, tiny.getX());
        assertEquals(PortalRoomLayout.minHeight(DEFAULT_DIMS), tiny.getY());
        assertEquals(PortalRoomLayout.minWidth(DEFAULT_DIMS), tiny.getZ());

        // A legal size passes through untouched.
        Vec3i ok = new Vec3i(21, 9, 17);
        assertEquals(ok, PortalRoomLayout.clampSize(DEFAULT_DIMS, ok));
    }

    @Test
    @DisplayName("sizeOfLength moves only the length; height and width stay at their floors")
    void sizeOfLength_movesOnlyTheLength() {
        Vec3i built = PortalRoomLayout.builtInSize(DEFAULT_DIMS);
        Vec3i longer = PortalRoomLayout.sizeOfLength(DEFAULT_DIMS, 21);
        assertEquals(21, longer.getX());
        assertEquals(built.getY(), longer.getY());
        assertEquals(built.getZ(), longer.getZ());
    }

    @Test
    @DisplayName("The room starts one corridor along +X and is centred on the corridor's doorway line")
    void roomOrigin_sitsPastTheCorridorAndOnTheWalkwayCentre() {
        BlockPos entry = new BlockPos(100, -60, 40);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DEFAULT_DIMS);
        int width = PortalRoomLayout.minWidth(DEFAULT_DIMS);
        BlockPos room = PortalRoomLayout.roomOrigin(entry, DEFAULT_DIMS, layout, width);

        assertEquals(entry.getX() + PortalCorridorSize.corridorLength(DEFAULT_DIMS), room.getX(),
            "room begins where the corridor ends — a corridor, which is longer than a carriage");
        assertEquals(entry.getY(), room.getY(), "room shares the corridor's floor");

        // The interior's centre column must land on the doorway line, or the openings meet a wall.
        assertEquals(entry.getZ() + layout.doorZ(), room.getZ() + 1 + (width - 2) / 2);
    }

    @Test
    @DisplayName("A wider room stays centred on the doorway line — it grows both ways, not one")
    void roomOrigin_recentresAsTheRoomWidens() {
        BlockPos entry = new BlockPos(0, 0, 0);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DEFAULT_DIMS);

        for (int width : new int[]{13, 17, 25}) {
            BlockPos room = PortalRoomLayout.roomOrigin(entry, DEFAULT_DIMS, layout, width);
            assertEquals(entry.getZ() + layout.doorZ(), room.getZ() + 1 + (width - 2) / 2,
                "width " + width + " must still centre on the doorway line");
        }
    }

    @Test
    @DisplayName("The corridor's whole cross-section fits inside the room's interior")
    void roomInterior_swallowsTheCorridorCrossSection() {
        BlockPos entry = new BlockPos(0, 0, 0);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DEFAULT_DIMS);
        int width = PortalRoomLayout.minWidth(DEFAULT_DIMS);
        BlockPos room = PortalRoomLayout.roomOrigin(entry, DEFAULT_DIMS, layout, width);

        int interiorMinZ = room.getZ() + 1;
        int interiorMaxZ = room.getZ() + width - 2;
        assertTrue(interiorMinZ <= entry.getZ(),
            "corridor's -Z edge (" + entry.getZ() + ") must be inside the room (" + interiorMinZ + ")");
        assertTrue(interiorMaxZ >= entry.getZ() + DEFAULT_DIMS.width() - 1,
            "corridor's +Z edge must be inside the room");
        assertTrue(PortalRoomLayout.minHeight(DEFAULT_DIMS) >= DEFAULT_DIMS.height());
    }
}
