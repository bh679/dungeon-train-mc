package games.brennan.dungeontrain.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The two rules behind {@code RespawnDimensionEvents.placeOrWake}: which of land / wake / spawn a
 * returning player gets, and which sleeping group is asked back first. The bug these pin: a
 * player back from the End used to get SPAWN whenever nothing was loaded, which put a second
 * train under the one Sable had in holding.
 */
class RespawnTrainReturnTest {

    @Test
    void loadedDeckAlwaysLands() {
        assertEquals(RespawnDimensionEvents.Outcome.LAND, RespawnDimensionEvents.decide(true, true));
        assertEquals(RespawnDimensionEvents.Outcome.LAND, RespawnDimensionEvents.decide(true, false));
    }

    @Test
    void knownButUnloadedTrainIsWokenNotRespawned() {
        assertEquals(RespawnDimensionEvents.Outcome.WAKE_AND_DEFER, RespawnDimensionEvents.decide(false, true));
    }

    @Test
    void onlyADimensionWithNoTrainGetsASeed() {
        assertEquals(RespawnDimensionEvents.Outcome.SPAWN, RespawnDimensionEvents.decide(false, false));
    }

    @Test
    void picksAnchorNearestTheLastSeenGroup() {
        assertEquals(6, RespawnDimensionEvents.pickAnchorToWake(Set.of(-3, 0, 3, 6, 9), 7));
        assertEquals(0, RespawnDimensionEvents.pickAnchorToWake(Set.of(-3, 0, 3), 1));
    }

    @Test
    void withoutAFixPicksTheFrontOfTheTrain() {
        assertEquals(9, RespawnDimensionEvents.pickAnchorToWake(Set.of(-3, 0, 3, 6, 9), null));
    }

    @Test
    void emptyRegistryPicksNothing() {
        assertNull(RespawnDimensionEvents.pickAnchorToWake(Set.of(), null));
        assertNull(RespawnDimensionEvents.pickAnchorToWake(Set.of(), 4));
    }
}
