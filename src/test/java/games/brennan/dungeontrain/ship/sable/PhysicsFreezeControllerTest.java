package games.brennan.dungeontrain.ship.sable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.ship.sable.PhysicsFreezeController.Action;
import static games.brennan.dungeontrain.ship.sable.PhysicsFreezeController.FREEZE_GRACE_TICKS;
import static games.brennan.dungeontrain.ship.sable.PhysicsFreezeController.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for {@link PhysicsFreezeController#decide} — the freeze hysteresis for issue
 * #646 (eager unfreeze, lazy freeze after a grace window). Registry-free booleans/ints, so no
 * Minecraft bootstrap — same pattern as {@link PhysicsSubstepTunerTest}. Expectations derive from
 * the package-private {@link PhysicsFreezeController#FREEZE_GRACE_TICKS} so retuning can't stale them.
 */
final class PhysicsFreezeControllerTest {

    @Test
    @DisplayName("grace window is positive (freezing is genuinely lazy)")
    void graceIsUsable() {
        assertTrue(FREEZE_GRACE_TICKS >= 1, "need a grace window for lazy freeze to mean anything");
    }

    @Test
    @DisplayName("active + frozen → UNFREEZE immediately (eager), regardless of counter")
    void activeFrozen_unfreezesEagerly() {
        assertEquals(Action.UNFREEZE, decide(true, 0, true));
        assertEquals(Action.UNFREEZE, decide(true, FREEZE_GRACE_TICKS * 5, true));
    }

    @Test
    @DisplayName("active + not frozen → hold (nothing to do)")
    void activeUnfrozen_holds() {
        assertEquals(Action.NONE, decide(true, 0, false));
        assertEquals(Action.NONE, decide(true, 999, false));
    }

    @Test
    @DisplayName("inactive + not frozen, below grace → hold (don't freeze prematurely)")
    void inactiveBelowGrace_holds() {
        assertEquals(Action.NONE, decide(false, 0, false));
        assertEquals(Action.NONE, decide(false, FREEZE_GRACE_TICKS - 1, false));
    }

    @Test
    @DisplayName("inactive + not frozen, at/above grace → FREEZE")
    void inactiveAtGrace_freezes() {
        assertEquals(Action.FREEZE, decide(false, FREEZE_GRACE_TICKS, false));
        assertEquals(Action.FREEZE, decide(false, FREEZE_GRACE_TICKS + 50, false));
    }

    @Test
    @DisplayName("inactive + already frozen → hold (stay frozen, no churn)")
    void inactiveFrozen_stays() {
        assertEquals(Action.NONE, decide(false, 0, true));
        assertEquals(Action.NONE, decide(false, FREEZE_GRACE_TICKS * 3, true));
    }
}
