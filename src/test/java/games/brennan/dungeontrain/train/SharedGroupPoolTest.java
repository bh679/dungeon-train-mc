package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.PoolLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Buffer bookkeeping for drifting Group carriages, driven through the {@code offer*ForTest} seams. */
class SharedGroupPoolTest {

    private static final CarriageDims BOX = new CarriageDims(27, 7, 7);
    private static final CarriageDims OTHER_BOX = new CarriageDims(18, 7, 7);

    @AfterEach
    void tidy() {
        SharedGroupPool.clear();
    }

    private static PoolLease lease(int id, CarriageDims box) {
        return new PoolLease(id, "tok" + id, "BLOB", box.length(), box.height(), box.width(),
                0, List.of(), "author" + id, SharedCarriageClient.Credits.EMPTY,
                SharedCarriageClient.Deaths.EMPTY, PoolLease.KIND_CARRIAGE_GROUP, "");
    }

    @Test
    void aGroupIsOnlyServedIntoItsOwnStageAndBox() {
        SharedGroupPool.offerForTest("stone", BOX, lease(1, BOX));
        assertNull(SharedGroupPool.poll("desert", BOX), "not another stage's");
        assertNull(SharedGroupPool.poll("stone", OTHER_BOX), "not another group size's");
        assertEquals(1, SharedGroupPool.poll("stone", BOX).id());
        assertNull(SharedGroupPool.poll("stone", BOX), "each build is served once");
    }

    @Test
    void ownBuildsAreKeptPerAuthor() {
        SharedGroupPool.offerOwnForTest("stone", BOX, "alice", lease(1, BOX));
        assertNull(SharedGroupPool.pollOwn("stone", BOX, List.of("bob")));
        assertNull(SharedGroupPool.poll("stone", BOX), "an own build is not spent on the ordinary pool");
        assertEquals(1, SharedGroupPool.pollOwn("stone", BOX, List.of("bob", "alice")).id());
    }

    @Test
    void demandIsNullUntilAGroupDrifts() {
        assertNull(SharedGroupPool.demand());
        SharedGroupPool.noteDemand("stone", BOX);
        assertEquals(new SharedGroupPool.Demand("stone", BOX), SharedGroupPool.demand());
        SharedGroupPool.noteDemand("", BOX); // a stageless group never steers the prefetch
        assertEquals("stone", SharedGroupPool.demand().stage());
    }

    @Test
    void aGroupLeaseKnowsItIsAGroup() {
        assertEquals(true, lease(1, BOX).isGroup());
        assertEquals(false, lease(1, BOX).isRoom());
    }
}
