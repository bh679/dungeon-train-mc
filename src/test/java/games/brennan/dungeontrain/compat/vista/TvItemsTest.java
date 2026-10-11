package games.brennan.dungeontrain.compat.vista;

import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which hand a left-click toggles. */
final class TvItemsTest {

    @Test
    @DisplayName("a TV in the main hand toggles whatever the offhand holds")
    void mainHandWins() {
        assertEquals(InteractionHand.MAIN_HAND, TvItems.toggleHand(true, false, false));
        assertEquals(InteractionHand.MAIN_HAND, TvItems.toggleHand(true, false, true));
    }

    @Test
    @DisplayName("an offhand TV toggles only with an empty main hand; a sword still swings")
    void offhandNeedsEmptyMain() {
        assertEquals(InteractionHand.OFF_HAND, TvItems.toggleHand(false, true, true));
        assertNull(TvItems.toggleHand(false, false, true));
        assertNull(TvItems.toggleHand(false, true, false));
        assertNull(TvItems.toggleHand(false, false, false));
    }
}
