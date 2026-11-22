package games.brennan.dungeontrain.portal.tribute;

import games.brennan.dungeontrain.portal.PortalRoomDoorCells;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TributeRoomCorridorsTest {

    private static final BlockPos ROOM = new BlockPos(13, -62, -515);
    private static final Vec3i ROOM_SIZE = new Vec3i(11, 7, 13);
    private static final Vec3i TUBE = new Vec3i(16, 7, 9);

    @Test
    void entranceEndsOneBeforeTheRoom() {
        BlockPos o = TributeRoomCorridors.entranceOrigin(ROOM, ROOM_SIZE, TUBE);
        assertEquals(13 - 16, o.getX());
        assertEquals(ROOM.getY(), o.getY(), "same floor row");
        assertEquals(-515 + 2, o.getZ(), "a 9-wide tube centred on a 13-wide room");
        assertEquals(12, TributeRoomCorridors.boxOf(o, TUBE).maxX(), "its last column touches the room's first");
    }

    @Test
    void exitStartsOnePastTheRoom() {
        BlockPos o = TributeRoomCorridors.exitOrigin(ROOM, ROOM_SIZE, TUBE);
        assertEquals(13 + 11, o.getX());
        assertEquals(-513, o.getZ());
    }

    @Test
    void windowWrapsEverythingWithAirToSpare() {
        BoundingBox room = TributeRoomCorridors.boxOf(ROOM, ROOM_SIZE);
        BoundingBox in = TributeRoomCorridors.boxOf(TributeRoomCorridors.entranceOrigin(ROOM, ROOM_SIZE, TUBE), TUBE);
        BoundingBox out = TributeRoomCorridors.boxOf(TributeRoomCorridors.exitOrigin(ROOM, ROOM_SIZE, TUBE), TUBE);
        BoundingBox w = TributeRoomCorridors.window(room, List.of(in, out));
        assertEquals(13 - 16 - 1, w.minX());
        assertEquals(13 + 11 + 16, w.maxX());
        assertEquals(-516, w.minZ());
        assertEquals(-515 + 13, w.maxZ());
        assertTrue(w.isInside(new BlockPos(0, -60, -510)));
    }

    @Test
    void noOffsetsIsTheOriginalPlacement() {
        TributeRoomCorridors.DoorOffsets none = TributeRoomCorridors.DoorOffsets.NONE;
        assertEquals(TributeRoomCorridors.entranceOrigin(ROOM, ROOM_SIZE, TUBE),
            TributeRoomCorridors.entranceOrigin(ROOM, ROOM_SIZE, TUBE, none));
        assertEquals(TributeRoomCorridors.exitOrigin(ROOM, ROOM_SIZE, TUBE),
            TributeRoomCorridors.exitOrigin(ROOM, ROOM_SIZE, TUBE, none));
    }

    @Test
    void eachTubeRisesWithItsOwnDoor() {
        TributeRoomCorridors.DoorOffsets doors = new TributeRoomCorridors.DoorOffsets(0, 2, 0, 1);
        assertEquals(ROOM.getY() + 2, TributeRoomCorridors.entranceOrigin(ROOM, ROOM_SIZE, TUBE, doors).getY());
        assertEquals(ROOM.getY() + 1, TributeRoomCorridors.exitOrigin(ROOM, ROOM_SIZE, TUBE, doors).getY());
    }

    @Test
    void eachTubeSlidesWithItsOwnDoor() {
        TributeRoomCorridors.DoorOffsets doors = new TributeRoomCorridors.DoorOffsets(-1, 0, 2, 0);
        assertEquals(-513 - 1, TributeRoomCorridors.entranceOrigin(ROOM, ROOM_SIZE, TUBE, doors).getZ());
        assertEquals(-513 + 2, TributeRoomCorridors.exitOrigin(ROOM, ROOM_SIZE, TUBE, doors).getZ());
    }

    /**
     * The tube against the code that cuts the doors: for every offset pair, the tube's first walkable
     * row is the door's lower cell, and its centre line moves exactly as the door's does.
     */
    @Test
    void tubesStayFlushWithTheDoorCellsAtEveryOffset() {
        int centreZIn = TributeRoomCorridors.entranceOrigin(ROOM, ROOM_SIZE, TUBE).getZ();
        int centreZOut = TributeRoomCorridors.exitOrigin(ROOM, ROOM_SIZE, TUBE).getZ();
        int doorZ0 = PortalRoomDoorCells.doorZ(ROOM, ROOM_SIZE, 0);
        for (int y = 0; y <= 3; y++) {
            for (int ey = 0; ey <= 3; ey++) {
                for (int z = -2; z <= 2; z++) {
                    for (int ez = -2; ez <= 2; ez++) {
                        TributeRoomCorridors.DoorOffsets doors = new TributeRoomCorridors.DoorOffsets(z, y, ez, ey);
                        List<BlockPos> cells = PortalRoomDoorCells.forRoom(ROOM, ROOM_SIZE, z, y, ez, ey);
                        BlockPos entryDoor = cells.get(0), exitDoor = cells.get(PortalRoomDoorCells.CELLS_PER_DOOR);
                        BlockPos in = TributeRoomCorridors.entranceOrigin(ROOM, ROOM_SIZE, TUBE, doors);
                        BlockPos out = TributeRoomCorridors.exitOrigin(ROOM, ROOM_SIZE, TUBE, doors);
                        assertEquals(entryDoor.getY(), in.getY() + 1, "entrance floor under its door");
                        assertEquals(exitDoor.getY(), out.getY() + 1, "exit floor under its door");
                        assertEquals(entryDoor.getZ() - doorZ0, in.getZ() - centreZIn, "entrance follows its door sideways");
                        assertEquals(exitDoor.getZ() - doorZ0, out.getZ() - centreZOut, "exit follows its door sideways");
                        assertEquals(entryDoor.getX(), TributeRoomCorridors.boxOf(in, TUBE).maxX(), "entrance ends at the door column");
                        assertEquals(exitDoor.getX(), out.getX(), "exit starts at the door column");
                    }
                }
            }
        }
    }
}
