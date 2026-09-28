package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.PoolLease;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Buffer bookkeeping only — the relay round-trips are driven through the test seams. */
class SharedRoomPoolTest {

    private static final Vec3i SIZE = new Vec3i(16, 10, 12);

    @AfterEach
    void tidy() {
        SharedRoomPool.clear();
    }

    private static PoolLease lease(int id, Vec3i size) {
        return new PoolLease(id, "tok" + id, "BLOB", size.getX(), size.getY(), size.getZ(),
                0, List.of(), "author" + id, SharedCarriageClient.Credits.EMPTY,
                SharedCarriageClient.Deaths.EMPTY, PoolLease.KIND_PORTAL_ROOM, "abandonedroom");
    }

    @Test
    @DisplayName("A buffered room is served only to the same stage, room name and size")
    void keyedByStageRoomAndSize() {
        SharedRoomPool.offerForTest("stone", "abandonedroom", SIZE, lease(1, SIZE));
        assertNull(SharedRoomPool.poll("desert", "abandonedroom", SIZE), "another stage");
        assertNull(SharedRoomPool.poll("stone", "backrooms", SIZE), "another room");
        assertNull(SharedRoomPool.poll("stone", "abandonedroom", new Vec3i(16, 10, 14)), "another box");
        assertNull(SharedRoomPool.poll("", "abandonedroom", SIZE), "no stage never draws");
        assertEquals(1, SharedRoomPool.poll("stone", "abandonedroom", SIZE).id());
        assertNull(SharedRoomPool.poll("stone", "abandonedroom", SIZE), "served once");
    }

    @Test
    @DisplayName("Own builds are kept per author and tried in the order the caller gives")
    void ownBuildsPerAuthor() {
        SharedRoomPool.offerOwnForTest("stone", "abandonedroom", SIZE, "alice", lease(1, SIZE));
        SharedRoomPool.offerOwnForTest("stone", "abandonedroom", SIZE, "bob", lease(2, SIZE));
        assertNull(SharedRoomPool.pollOwn("stone", "abandonedroom", SIZE, List.of("carol")));
        assertEquals(2, SharedRoomPool.pollOwn("stone", "abandonedroom", SIZE, List.of("bob", "alice")).id());
        assertEquals(1, SharedRoomPool.pollOwn("stone", "abandonedroom", SIZE, List.of("bob", "alice")).id());
        assertNull(SharedRoomPool.pollOwn("stone", "abandonedroom", SIZE, List.of("alice")));
        assertNull(SharedRoomPool.poll("stone", "abandonedroom", SIZE), "own builds never leak into the shared draw");
    }

    @Test
    @DisplayName("Demand is what the planner last rolled, and clear forgets it")
    void demandFollowsThePlanner() {
        assertNull(SharedRoomPool.demand());
        SharedRoomPool.noteDemand("stone", "abandonedroom", SIZE);
        SharedRoomPool.Demand d = SharedRoomPool.demand();
        assertEquals("stone", d.stage());
        assertEquals("abandonedroom", d.roomName());
        assertEquals(SIZE, d.size());
        SharedRoomPool.noteDemand("", "x", SIZE); // a stageless note is ignored, not recorded
        assertEquals("stone", SharedRoomPool.demand().stage());
        SharedRoomPool.clear();
        assertNull(SharedRoomPool.demand());
        assertEquals(0, SharedRoomPool.buffered());
    }
}
