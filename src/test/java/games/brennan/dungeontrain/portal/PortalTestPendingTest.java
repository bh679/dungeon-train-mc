package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test the Carriage presses waiting on a chunk sample, and the cache rule that keeps one test room's
 * ground out of another's under the shared test key.
 */
class PortalTestPendingTest {

    private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @AfterEach
    void tearDown() {
        PortalTestPending.clear();
    }

    @Test
    @DisplayName("A press waits out its timeout and no longer")
    void pending_expiresAfterTimeout() {
        PortalTestPending.put(AUTHOR, "chunk_dimension", false, 100L);
        PortalTestPending.Pending pending = PortalTestPending.entries().iterator().next().getValue();

        assertFalse(pending.expired(100L + PortalTestPending.TIMEOUT_TICKS));
        assertTrue(pending.expired(100L + PortalTestPending.TIMEOUT_TICKS + 1));
    }

    @Test
    @DisplayName("A second press replaces the first — one room, one stamp")
    void pending_secondPressReplacesFirst() {
        PortalTestPending.put(AUTHOR, "chunk_dimension", false, 0L);
        PortalTestPending.Pending first = PortalTestPending.entries().iterator().next().getValue();
        PortalTestPending.put(AUTHOR, "chunk_dimension_nether", true, 5L);

        assertEquals(1, PortalTestPending.entries().size());
        // The ticker finishing the first press must not drop the second.
        PortalTestPending.remove(AUTHOR, first);
        assertEquals("chunk_dimension_nether",
            PortalTestPending.entries().iterator().next().getValue().roomName());
    }

    @Test
    @DisplayName("Back withdraws a waiting press and says it did")
    void cancel_reportsWhetherAPressWasWaiting() {
        assertFalse(PortalTestPending.cancel(AUTHOR));
        PortalTestPending.put(AUTHOR, "chunk_dimension", false, 0L);
        assertTrue(PortalTestPending.cancel(AUTHOR));
        assertTrue(PortalTestPending.isEmpty());
    }

    @Test
    @DisplayName("A cube sampled for one room is not another room's ground")
    void cache_roomMismatchIsAMiss() {
        assertTrue(PortalChunkTerrain.sameRoom("chunk_dimension", "chunk_dimension"));
        assertFalse(PortalChunkTerrain.sameRoom("chunk_dimension", "chunk_dimension_nether"));
        assertFalse(PortalChunkTerrain.sameRoom("chunk_dimension_nether", "chunk_dimension_end"));
    }

    @Test
    @DisplayName("A reseed moves the test key on to a fresh roll; play's keys stay at zero")
    void reroll_advancesOnlyTheRerolledKey() {
        PortalChunkTerrain.clear();
        assertEquals(0, PortalChunkTerrain.rollOf(PortalTestSession.PAIR_KEY));
        PortalChunkTerrain.reroll(PortalTestSession.PAIR_KEY);
        PortalChunkTerrain.reroll(PortalTestSession.PAIR_KEY);
        assertEquals(2, PortalChunkTerrain.rollOf(PortalTestSession.PAIR_KEY));
        assertEquals(0, PortalChunkTerrain.rollOf(PortalTestSession.PAIR_KEY + 1));
        PortalChunkTerrain.clear();
        assertEquals(0, PortalChunkTerrain.rollOf(PortalTestSession.PAIR_KEY));
    }

    @Test
    @DisplayName("Nothing has failed before anything is sampled")
    void failed_isFalseForAnUnsampledPair() {
        PortalChunkTerrain.clear();
        assertFalse(PortalChunkTerrain.failed(PortalTestSession.PAIR_KEY, "chunk_dimension"));
    }
}
