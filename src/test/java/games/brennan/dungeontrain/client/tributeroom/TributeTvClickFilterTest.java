package games.brennan.dungeontrain.client.tributeroom;

import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** One press on a tribute TV reaches the page once, however many interaction events it fires. */
class TributeTvClickFilterTest {

    @Test
    void rightClickDeliversOnlyForTheMainHand() {
        assertTrue(TributeTvClientEvents.deliversClick(true, InteractionHand.MAIN_HAND, null));
        assertFalse(TributeTvClientEvents.deliversClick(true, InteractionHand.OFF_HAND, null),
            "the off hand's pass after a cancelled main-hand use is the same press");
    }

    @Test
    void leftClickDeliversOnlyOnStart() {
        assertTrue(TributeTvClientEvents.deliversClick(false, InteractionHand.MAIN_HAND, Action.START));
        for (Action a : Action.values()) {
            if (a == Action.START) continue;
            assertFalse(TributeTvClientEvents.deliversClick(false, InteractionHand.MAIN_HAND, a), a + " is the same press held or let go");
        }
    }
}
