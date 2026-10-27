package games.brennan.dungeontrain.client.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The perks menu opened on death: the points page, the drain into the menu, and what each phase shows. */
class DeathPerksPhaseTest {

    private static final PerkMenuSnapshot POINTED =
        new PerkMenuSnapshot(Map.of("health", 3, "speed", 2), Map.of("health", 3, "speed", 1), 4, 2);
    private static final PerkMenuSnapshot NO_POINTS =
        new PerkMenuSnapshot(Map.of("health", 3), Map.of(), 4, 0);

    @Test
    @DisplayName("a life with points opens on the points page; one without opens on the menu")
    void opensOnPointsOnlyWithPoints() {
        assertEquals(DeathPerksPhase.Phase.POINTS, new DeathPerksPhase(POINTED, () -> null).phase(0));
        assertEquals(DeathPerksPhase.Phase.UNLOCK, new DeathPerksPhase(NO_POINTS, () -> null).phase(0));
    }

    @Test
    @DisplayName("Next Screen drains the rings into the menu, which then takes input")
    void drainRunsToUnlock() {
        DeathPerksPhase phase = new DeathPerksPhase(POINTED, () -> null);
        assertEquals(1.0, phase.pageAlpha(0));
        assertEquals(0.0, phase.menuAlpha(0));
        assertFalse(phase.menuLive(0));
        phase.advance(1000);
        assertEquals(DeathPerksPhase.Phase.DRAIN, phase.phase(1000));
        assertEquals(1.0, phase.ringsLeft(1000));
        assertTrue(phase.ringsLeft(1600) < 1 && phase.ringsLeft(1600) > 0);
        assertEquals(0.0, phase.pageAlpha(1000 + 400));
        assertEquals(DeathPerksPhase.Phase.UNLOCK, phase.phase(1000 + DeathPerksPhase.DRAIN_MS));
        assertTrue(phase.menuLive(1000 + DeathPerksPhase.DRAIN_MS));
        assertEquals(1.0, phase.menuAlpha(1000 + DeathPerksPhase.DRAIN_MS));
    }

    @Test
    @DisplayName("a second advance during the drain skips to the menu")
    void skipDrain() {
        DeathPerksPhase phase = new DeathPerksPhase(POINTED, () -> null);
        phase.advance(0);
        phase.advance(100);
        assertEquals(DeathPerksPhase.Phase.UNLOCK, phase.phase(100));
    }

    @Test
    @DisplayName("the points page shows the points held at death; the menu shows the live tree with none")
    void viewPerPhase() {
        PerkMenuSnapshot live = new PerkMenuSnapshot(Map.of("health", 4, "speed", 2), Map.of("health", 3), 1, 2);
        DeathPerksPhase phase = new DeathPerksPhase(POINTED, () -> null);
        assertEquals(POINTED, phase.view(live, 0));
        phase.advance(0);
        PerkMenuSnapshot unlock = phase.view(live, DeathPerksPhase.DRAIN_MS);
        assertTrue(unlock.active().isEmpty());
        assertEquals(0, unlock.activePoints());
        assertEquals(4, unlock.unlockLevel("health"));
        assertEquals(1, unlock.unlockPoints());
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
}
