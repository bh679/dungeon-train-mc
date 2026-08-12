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
    @DisplayName("The floor on every axis is whatever the corridor mouth needs, at any carriage width")
    void floors_coverTheCorridorMouth() {
        CarriageDims wide = CarriageDims.clamp(9, 21, 7);
        CarriageDims tall = CarriageDims.clamp(9, 7, 11);

        // Wide: the room must be wider than the corridor or the mouth's seal ring cannot close.
        assertTrue(PortalRoomLayout.minWidth(wide) > wide.width(),
            "room width " + PortalRoomLayout.minWidth(wide) + " must exceed corridor width " + wide.width());
        // Tall: the room must be at least as tall, or the corridor pokes through its ceiling.
        assertTrue(PortalRoomLayout.minHeight(tall) >= tall.height());
        assertTrue(PortalRoomLayout.minHeight(DEFAULT_DIMS) >= DEFAULT_DIMS.height());

        // The width floor is the seal's requirement and nothing more — two blocks of room either
        // side of the corridor, at every legal carriage width, odd or even. It is deliberately NOT
        // held up to the built-in room's 13; see minWidth's javadoc.
        assertEquals(9, PortalRoomLayout.minWidth(DEFAULT_DIMS));
        for (int carriageWidth = CarriageDims.MIN_WIDTH; carriageWidth <= CarriageDims.MAX_WIDTH; carriageWidth++) {
            CarriageDims dims = CarriageDims.clamp(9, carriageWidth, 7);
            assertEquals(carriageWidth + 2, PortalRoomLayout.minWidth(dims),
                "carriage width " + carriageWidth + " needs one wall of room either side");
        }
    }

    @Test
    @DisplayName("The height floor is the taller of MIN_HEIGHT and the corridor — 4 only bites below that")
    void minHeight_floorsAtMinHeightButNeverBelowTheCorridor() {
        // Ordinary and tall worlds: the corridor binds, because the room's ceiling may not sit
        // below the corridor poking into it.
        assertEquals(7, PortalRoomLayout.minHeight(DEFAULT_DIMS));
        for (int carriageHeight = CarriageDims.MIN_HEIGHT; carriageHeight <= CarriageDims.MAX_HEIGHT; carriageHeight++) {
            CarriageDims dims = CarriageDims.clamp(9, 7, carriageHeight);
            int floor = PortalRoomLayout.minHeight(dims);
            assertTrue(floor >= Math.min(PortalRoomLayout.MAX_HEIGHT, dims.height()),
                "carriage height " + carriageHeight + " must not poke through the room's ceiling");
            assertTrue(floor >= PortalRoomLayout.MIN_HEIGHT,
                "carriage height " + carriageHeight + " still needs a room with an interior");
            assertTrue(floor <= PortalRoomLayout.MAX_HEIGHT, "must stay inside the twin's Y lane");
        }

        // Short world: MIN_HEIGHT takes over. CarriageDims allows a 3-tall carriage, and a 3-tall
        // room is a floor and a ceiling with nothing between them.
        CarriageDims shortDims = CarriageDims.clamp(9, 7, CarriageDims.MIN_HEIGHT);
        assertEquals(PortalRoomLayout.MIN_HEIGHT, PortalRoomLayout.minHeight(shortDims));
    }

    @Test
    @DisplayName("Relaxing the height floor left the built-in room, and so the editor's plot slots, alone")
    void builtInSize_keepsItsOwnHeight() {
        // Same coupling as builtInSize_keepsItsOwnWidth: TrackKind.dims(PORTAL_ROOM) reports this
        // and TrackSidePlots stacks the editor's plots on it, so it must not follow minHeight down
        // — nor stamp a shell too short for the 5-block interior it is made of.
        CarriageDims shortDims = CarriageDims.clamp(9, 7, CarriageDims.MIN_HEIGHT);
        assertTrue(PortalRoomLayout.minHeight(shortDims) < 7, "precondition: the floor did drop");
        assertEquals(7, PortalRoomLayout.builtInSize(shortDims).getY());
        assertEquals(7, PortalRoomLayout.builtInSize(DEFAULT_DIMS).getY());

        // A world taller than the built-in shell still raises it, as the width side does.
        CarriageDims tall = CarriageDims.clamp(9, 7, 11);
        assertEquals(PortalRoomLayout.minHeight(tall), PortalRoomLayout.builtInSize(tall).getY());
    }

    @Test
    @DisplayName("Relaxing the width floor left the built-in room, and so the editor's plot slots, alone")
    void builtInSize_keepsItsOwnWidth() {
        // TrackKind.dims(PORTAL_ROOM) reports this, and TrackSidePlots.slotZ bases the editor's
        // plot slot on it — so it must not follow minWidth down.
        assertEquals(13, PortalRoomLayout.builtInSize(DEFAULT_DIMS).getZ());
        assertTrue(PortalRoomLayout.builtInSize(DEFAULT_DIMS).getZ() > PortalRoomLayout.minWidth(DEFAULT_DIMS));

        // A world wider than the built-in shell still raises it, or the seal ring cannot close.
        CarriageDims wide = CarriageDims.clamp(9, 21, 7);
        assertEquals(PortalRoomLayout.minWidth(wide), PortalRoomLayout.builtInSize(wide).getZ());
    }

    @Test
    @DisplayName("An authored room narrower than the built-in shell is legal — library_dimension is 13 × 7 × 12")
    void minSize_admitsARoomNarrowerThanTheBuiltInShell() {
        Vec3i floor = PortalRoomLayout.minSize(DEFAULT_DIMS);
        assertEquals(PortalRoomLayout.MIN_LENGTH, floor.getX());
        assertEquals(PortalRoomLayout.minHeight(DEFAULT_DIMS), floor.getY());
        assertEquals(PortalRoomLayout.minWidth(DEFAULT_DIMS), floor.getZ());

        // The rotated library template's own box. It clears the floor on every axis, and clampSize
        // leaves it alone — which together are what stop it being rejected on load and silently
        // replaced by the built-in room.
        Vec3i library = new Vec3i(13, 7, 12);
        assertTrue(library.getX() >= floor.getX() && library.getY() >= floor.getY()
            && library.getZ() >= floor.getZ(), "library_dimension must clear the floor");
        assertEquals(library, PortalRoomLayout.clampSize(DEFAULT_DIMS, library));
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
        Vec3i longer = PortalRoomLayout.sizeOfLength(DEFAULT_DIMS, 21);
        assertEquals(21, longer.getX());
        assertEquals(PortalRoomLayout.minHeight(DEFAULT_DIMS), longer.getY());
        assertEquals(PortalRoomLayout.minWidth(DEFAULT_DIMS), longer.getZ());
    }

    @Test
    @DisplayName("The room starts one corridor along +X and is centred on the corridor's doorway line")
    void roomOrigin_sitsPastTheCorridorAndOnTheWalkwayCentre() {
        BlockPos entry = new BlockPos(100, -60, 40);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DEFAULT_DIMS, PortalCorridorKind.LONG);
        int width = PortalRoomLayout.minWidth(DEFAULT_DIMS);
        BlockPos room = PortalRoomLayout.roomOrigin(entry, DEFAULT_DIMS, layout, width);

        assertEquals(entry.getX() + PortalCorridorSize.corridorLength(DEFAULT_DIMS, PortalCorridorKind.LONG), room.getX(),
            "room begins where the corridor ends — a corridor, which is longer than a carriage");
        assertEquals(entry.getY(), room.getY(), "room shares the corridor's floor");

        // The interior's centre column must land on the doorway line, or the openings meet a wall.
        assertEquals(entry.getZ() + layout.doorZ(), room.getZ() + 1 + (width - 2) / 2);
    }

    @Test
    @DisplayName("A wider room stays centred on the doorway line — it grows both ways, not one")
    void roomOrigin_recentresAsTheRoomWidens() {
        BlockPos entry = new BlockPos(0, 0, 0);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DEFAULT_DIMS, PortalCorridorKind.LONG);

        for (int width : new int[]{13, 17, 25}) {
            BlockPos room = PortalRoomLayout.roomOrigin(entry, DEFAULT_DIMS, layout, width);
            assertEquals(entry.getZ() + layout.doorZ(), room.getZ() + 1 + (width - 2) / 2,
                "width " + width + " must still centre on the doorway line");
        }
    }

    @Test
    @DisplayName("The corridor's whole cross-section fits inside the room's interior, at every carriage width")
    void roomInterior_swallowsTheCorridorCrossSection() {
        BlockPos entry = new BlockPos(0, 0, 0);

        // minWidth is the exact geometric bound now (dims.width() + 2), not a comfortable distance
        // from it — so this has to hold at every carriage width a portal can exist at, odd and even,
        // rather than at one sampled width with slack to spare. This sweep is what licenses the
        // `+ 2`. It starts at PortalCarriageLayout.MIN_WIDTH rather than CarriageDims.MIN_WIDTH (3):
        // a corridor narrower than 5 has no walkway beside its doorway and layoutFor throws.
        for (int carriageWidth = PortalCarriageLayout.MIN_WIDTH; carriageWidth <= CarriageDims.MAX_WIDTH; carriageWidth++) {
            CarriageDims dims = CarriageDims.clamp(9, carriageWidth, 7);
            PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, PortalCorridorKind.LONG);
            int width = PortalRoomLayout.minWidth(dims);
            BlockPos room = PortalRoomLayout.roomOrigin(entry, dims, layout, width);

            int interiorMinZ = room.getZ() + 1;
            int interiorMaxZ = room.getZ() + width - 2;
            assertTrue(interiorMinZ <= entry.getZ(),
                "carriage width " + carriageWidth + ": corridor's -Z edge (" + entry.getZ()
                    + ") must be inside the room (" + interiorMinZ + ")");
            assertTrue(interiorMaxZ >= entry.getZ() + dims.width() - 1,
                "carriage width " + carriageWidth + ": corridor's +Z edge ("
                    + (entry.getZ() + dims.width() - 1) + ") must be inside the room ("
                    + interiorMaxZ + ")");
        }
    }

    @Test
    @DisplayName("One block under minWidth and the corridor no longer fits — the floor has no slack left")
    void roomInterior_failsOneBlockUnderMinWidth() {
        BlockPos entry = new BlockPos(0, 0, 0);

        for (int carriageWidth = PortalCarriageLayout.MIN_WIDTH; carriageWidth <= CarriageDims.MAX_WIDTH; carriageWidth++) {
            CarriageDims dims = CarriageDims.clamp(9, carriageWidth, 7);
            PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, PortalCorridorKind.LONG);
            int tooNarrow = PortalRoomLayout.minWidth(dims) - 1;
            BlockPos room = PortalRoomLayout.roomOrigin(entry, dims, layout, tooNarrow);

            int interiorMinZ = room.getZ() + 1;
            int interiorMaxZ = room.getZ() + tooNarrow - 2;
            assertTrue(interiorMinZ > entry.getZ()
                    || interiorMaxZ < entry.getZ() + dims.width() - 1,
                "carriage width " + carriageWidth + ": width " + tooNarrow
                    + " should leave a corridor column outside the room — if this passes, minWidth"
                    + " is one block more conservative than it needs to be");
        }
    }
}
