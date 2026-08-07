package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exit twin's position is derived here and nowhere else. Everything that reads it — where the
 * corridor is stamped, how far the erase reaches, the occupancy box, and the origin the EXIT role's
 * frames map into — has to agree, so these tests pin the arithmetic rather than any one caller.
 */
class PortalStructureTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;   // length 9
    private static final BlockPos ORIGIN = new BlockPos(200, -60, -30);

    private static PortalStructure withRoomLength(int length) {
        return new PortalStructure(ORIGIN, "default",
            PortalRoomLayout.sizeOfLength(DIMS, length));
    }

    @Test
    @DisplayName("Built-in room: the offset is the old dims.length() + POCKET_LENGTH figure")
    void builtInRoom_reproducesTheOldConstant() {
        PortalStructure s = new PortalStructure(ORIGIN, "default", PortalRoomLayout.builtInSize(DIMS));
        assertEquals(9 + 11, s.exitTwinOffsetX(DIMS));
        assertEquals(ORIGIN.offset(20, 0, 0), s.exitOrigin(DIMS));
        assertEquals(20 + 9, s.spanX(DIMS));
    }

    @Test
    @DisplayName("A longer room pushes the exit twin further along — no overlap, no gap")
    void longerRoom_movesTheExitTwin() {
        PortalStructure shortRoom = withRoomLength(7);
        PortalStructure longRoom = withRoomLength(21);

        assertEquals(9 + 7, shortRoom.exitTwinOffsetX(DIMS));
        assertEquals(9 + 21, longRoom.exitTwinOffsetX(DIMS));

        // The room must exactly fill the gap between the two corridors: entry corridor ends at
        // origin.x + length - 1, exit corridor starts at exitOrigin.x, and the room occupies
        // everything strictly between.
        for (PortalStructure s : new PortalStructure[]{shortRoom, longRoom}) {
            int entryEnd = s.origin().getX() + DIMS.length() - 1;
            int exitStart = s.exitOrigin(DIMS).getX();
            assertEquals(s.roomLength(), exitStart - entryEnd - 1,
                "room of length " + s.roomLength() + " must exactly bridge the two corridors");
        }
    }

    @Test
    @DisplayName("The room begins where the entry corridor ends, whatever its length")
    void roomOrigin_abutsTheEntryCorridor() {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS);
        for (int length : new int[]{PortalRoomLayout.MIN_LENGTH, 11, 21, PortalRoomLayout.MAX_LENGTH}) {
            PortalStructure s = withRoomLength(length);
            assertEquals(ORIGIN.getX() + DIMS.length(), s.roomOrigin(DIMS, layout).getX());
        }
    }

    @Test
    @DisplayName("Relocating keeps the room identity — only the position moves")
    void movedTo_keepsTheRoom() {
        PortalStructure s = withRoomLength(15);
        BlockPos elsewhere = ORIGIN.offset(64, 0, 0);
        PortalStructure moved = s.movedTo(elsewhere);

        assertEquals(elsewhere, moved.origin());
        assertEquals(s.roomName(), moved.roomName());
        assertEquals(s.roomSize(), moved.roomSize());
        assertEquals(s.spanX(DIMS), moved.spanX(DIMS));
        assertNotEquals(s.exitOrigin(DIMS), moved.exitOrigin(DIMS));
    }

    // ---- tiling ----

    @Test
    @DisplayName("Tiling never moves the exit twin — the EXIT frame stays under a player's feet")
    void tilingDoesNotMoveTheExitTwin() {
        PortalStructure plain = withRoomLength(15);
        PortalStructure tiled = plain.withTiling(PortalRoomTiling.base()
            .with(new PortalRoomTiling.Tile(0, 4))
            .with(new PortalRoomTiling.Tile(-3, 2)));

        // The whole reason tiling stays off the corridor row: these four have to agree, and they all
        // read spanX / exitOrigin.
        assertEquals(plain.spanX(DIMS), tiled.spanX(DIMS));
        assertEquals(plain.exitOrigin(DIMS), tiled.exitOrigin(DIMS));
        assertEquals(plain.exitTwinOffsetX(DIMS), tiled.exitTwinOffsetX(DIMS));
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS);
        assertEquals(plain.roomOrigin(DIMS, layout), tiled.roomOrigin(DIMS, layout));
    }

    @Test
    @DisplayName("A base-only structure reports the base room's own bounds — the boxes are unchanged")
    void baseOnlyTilingKeepsTheOldBounds() {
        PortalStructure s = withRoomLength(15);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS);
        BlockPos room = s.roomOrigin(DIMS, layout);

        assertEquals(room.getX(), s.tiledMinX(DIMS, layout));
        assertEquals(room.getX() + s.roomLength() - 1, s.tiledMaxX(DIMS, layout));
        assertEquals(room.getZ(), s.tiledMinZ(DIMS, layout));
        assertEquals(room.getZ() + s.roomWidth() - 1, s.tiledMaxZ(DIMS, layout));
    }

    @Test
    @DisplayName("Tiled bounds span every standing copy, at the room's own stride")
    void tiledBoundsSpanTheCopies() {
        PortalStructure s = withRoomLength(15).withTiling(PortalRoomTiling.base()
            .with(new PortalRoomTiling.Tile(2, 1))
            .with(new PortalRoomTiling.Tile(-1, -3)));
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS);
        BlockPos room = s.roomOrigin(DIMS, layout);

        assertEquals(room.getX() - s.roomLength(), s.tiledMinX(DIMS, layout));
        assertEquals(room.getX() + 3 * s.roomLength() - 1, s.tiledMaxX(DIMS, layout));
        assertEquals(room.getZ() - 3 * s.roomWidth(), s.tiledMinZ(DIMS, layout));
        assertEquals(room.getZ() + 2 * s.roomWidth() - 1, s.tiledMaxZ(DIMS, layout));
    }

    @Test
    @DisplayName("tileOrigin and tileAt are inverses — a copy's corner resolves back to its own tile")
    void tileOriginAndTileAtAgree() {
        PortalStructure s = withRoomLength(15);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS);
        for (PortalRoomTiling.Tile tile : new PortalRoomTiling.Tile[]{
            PortalRoomTiling.Tile.BASE, new PortalRoomTiling.Tile(3, 2),
            new PortalRoomTiling.Tile(-2, -4)}) {
            BlockPos corner = s.tileOrigin(DIMS, layout, tile);
            assertEquals(tile, s.tileAt(DIMS, layout, corner.getX(), corner.getZ()));
            // And anywhere inside it, including the far corner — floorDiv, not truncation, so this
            // holds on the negative side too.
            assertEquals(tile, s.tileAt(DIMS, layout,
                corner.getX() + s.roomLength() - 1, corner.getZ() + s.roomWidth() - 1));
        }
    }

    @Test
    @DisplayName("Relocating drops the standing copies — they belong to the place it left")
    void movedToDropsTheTiling() {
        PortalStructure s = withRoomLength(15)
            .withTiling(PortalRoomTiling.base().with(new PortalRoomTiling.Tile(0, 2)));
        assertEquals(2, s.tiling().size());

        PortalStructure moved = s.movedTo(ORIGIN.offset(64, 0, 0));
        assertTrue(moved.tiling().isBaseOnly());
        assertEquals(s.mode(), moved.mode());
    }

    @Test
    @DisplayName("An unset mode is the sealed default, and it survives a re-tiling")
    void modeDefaultsAndSurvives() {
        PortalStructure s = withRoomLength(15);
        assertEquals(PortalRoomMode.DEFAULT, s.mode());

        PortalStructure open = new PortalStructure(ORIGIN, "default", s.roomSize(),
            PortalRoomMode.ENDLESS_OPEN, PortalRoomTiling.base());
        assertEquals(PortalRoomMode.ENDLESS_OPEN,
            open.withTiling(PortalRoomTiling.base().with(new PortalRoomTiling.Tile(0, 1))).mode());
        // A null mode or tiling is normalised rather than stored — nothing downstream null-checks.
        assertEquals(PortalRoomMode.DEFAULT,
            new PortalStructure(ORIGIN, "default", s.roomSize(), null, null).mode());
        assertTrue(new PortalStructure(ORIGIN, "default", s.roomSize(), null, null)
            .tiling().isBaseOnly());
    }

    @Test
    @DisplayName("Rejects null components — a half-built structure would stamp into nowhere")
    void rejectsNull() {
        Vec3i size = PortalRoomLayout.builtInSize(DIMS);
        assertThrows(NullPointerException.class, () -> new PortalStructure(null, "default", size));
        assertThrows(NullPointerException.class, () -> new PortalStructure(ORIGIN, null, size));
        assertThrows(NullPointerException.class, () -> new PortalStructure(ORIGIN, "default", null));
    }
}
