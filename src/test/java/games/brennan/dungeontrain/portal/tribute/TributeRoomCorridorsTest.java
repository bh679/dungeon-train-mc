package games.brennan.dungeontrain.portal.tribute;

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
}
