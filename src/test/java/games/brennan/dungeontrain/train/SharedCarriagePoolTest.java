package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient.PoolLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Buffer bookkeeping only — the relay round-trips are driven through {@code offer*ForTest} seams, so
 * nothing here touches HTTP.
 */
class SharedCarriagePoolTest {

    private static final CarriageDims DIMS = CarriageDims.clamp(9, 7, 7);

    @AfterEach
    void tidy() {
        SharedCarriagePool.clear();
    }

    private static PoolLease lease(int id) {
        return new PoolLease(id, "tok" + id, "BLOB", DIMS.length(), DIMS.height(), DIMS.width(),
                0, List.of(), "author" + id);
    }

    @Test
    void ownBuildsAreKeptPerAuthorAndPerStage() {
        SharedCarriagePool.offerOwnForTest("stone", "alice", lease(1));
        SharedCarriagePool.offerOwnForTest("desert", "alice", lease(2));
        SharedCarriagePool.offerOwnForTest("stone", "bob", lease(3));

        // Nobody else's build, and not another stage's.
        assertNull(SharedCarriagePool.pollOwn(DIMS, "stone", List.of("carol")));
        assertNull(SharedCarriagePool.pollOwn(DIMS, "nether", List.of("alice", "bob")));

        assertEquals(1, SharedCarriagePool.pollOwn(DIMS, "stone", List.of("alice")).id());
        assertEquals(3, SharedCarriagePool.pollOwn(DIMS, "stone", List.of("bob")).id());
        assertEquals(2, SharedCarriagePool.pollOwn(DIMS, "desert", List.of("alice")).id());
        assertNull(SharedCarriagePool.pollOwn(DIMS, "stone", List.of("alice")), "each build is served once");
    }

    @Test
    void ownersAreTriedInTheOrderGiven() {
        SharedCarriagePool.offerOwnForTest("stone", "alice", lease(1));
        SharedCarriagePool.offerOwnForTest("stone", "bob", lease(2));
        assertEquals(2, SharedCarriagePool.pollOwn(DIMS, "stone", List.of("bob", "alice")).id());
        assertEquals(1, SharedCarriagePool.pollOwn(DIMS, "stone", List.of("bob", "alice")).id());
    }

    @Test
    void theTwoBuffersAreDrainedIndependently() {
        SharedCarriagePool.offerForTest("stone", lease(1));
        SharedCarriagePool.offerOwnForTest("stone", "alice", lease(2));
        assertEquals(2, SharedCarriagePool.buffered(), "both buffers count toward the world's lock budget");

        assertEquals(1, SharedCarriagePool.poll(DIMS, "stone").id(), "the pool poll must not spend an own build");
        assertNull(SharedCarriagePool.poll(DIMS, "stone"));
        assertNotNull(SharedCarriagePool.pollOwn(DIMS, "stone", List.of("alice")), "own build still there");
        assertEquals(0, SharedCarriagePool.buffered());
    }

    @Test
    void aStagelessOrOwnerlessRequestNeverDrawsFromTheBuffer() {
        SharedCarriagePool.offerOwnForTest("stone", "alice", lease(1));
        assertNull(SharedCarriagePool.pollOwn(DIMS, "", List.of("alice")));
        assertNull(SharedCarriagePool.pollOwn(DIMS, null, List.of("alice")));
        assertNull(SharedCarriagePool.pollOwn(DIMS, "stone", List.of()));
        assertNull(SharedCarriagePool.pollOwn(DIMS, "stone", null));
        assertEquals(1, SharedCarriagePool.buffered(), "nothing was spent on an unanswerable request");
    }

    @Test
    void clearResetsBothBuffers() {
        SharedCarriagePool.offerForTest("stone", lease(1));
        SharedCarriagePool.offerOwnForTest("stone", "alice", lease(2));
        SharedCarriagePool.clear();
        assertEquals(0, SharedCarriagePool.buffered());
        assertNull(SharedCarriagePool.pollOwn(DIMS, "stone", List.of("alice")));
    }
}
