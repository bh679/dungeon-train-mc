package games.brennan.dungeontrain.client.lod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.lod.CarriageLodController.Action;
import static games.brennan.dungeontrain.client.lod.CarriageLodController.DEMOTE_GRACE_TICKS;
import static games.brennan.dungeontrain.client.lod.CarriageLodController.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for {@link CarriageLodController#decide} — the LOD hysteresis (eager promote,
 * lazy demote after a grace window). Registry-free booleans/ints, so no Minecraft bootstrap — same
 * pattern as {@code PhysicsFreezeControllerTest}, whose freeze hysteresis this mirrors.
 * Expectations derive from {@link CarriageLodController#DEMOTE_GRACE_TICKS} so retuning can't
 * stale them.
 */
final class CarriageLodControllerTest {

    @Test
    @DisplayName("grace window is positive (demotion is genuinely lazy)")
    void graceIsUsable() {
        assertTrue(DEMOTE_GRACE_TICKS >= 1, "need a grace window for lazy demote to mean anything");
    }

    @Test
    @DisplayName("near + far-tier → PROMOTE immediately (eager), regardless of counter")
    void nearWhileFar_promotesEagerly() {
        assertEquals(Action.PROMOTE, decide(false, 0, true));
        assertEquals(Action.PROMOTE, decide(false, DEMOTE_GRACE_TICKS * 5, true));
    }

    @Test
    @DisplayName("near + near-tier → hold (nothing to do)")
    void nearWhileNear_holds() {
        assertEquals(Action.NONE, decide(false, 0, false));
        assertEquals(Action.NONE, decide(false, 999, false));
    }

    @Test
    @DisplayName("far + near-tier, below grace → hold (don't demote prematurely)")
    void farBelowGrace_holds() {
        assertEquals(Action.NONE, decide(true, 0, false));
        assertEquals(Action.NONE, decide(true, DEMOTE_GRACE_TICKS - 1, false));
    }

    @Test
    @DisplayName("far + near-tier, at/above grace → DEMOTE")
    void farAtGrace_demotes() {
        assertEquals(Action.DEMOTE, decide(true, DEMOTE_GRACE_TICKS, false));
        assertEquals(Action.DEMOTE, decide(true, DEMOTE_GRACE_TICKS + 100, false));
    }

    @Test
    @DisplayName("far + far-tier → hold (already demoted, don't re-demote)")
    void farWhileFar_holds() {
        assertEquals(Action.NONE, decide(true, DEMOTE_GRACE_TICKS, true));
        assertEquals(Action.NONE, decide(true, 0, true));
    }

    @Test
    @DisplayName("a carriage pacing the boundary never flaps: one near tick resets the grace")
    void boundaryPacingDoesNotFlap() {
        // Sit just short of demoting...
        int ticksFar = DEMOTE_GRACE_TICKS - 1;
        assertEquals(Action.NONE, decide(true, ticksFar, false));

        // ...one tick back inside resets the counter (the reconcile zeroes it on a near sample),
        // so the next far tick starts over rather than tipping straight into a demote.
        assertEquals(Action.NONE, decide(false, 0, false));
        assertEquals(Action.NONE, decide(true, 1, false));
    }
}
