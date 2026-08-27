package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which cell a seal plane copies when it is closed against a <b>room copy</b> rather than the base
 * room.
 *
 * <p>The seal plane at a corridor mouth is the wall plane of the copy standing beside it. Closed from
 * the base room — which is what every path did — that copy's mouth wore another copy's wall: visibly
 * wrong under {@link PortalRoomCopies.Kind#DYNAMIC}, where each copy rolls its own, and in any room
 * whose two ends differ. Anchored on the copy instead, it closes with the copy's own blocks.</p>
 *
 * <p>Pure integer geometry, so it runs without a level — the same reason
 * {@code PortalCarriageBuilder.sealFillSource} was split out in the first place.</p>
 */
class PortalRoomSealSourceTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;
    private static final BlockPos ORIGIN = new BlockPos(200, -60, -30);

    private static PortalStructure structure() {
        return PortalStructure.withMode(ORIGIN, "default", PortalRoomLayout.builtInSize(DIMS),
            PortalRoomMode.ENDLESS_REPETITION, PortalRoomTiling.base());
    }

    @Test
    @DisplayName("Anchored on the tile ahead, the exit mouth mirrors that tile's far wall")
    void exitMouthMirrorsTheCopysOwnFarWall() {
        PortalStructure s = structure();
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.LONG);
        BlockPos room = s.roomOrigin(DIMS, layout);
        Vec3i size = s.roomSize();
        BlockPos ahead = s.tileOrigin(DIMS, layout, new PortalRoomTiling.Tile(1, 0));

        int y = room.getY() + 2;
        int z = room.getZ() + 3;
        BlockPos source = PortalCarriageBuilder.sealFillSource(ahead, room, size,
            PortalCarriageRole.EXIT, y, z);

        // The plane it closes is that tile's -X wall, so the wall it carries on is the tile's own
        // +X wall — the same mirror-across-the-room rule PortalRoomTiler.closeFace uses.
        assertEquals(ahead.getX() + size.getX() - 1, source.getX(), "mirror column");
        assertEquals(y, source.getY(), "same row");
        assertEquals(z, source.getZ(), "same column across the room");
    }

    @Test
    @DisplayName("Anchored on the tile behind, the entry mouth mirrors that tile's near wall")
    void entryMouthMirrorsTheCopysOwnNearWall() {
        PortalStructure s = structure();
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.LONG);
        BlockPos room = s.roomOrigin(DIMS, layout);
        Vec3i size = s.roomSize();
        BlockPos behind = s.tileOrigin(DIMS, layout, new PortalRoomTiling.Tile(-1, 0));

        int y = room.getY() + 1;
        int z = room.getZ() + 1;
        BlockPos source = PortalCarriageBuilder.sealFillSource(behind, room, size,
            PortalCarriageRole.ENTRY, y, z);

        assertEquals(behind.getX(), source.getX(), "mirror column");
        assertEquals(y, source.getY(), "same row");
        assertEquals(z, source.getZ(), "same column across the room");
    }

    @Test
    @DisplayName("Anchored on the base room, the source is unchanged from what it always was")
    void baseAnchorIsUnchanged() {
        PortalStructure s = structure();
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.LONG);
        BlockPos room = s.roomOrigin(DIMS, layout);
        Vec3i size = s.roomSize();

        // The path that has no standing copy to read — a pair whose exit has moved seals against a
        // tile the tiling has not reached yet — must still answer exactly as before.
        BlockPos entry = PortalCarriageBuilder.sealFillSource(room, room, size,
            PortalCarriageRole.ENTRY, room.getY(), room.getZ());
        BlockPos exit = PortalCarriageBuilder.sealFillSource(room, room, size,
            PortalCarriageRole.EXIT, room.getY(), room.getZ());
        assertEquals(room.getX(), entry.getX());
        assertEquals(room.getX() + size.getX() - 1, exit.getX());
    }
}
