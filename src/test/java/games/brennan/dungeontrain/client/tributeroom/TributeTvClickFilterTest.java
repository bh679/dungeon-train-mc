package games.brennan.dungeontrain.client.tributeroom;

import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** One press on a tribute TV reaches the page once, however many interaction events it fires. */
class TributeTvClickFilterTest {

    private static final long FIRST = Long.MAX_VALUE;

    @Test
    void rightClickDeliversOnlyForTheMainHand() {
        assertTrue(TributeTvClientEvents.deliversClick(true, InteractionHand.MAIN_HAND, null, FIRST));
        assertFalse(TributeTvClientEvents.deliversClick(true, InteractionHand.OFF_HAND, null, FIRST),
            "the off hand's pass after a cancelled main-hand use is the same press");
    }

    @Test
    void leftClickDeliversOnlyOnAFreshStart() {
        assertTrue(TributeTvClientEvents.deliversClick(false, InteractionHand.MAIN_HAND, Action.START, FIRST));
        assertTrue(TributeTvClientEvents.deliversClick(false, InteractionHand.MAIN_HAND, Action.START, 2),
            "a gap of a tick is a new press");
        for (Action a : Action.values()) {
            if (a == Action.START) continue;
            assertFalse(TributeTvClientEvents.deliversClick(false, InteractionHand.MAIN_HAND, a, FIRST), a + " is never a new press");
        }
    }

    @Test
    void heldLeftClickRestartingEveryTickIsOnePress() {
        // A cancelled left-click held down re-fires START in the same tick and every tick after.
        assertFalse(TributeTvClientEvents.deliversClick(false, InteractionHand.MAIN_HAND, Action.START, 0));
        assertFalse(TributeTvClientEvents.deliversClick(false, InteractionHand.MAIN_HAND, Action.START, 1));
    }
}
