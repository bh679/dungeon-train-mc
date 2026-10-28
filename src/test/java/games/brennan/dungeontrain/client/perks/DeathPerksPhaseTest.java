package games.brennan.dungeontrain.client.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The perks menu opened on death: it builds up, the points held at death show, then drain away. */
class DeathPerksPhaseTest {

    private static final PerkMenuSnapshot POINTED =
        new PerkMenuSnapshot(Map.of("health", 3, "speed", 2), Map.of("health", 3, "speed", 1), 4, 2);
    private static final PerkMenuSnapshot NO_POINTS =
        new PerkMenuSnapshot(Map.of("health", 3), Map.of(), 4, 0);

    @Test
    @DisplayName("the menu takes input only once every layer is up; a click brings them all up at once")
    void liveOnceBuilt() {
        DeathPerksPhase phase = new DeathPerksPhase(POINTED, DeathStory.of(null, POINTED, "en_us"), () -> null, 0);
        long done = phase.reveal().doneAt();
        assertFalse(phase.menuLive(0));
        assertFalse(phase.menuLive(done - 1));
        assertTrue(phase.menuLive(done));
        DeathPerksPhase skipped = new DeathPerksPhase(POINTED, DeathStory.of(null, POINTED, "en_us"), () -> null, 0);
        skipped.advance(500);
        assertTrue(skipped.menuLive(500));
    }

    @Test
    @DisplayName("a life with points shows them through the build-up, then the rings empty")
    void pointsThenDrain() {
        PerkMenuSnapshot live = new PerkMenuSnapshot(Map.of("health", 4, "speed", 2), Map.of("health", 3), 1, 2);
        DeathPerksPhase phase = new DeathPerksPhase(POINTED, DeathStory.of(null, POINTED, "en_us"), () -> null, 0);
        long done = phase.reveal().doneAt();
        assertEquals(POINTED.active(), phase.view(live, 0).active(), "the points held at death, not the live ones");
        assertEquals(POINTED.active(), phase.view(live, done - 1).active());
        assertEquals(1.0, phase.ringsLeft(done));
        double mid = phase.ringsLeft(done + DeathPerksPhase.DRAIN_MS / 2);
        assertTrue(mid > 0 && mid < 1);
        PerkMenuSnapshot unlock = phase.view(live, done + DeathPerksPhase.DRAIN_MS);
        assertTrue(unlock.active().isEmpty());
        assertEquals(0, unlock.activePoints());
        assertEquals(4, unlock.unlockLevel("health"), "unlocks are live");
        assertEquals(1, unlock.unlockPoints());
    }

    @Test
    @DisplayName("a life with no points skips the active layer")
    void noPointsNoActiveLayer() {
        DeathPerksPhase none = new DeathPerksPhase(NO_POINTS, DeathStory.of(null, NO_POINTS, "en_us"), () -> null, 0);
        DeathPerksPhase some = new DeathPerksPhase(POINTED, DeathStory.of(null, POINTED, "en_us"), () -> null, 0);
        assertTrue(none.reveal().startOf(MenuReveal.Stage.CLASSES) < some.reveal().startOf(MenuReveal.Stage.CLASSES));
        assertEquals(0.0, none.reveal().alpha(MenuReveal.Stage.ACTIVE, 60_000));
    }

    @Test
    @DisplayName("drained keeps a share of each perk's points, to the nearest, and drops the empty ones")
    void drained() {
        assertEquals(POINTED.active(), POINTED.drained(1).active());
        assertEquals(Map.of("health", 2, "speed", 1), POINTED.drained(0.5).active());
        assertEquals(Map.of("health", 1), POINTED.drained(0.2).active());
        assertTrue(POINTED.drained(0).active().isEmpty());
        assertEquals(POINTED.unlocks(), POINTED.drained(0).unlocks());
    }

    @Test
    @DisplayName("the top perk is the one holding the most points")
    void topPerk() {
        assertEquals("health", PointsCarriedPanel.topPerk(POINTED));
        assertNull(PointsCarriedPanel.topPerk(NO_POINTS));
    }

    @Test
    @DisplayName("never unlocked a perk: the guided first time")
    void firstTime() {
        DeathPerksPhase fresh = new DeathPerksPhase(PerkMenuSnapshot.EMPTY, DeathStory.of(null, PerkMenuSnapshot.EMPTY, "en_us"), () -> null, 0);
        assertTrue(fresh.reveal().firstTime());
        assertFalse(new DeathPerksPhase(NO_POINTS, DeathStory.of(null, NO_POINTS, "en_us"), () -> null, 0).reveal().firstTime());
        assertTrue(fresh.menuLive(fresh.reveal().startOf(MenuReveal.Stage.CLASSES)));
    }
}
