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
    @DisplayName("A corridor wider or taller than the built-in room grows the room, never the reverse")
    void size_growsToCoverTheCorridorMouth() {
        CarriageDims wide = CarriageDims.clamp(9, 21, 7);
        CarriageDims tall = CarriageDims.clamp(9, 7, 19);

        // Wide: the room must be wider than the corridor or the mouth's seal ring cannot close.
        assertTrue(PortalRoomLayout.width(wide) > wide.width(),
            "room width " + PortalRoomLayout.width(wide) + " must exceed corridor width " + wide.width());
        // Tall: the room must be at least as tall, or the corridor pokes through its ceiling.
        assertTrue(PortalRoomLayout.height(tall) >= tall.height());

        // Never shrinks below the built-in figures.
        assertTrue(PortalRoomLayout.width(DEFAULT_DIMS) >= 13);
        assertTrue(PortalRoomLayout.height(DEFAULT_DIMS) >= 7);
    }

    @Test
    @DisplayName("Length is free within bounds; anything outside is clamped rather than rejected")
    void length_isClampedNotRejected() {
        assertEquals(21, PortalRoomLayout.clampLength(21));
        assertEquals(PortalRoomLayout.MIN_LENGTH, PortalRoomLayout.clampLength(0));
        assertEquals(PortalRoomLayout.MIN_LENGTH, PortalRoomLayout.clampLength(-4));
        assertEquals(PortalRoomLayout.MAX_LENGTH, PortalRoomLayout.clampLength(9999));
        assertEquals(21, PortalRoomLayout.sizeOfLength(DEFAULT_DIMS, 21).getX());
        // Height and width do not move with length.
        assertEquals(PortalRoomLayout.builtInSize(DEFAULT_DIMS).getY(),
            PortalRoomLayout.sizeOfLength(DEFAULT_DIMS, 21).getY());
        assertEquals(PortalRoomLayout.builtInSize(DEFAULT_DIMS).getZ(),
            PortalRoomLayout.sizeOfLength(DEFAULT_DIMS, 21).getZ());
    }

    @Test
    @DisplayName("The room starts one corridor along +X and is centred on the corridor's doorway line")
    void roomOrigin_sitsPastTheCorridorAndOnTheWalkwayCentre() {
        BlockPos entry = new BlockPos(100, -60, 40);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DEFAULT_DIMS);
        BlockPos room = PortalRoomLayout.roomOrigin(entry, DEFAULT_DIMS, layout);

        assertEquals(entry.getX() + DEFAULT_DIMS.length(), room.getX(), "room begins where the corridor ends");
        assertEquals(entry.getY(), room.getY(), "room shares the corridor's floor");

        // The interior's centre column must land on the doorway line, or the openings meet a wall.
        int interiorWidth = PortalRoomLayout.width(DEFAULT_DIMS) - 2;
        int interiorMinZ = room.getZ() + 1;
        assertEquals(entry.getZ() + layout.doorZ(), interiorMinZ + interiorWidth / 2);
    }

    @Test
    @DisplayName("The corridor's whole cross-section fits inside the room's interior")
    void roomInterior_swallowsTheCorridorCrossSection() {
        BlockPos entry = new BlockPos(0, 0, 0);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DEFAULT_DIMS);
        BlockPos room = PortalRoomLayout.roomOrigin(entry, DEFAULT_DIMS, layout);

        int interiorMinZ = room.getZ() + 1;
        int interiorMaxZ = room.getZ() + PortalRoomLayout.width(DEFAULT_DIMS) - 2;
        assertTrue(interiorMinZ <= entry.getZ(),
            "corridor's -Z edge (" + entry.getZ() + ") must be inside the room (" + interiorMinZ + ")");
        assertTrue(interiorMaxZ >= entry.getZ() + DEFAULT_DIMS.width() - 1,
            "corridor's +Z edge must be inside the room");
        assertTrue(PortalRoomLayout.height(DEFAULT_DIMS) >= DEFAULT_DIMS.height());
    }
}
