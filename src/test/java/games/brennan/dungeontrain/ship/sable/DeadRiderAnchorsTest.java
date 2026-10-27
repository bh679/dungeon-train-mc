package games.brennan.dungeontrain.ship.sable;

import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadRiderAnchorsTest {

    @Test
    void deadRemovedKilledPlayerInThisLevelIsAnAnchor() {
        assertTrue(DeadRiderAnchors.isDeadAnchor(true, true, Entity.RemovalReason.KILLED, true));
    }

    @Test
    void livingPlayerIsNotAnAnchor() {
        // Still in level.players(); counting it here would double it.
        assertFalse(DeadRiderAnchors.isDeadAnchor(true, false, null, false));
    }

    @Test
    void deadPlayerBeforeTick20IsNotAnAnchor() {
        // Not removed yet, so still in level.players().
        assertFalse(DeadRiderAnchors.isDeadAnchor(true, false, null, true));
    }

    @Test
    void playerRemovedForOtherReasonsIsNotAnAnchor() {
        assertFalse(DeadRiderAnchors.isDeadAnchor(true, true, Entity.RemovalReason.CHANGED_DIMENSION, true));
        assertFalse(DeadRiderAnchors.isDeadAnchor(true, true, Entity.RemovalReason.UNLOADED_WITH_PLAYER, true));
        assertFalse(DeadRiderAnchors.isDeadAnchor(true, true, Entity.RemovalReason.DISCARDED, true));
    }

    @Test
    void deadPlayerInAnotherLevelIsNotAnAnchorHere() {
        assertFalse(DeadRiderAnchors.isDeadAnchor(false, true, Entity.RemovalReason.KILLED, true));
    }

    @Test
    void mergeReturnsLiveListItselfWhenNobodyIsDead() {
        List<String> live = List.of("a", "b");
        assertSame(live, DeadRiderAnchors.merged(live, List.of()));
    }

    @Test
    void mergeAppendsDeadAfterLiveWithoutMutatingEither() {
        List<String> live = List.of("a");
        List<String> dead = List.of("corpse");
        List<String> all = DeadRiderAnchors.merged(live, dead);
        assertEquals(List.of("a", "corpse"), all);
        assertEquals(List.of("a"), live);
        assertEquals(List.of("corpse"), dead);
    }
}
