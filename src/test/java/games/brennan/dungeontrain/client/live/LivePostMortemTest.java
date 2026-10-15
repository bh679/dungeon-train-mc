package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.DeathMomentScreen;
import games.brennan.dungeontrain.client.NarrativeDeathScreen;
import games.brennan.dungeontrain.client.snapshot.RideGalleryScreen;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.live.LivePostMortem.SCREEN_GRACE_TICKS;
import static games.brennan.dungeontrain.client.live.LivePostMortem.shouldEnd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A stream that carries on through the death screens, and when it stops. */
class LivePostMortemTest {

    @Test
    void deathScreenNamesMatchTheRealClasses() {
        assertTrue(LivePostMortem.isDeathScreen(DeathMomentScreen.class.getName()));
        assertTrue(LivePostMortem.isDeathScreen(NarrativeDeathScreen.class.getName()));
        assertTrue(LivePostMortem.isDeathScreen(LivePostMortemChoiceScreen.class.getName()));
        assertFalse(LivePostMortem.isDeathScreen("net.minecraft.client.gui.screens.DeathScreen"));
        assertFalse(LivePostMortem.isDeathScreen(null));
    }

    @Test
    void deathScreensMayBeStreamed() {
        for (Class<?> c : new Class<?>[] {DeathMomentScreen.class, NarrativeDeathScreen.class,
                LivePostMortemChoiceScreen.class, RideGalleryScreen.class}) {
            assertTrue(StreamableScreens.shown(StreamableScreens.lineage(c), false), c.getName());
            assertFalse(StreamableScreens.shown(StreamableScreens.lineage(c), true), "typing hides " + c.getName());
        }
    }

    @Test
    void endsWhenNoDeathScreenComesUpInTime() {
        assertFalse(shouldEnd(SCREEN_GRACE_TICKS - 1, false, true, false));
        assertTrue(shouldEnd(SCREEN_GRACE_TICKS, false, true, false));
        assertFalse(shouldEnd(10_000, true, true, false), "the death screens can stay up as long as they like");
    }

    @Test
    void endsOnRespawnOnlyAfterBeingSeenDead() {
        assertFalse(shouldEnd(1, true, false, true), "STOP_DIED can land before the client sees the death");
        assertTrue(shouldEnd(100, true, true, true));
    }

    @Test
    void phases() {
        LivePostMortem pm = new LivePostMortem();
        assertFalse(pm.active());
        pm.choseContinue();
        assertEquals(LivePostMortem.Phase.NONE, pm.phase(), "nothing to continue before a death");
        pm.begin();
        assertEquals(LivePostMortem.Phase.CHOOSING, pm.phase());
        pm.choseContinue();
        assertEquals(LivePostMortem.Phase.CONTINUING, pm.phase());
        assertFalse(pm.tick(NarrativeDeathScreen.class, true, false));
        pm.clear();
        assertFalse(pm.active());
        assertFalse(pm.tick(null, false, true), "inactive never ends anything");
    }
}
