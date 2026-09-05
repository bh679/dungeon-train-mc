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
        // eraseTwin sweeps one row past the structure's top, so a lane of exactly the room's height
        // would erase the floor of the lane above it. The spacing is a function of the room now, so
        // the guarantee is per height rather than against one constant.
        for (int h = PortalRoomLayout.MIN_HEIGHT; h <= PortalRoomLayout.MAX_HEIGHT; h++) {
            assertTrue(PortalTwinLanes.laneHeight(h) > h,
                "a room of " + h + " must not reach the lane above it");
        }
        // Even a world whose carriages are taller than a lane cannot produce an illegal floor.
        CarriageDims veryTall = CarriageDims.clamp(9, 7, CarriageDims.MAX_HEIGHT);
        assertTrue(PortalRoomLayout.minHeight(veryTall) <= PortalRoomLayout.MAX_HEIGHT);
    }

    @Test
    @DisplayName("The authoring ceiling is what a stock world can very nearly stand up")
    void maxHeight_isTheAuthoringCeiling() {
        // A room built to the ceiling has to stand up in every stock basement: 96 blocks, less the
        // floor margin and the row under bedrock, is 93, and 90 fits under that.
        assertEquals(90, PortalRoomLayout.MAX_HEIGHT);
        assertTrue(PortalRoomLayout.MAX_HEIGHT <= PortalTwinLanes.maxStructureHeight(-96, 0));
        assertTrue(PortalRoomLayout.MAX_HEIGHT <= PortalTwinLanes.maxStructureHeight(-64, 32));
        // What a stock DT preset (basement 80, floor -48, bedrock 32) actually holds. A room asked
        // for taller than this is stamped at this instead — see PortalCarriageBuilder.
        assertEquals(77, PortalTwinLanes.maxStructureHeight(-48, 32));
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
    @DisplayName("A room at exactly minWidth has no slack — every offset clamps back to zero")
    void maxDoorOffset_isZeroAtMinWidth() {
        for (int carriageWidth = PortalCarriageLayout.MIN_WIDTH; carriageWidth <= CarriageDims.MAX_WIDTH; carriageWidth++) {
            CarriageDims dims = CarriageDims.clamp(9, carriageWidth, 7);
            int width = PortalRoomLayout.minWidth(dims);
            assertEquals(0, PortalRoomLayout.maxDoorOffset(dims, width),
                "carriage width " + carriageWidth + ": minWidth has no spare width to give the door");
            assertEquals(0, PortalRoomLayout.clampDoorOffset(dims, width, 7),
                "an offset asked for at minWidth must clamp back to centre");
            assertEquals(0, PortalRoomLayout.clampDoorOffset(dims, width, -7),
                "a negative offset asked for at minWidth must clamp back to centre too");
        }
    }

    @Test
    @DisplayName("A wider room has half its slack to spend on either side of centre")
    void maxDoorOffset_isHalfTheSlackOverMinWidth() {
        CarriageDims dims = DEFAULT_DIMS;
        int min = PortalRoomLayout.minWidth(dims);
        assertEquals(0, PortalRoomLayout.maxDoorOffset(dims, min));
        assertEquals(1, PortalRoomLayout.maxDoorOffset(dims, min + 2));
        assertEquals(1, PortalRoomLayout.maxDoorOffset(dims, min + 3), "odd slack floors down");
        assertEquals(5, PortalRoomLayout.maxDoorOffset(dims, min + 10));

        int max = PortalRoomLayout.maxDoorOffset(dims, min + 10);
        assertEquals(max, PortalRoomLayout.clampDoorOffset(dims, min + 10, max + 4),
            "an offset past the slack clamps to it exactly");
        assertEquals(-max, PortalRoomLayout.clampDoorOffset(dims, min + 10, -max - 4));
        assertEquals(2, PortalRoomLayout.clampDoorOffset(dims, min + 10, 2), "in range passes through");
    }

    @Test
    @DisplayName("An off-centre door still leaves the corridor's whole cross-section inside the room")
    void roomInterior_swallowsTheCorridorCrossSectionEvenOffCentre() {
        BlockPos entry = new BlockPos(0, 0, 0);

        for (int carriageWidth = PortalCarriageLayout.MIN_WIDTH; carriageWidth <= CarriageDims.MAX_WIDTH; carriageWidth++) {
            CarriageDims dims = CarriageDims.clamp(9, carriageWidth, 7);
            PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, PortalCorridorKind.LONG);
            // Comfortably wider than the floor, so there is slack to spend at every offset tried.
            int width = PortalRoomLayout.minWidth(dims) + 10;
            int max = PortalRoomLayout.maxDoorOffset(dims, width);

            for (int offset : new int[]{-max, -1, 0, 1, max}) {
                BlockPos room = PortalRoomLayout.roomOrigin(entry, dims, layout, width, offset);
                int interiorMinZ = room.getZ() + 1;
                int interiorMaxZ = room.getZ() + width - 2;
                assertTrue(interiorMinZ <= entry.getZ() && interiorMaxZ >= entry.getZ() + dims.width() - 1,
                    "carriage width " + carriageWidth + ", offset " + offset
                        + ": the corridor's cross-section must stay inside the room");

                // The corridor itself never moved — only the split around it did.
                assertEquals(entry.getZ() + layout.doorZ(),
                    PortalRoomDoorCells.doorZ(room, new Vec3i(layout.length(),
                        PortalRoomLayout.minHeight(dims), width), offset),
                    "the ghosted door line must still be the corridor's own fixed doorway line");
            }

            // An offset beyond the room's slack clamps rather than pushing the corridor outside it.
            BlockPos clamped = PortalRoomLayout.roomOrigin(entry, dims, layout, width, max + 50);
            BlockPos atMax = PortalRoomLayout.roomOrigin(entry, dims, layout, width, max);
            assertEquals(atMax, clamped, "an offset past the slack must land exactly where the max does");
        }
    }

    @Test
    @DisplayName("A room at exactly minHeight has no slack — every height offset clamps to zero")
    void maxDoorHeightOffset_isZeroAtMinHeight() {
        for (int carriageHeight = CarriageDims.MIN_HEIGHT; carriageHeight <= CarriageDims.MAX_HEIGHT; carriageHeight++) {
            CarriageDims dims = CarriageDims.clamp(9, 7, carriageHeight);
            int height = PortalRoomLayout.minHeight(dims);
            assertEquals(0, PortalRoomLayout.maxDoorHeightOffset(dims, height),
                "carriage height " + carriageHeight + ": minHeight has no spare height to give the door");
            assertEquals(0, PortalRoomLayout.clampDoorHeightOffset(dims, height, 7),
                "a height offset asked for at minHeight must clamp back to the floor");
            assertEquals(0, PortalRoomLayout.clampDoorHeightOffset(dims, height, -7),
                "a negative height offset must clamp to the floor too — it is unsigned");
        }
    }

    @Test
    @DisplayName("A taller room has its whole slack over minHeight to spend, unhalved and one-directional")
    void maxDoorHeightOffset_isTheWholeSlackOverMinHeight() {
        CarriageDims dims = DEFAULT_DIMS;
        int min = PortalRoomLayout.minHeight(dims);
        assertEquals(0, PortalRoomLayout.maxDoorHeightOffset(dims, min));
        assertEquals(5, PortalRoomLayout.maxDoorHeightOffset(dims, min + 5));

        int max = PortalRoomLayout.maxDoorHeightOffset(dims, min + 5);
        assertEquals(max, PortalRoomLayout.clampDoorHeightOffset(dims, min + 5, max + 10),
            "a height offset past the slack clamps to it exactly");
        assertEquals(2, PortalRoomLayout.clampDoorHeightOffset(dims, min + 5, 2), "in range passes through");
    }

    @Test
    @DisplayName("At height offset 0 the room's own floor is still exactly the corridor's — unchanged from before")
    void roomOrigin_heightOffsetZeroMatchesTheFlatOverload() {
        BlockPos entry = new BlockPos(5, 64, -20);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DEFAULT_DIMS, PortalCorridorKind.LONG);
        int width = PortalRoomLayout.minWidth(DEFAULT_DIMS) + 4;
        int height = PortalRoomLayout.minHeight(DEFAULT_DIMS) + 6;

        BlockPos flat = PortalRoomLayout.roomOrigin(entry, DEFAULT_DIMS, layout, width, 2);
        BlockPos twoAxis = PortalRoomLayout.roomOrigin(entry, DEFAULT_DIMS, layout, width, height, 2, 0);
        assertEquals(flat, twoAxis, "height offset 0 must be exactly the pre-existing behaviour");
    }

    @Test
    @DisplayName("A taller room's floor sinks below the corridor's own fixed line as the height offset grows")
    void roomOrigin_heightOffsetSinksTheFloorBelowTheFixedCorridorLine() {
        BlockPos entry = new BlockPos(0, 64, 0);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DEFAULT_DIMS, PortalCorridorKind.LONG);
        int width = PortalRoomLayout.minWidth(DEFAULT_DIMS);
        int height = PortalRoomLayout.minHeight(DEFAULT_DIMS) + 8;
        int max = PortalRoomLayout.maxDoorHeightOffset(DEFAULT_DIMS, height);

        for (int heightOffset : new int[]{0, 1, max / 2, max}) {
            BlockPos room = PortalRoomLayout.roomOrigin(
                entry, DEFAULT_DIMS, layout, width, height, 0, heightOffset);
            // The corridor's own floor never moves — entry.getY() is where PortalTwinLanes put this
            // pair's lane, shared by nothing else.
            assertEquals(entry.getY(), room.getY() + heightOffset,
                "height offset " + heightOffset + ": the corridor's fixed floor line must still be"
                    + " entry.getY(), reached from the room's own floor plus the offset");
            // The room's own top must still reach at least the corridor's own ceiling.
            int roomTop = room.getY() + height - 1;
            int corridorTop = entry.getY() + DEFAULT_DIMS.height() - 1;
            assertTrue(roomTop >= corridorTop,
                "height offset " + heightOffset + ": the corridor's ceiling must stay inside the room");
        }

        // Past the slack, clamps rather than sinking further.
        BlockPos clamped = PortalRoomLayout.roomOrigin(entry, DEFAULT_DIMS, layout, width, height, 0, max + 40);
        BlockPos atMax = PortalRoomLayout.roomOrigin(entry, DEFAULT_DIMS, layout, width, height, 0, max);
        assertEquals(atMax, clamped, "a height offset past the slack must land exactly where the max does");
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
    // ---- the room's two doorways, placed apart ----

    @Test
    @DisplayName("A room whose two doors agree displaces its exit corridor by nothing at all")
    void exitDoorDelta_isZeroWhileTheDoorsMirror() {
        Vec3i size = new Vec3i(11, 13, 21);
        for (int offset = -6; offset <= 6; offset++) {
            assertEquals(0, PortalRoomLayout.exitDoorDeltaZ(
                DEFAULT_DIMS, size.getZ(), offset, offset), "Z at offset " + offset);
        }
        for (int up = 0; up <= 8; up++) {
            assertEquals(0, PortalRoomLayout.exitDoorDeltaY(
                DEFAULT_DIMS, size.getY(), up, up), "Y at offset " + up);
        }
    }

    @Test
    @DisplayName("The delta is the difference of the two CLAMPED doors, never the clamped difference")
    void exitDoorDelta_clampsEachDoorBeforeSubtracting() {
        // 21 wide against a 9-wide floor gives 6 either way; 13 tall against 7 gives 6 up.
        int width = 21;
        int height = 13;
        assertEquals(6, PortalRoomLayout.maxDoorOffset(DEFAULT_DIMS, width));
        assertEquals(6, PortalRoomLayout.maxDoorHeightOffset(DEFAULT_DIMS, height));

        // Both doors asked for more than the room can spend. Clamping the difference would give 0
        // for the first and 94 for the second; clamping each door first is what keeps both
        // corridors inside the box, which is what the mouth's seal needs.
        assertEquals(0, PortalRoomLayout.exitDoorDeltaZ(DEFAULT_DIMS, width, 50, 50));
        assertEquals(12, PortalRoomLayout.exitDoorDeltaZ(DEFAULT_DIMS, width, -50, 50));
        assertEquals(-12, PortalRoomLayout.exitDoorDeltaZ(DEFAULT_DIMS, width, 50, -50));
        assertEquals(6, PortalRoomLayout.exitDoorDeltaY(DEFAULT_DIMS, height, 0, 100));
        assertEquals(-6, PortalRoomLayout.exitDoorDeltaY(DEFAULT_DIMS, height, 100, 0));
    }

    @Test
    @DisplayName("Both corridors stay inside the room at every pair of doors, at every legal width")
    void exitDoorDelta_keepsBothCorridorsInsideTheRoom() {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DEFAULT_DIMS, PortalCorridorKind.LONG);
        BlockPos entryOrigin = new BlockPos(100, -50, 40);

        for (int width = PortalRoomLayout.minWidth(DEFAULT_DIMS); width <= 25; width++) {
            for (int height = PortalRoomLayout.minHeight(DEFAULT_DIMS); height <= 15; height++) {
                int maxZ = PortalRoomLayout.maxDoorOffset(DEFAULT_DIMS, width);
                int maxY = PortalRoomLayout.maxDoorHeightOffset(DEFAULT_DIMS, height);
                for (int entryZ = -maxZ; entryZ <= maxZ; entryZ++) {
                    for (int exitZ = -maxZ; exitZ <= maxZ; exitZ++) {
                        for (int entryY = 0; entryY <= maxY; entryY++) {
                            for (int exitY = 0; exitY <= maxY; exitY++) {
                                assertCorridorsInsideRoom(layout, entryOrigin, width, height,
                                    entryZ, entryY, exitZ, exitY);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Both corridor cross-sections lie inside the room box — the property {@code sealCorridorMouth}
     * relies on, and the one thing two independently-placed doors could break.
     */
    @Test
    @DisplayName("A lane lifted by the door-height offset stands the ROOM's floor on the lane floor")
    void laneLiftedByOffset_standsTheRoomOnTheLane() {
        // The rule both stamp sites use — PortalTestCommand.originFor and the live twin placement in
        // PortalCarriageEvents.ensureStructure. They stamp the corridor LANE, a room spends its
        // door-height offset by dropping its own floor below that line, and a lane's floor is only a
        // couple of blocks off the bottom of the world: a room that hung below it hung out of the
        // world, where setBlock is a silent no-op and the author's lowest rows were never written.
        // Lifting the lane by the same clamped offset is what makes the box the one a lane is sized
        // for, whatever the author asked for.
        PortalCarriageLayout layout =
            PortalCarriageBuilder.layoutFor(DEFAULT_DIMS, PortalCorridorKind.DEFAULT);
        int laneFloor = -46;

        for (int height : new int[]{PortalRoomLayout.minHeight(DEFAULT_DIMS), 12, 40, 70,
                                    PortalRoomLayout.MAX_HEIGHT}) {
            // Past the legal maximum as well as inside it: an author's raw number is unclamped, and
            // the lift has to agree with roomOrigin about how far it may actually run.
            for (int authored : new int[]{0, 1, 4, 47, height, height + 25}) {
                int lift = PortalRoomLayout.clampDoorHeightOffset(DEFAULT_DIMS, height, authored);
                BlockPos entryOrigin = new BlockPos(0, laneFloor + lift, 0);
                BlockPos room = PortalRoomLayout.roomOrigin(
                    entryOrigin, DEFAULT_DIMS, layout, PortalRoomLayout.minWidth(DEFAULT_DIMS),
                    height, 0, authored);

                assertEquals(laneFloor, room.getY(),
                    "h=" + height + " offset=" + authored + ": the room's floor must land on the lane");
                assertTrue(entryOrigin.getY() + DEFAULT_DIMS.height() <= laneFloor + height,
                    "h=" + height + " offset=" + authored + ": the corridor must stay under the lane's ceiling");
            }
        }
    }

    private static void assertCorridorsInsideRoom(PortalCarriageLayout layout, BlockPos entryOrigin,
                                                  int width, int height, int entryZ, int entryY,
                                                  int exitZ, int exitY) {
        BlockPos room = PortalRoomLayout.roomOrigin(
            entryOrigin, DEFAULT_DIMS, layout, width, height, entryZ, entryY);
        String where = "w=" + width + " h=" + height + " entry=(" + entryZ + "," + entryY
            + ") exit=(" + exitZ + "," + exitY + ")";

        // The entry corridor stands where the lane put it; the exit one is displaced by the delta.
        assertInside(room, width, height, entryOrigin.getZ(), entryOrigin.getY(), "entry " + where);
        assertInside(room, width, height,
            entryOrigin.getZ() + PortalRoomLayout.exitDoorDeltaZ(DEFAULT_DIMS, width, entryZ, exitZ),
            entryOrigin.getY() + PortalRoomLayout.exitDoorDeltaY(DEFAULT_DIMS, height, entryY, exitY),
            "exit " + where);
    }

    private static void assertInside(BlockPos room, int width, int height,
                                     int corridorZ, int corridorY, String where) {
        assertTrue(corridorZ >= room.getZ(), where + ": corridor minZ " + corridorZ
            + " is outside room minZ " + room.getZ());
        assertTrue(corridorZ + DEFAULT_DIMS.width() - 1 <= room.getZ() + width - 1,
            where + ": corridor maxZ is outside room maxZ");
        assertTrue(corridorY >= room.getY(), where + ": corridor minY " + corridorY
            + " is outside room minY " + room.getY());
        assertTrue(corridorY + DEFAULT_DIMS.height() - 1 <= room.getY() + height - 1,
            where + ": corridor maxY is outside room maxY");
    }

    @Test
    @DisplayName("Growing stops at the authoring ceiling, but a room already past it is neither shrunk nor grown")
    void authoringCeilingNeverShrinksAnExistingBuild() {
        Vec3i small = new Vec3i(11, 7, 13);
        assertEquals(new Vec3i(64, 7, 13), PortalRoomLayout.heldForAuthoring(small, new Vec3i(80, 7, 13)));
        assertEquals(new Vec3i(11, 64, 13), PortalRoomLayout.heldForAuthoring(small, new Vec3i(11, 90, 13)));
        assertEquals(new Vec3i(40, 7, 13), PortalRoomLayout.heldForAuthoring(small, new Vec3i(40, 7, 13)));

        // Terrarium: 80 tall from before the ceiling existed. It keeps its 80, may not reach 81, and
        // may still be made shorter.
        Vec3i terrarium = new Vec3i(43, 80, 9);
        assertEquals(terrarium, PortalRoomLayout.heldForAuthoring(terrarium, terrarium));
        assertEquals(new Vec3i(43, 80, 9), PortalRoomLayout.heldForAuthoring(terrarium, new Vec3i(43, 81, 9)));
        assertEquals(new Vec3i(43, 70, 9), PortalRoomLayout.heldForAuthoring(terrarium, new Vec3i(43, 70, 9)));
        assertEquals(new Vec3i(64, 80, 9), PortalRoomLayout.heldForAuthoring(terrarium, new Vec3i(90, 80, 9)));
    }
}
