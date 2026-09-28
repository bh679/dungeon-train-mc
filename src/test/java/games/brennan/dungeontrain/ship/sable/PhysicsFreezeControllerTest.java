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
 * #646 (eager unfreeze, lazy freeze after a grace window), including the unsettled-carriage exemption
 * that keeps the placement tracker's nudges observable. Registry-free booleans/ints, so no
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
        assertEquals(Action.UNFREEZE, decide(true, false, 0, true));
        assertEquals(Action.UNFREEZE, decide(true, false, FREEZE_GRACE_TICKS * 5, true));
    }

    @Test
    @DisplayName("active + not frozen → hold (nothing to do)")
    void activeUnfrozen_holds() {
        assertEquals(Action.NONE, decide(true, false, 0, false));
        assertEquals(Action.NONE, decide(true, false, 999, false));
    }

    @Test
    @DisplayName("inactive + not frozen, below grace → hold (don't freeze prematurely)")
    void inactiveBelowGrace_holds() {
        assertEquals(Action.NONE, decide(false, false, 0, false));
        assertEquals(Action.NONE, decide(false, false, FREEZE_GRACE_TICKS - 1, false));
    }

    @Test
    @DisplayName("inactive + not frozen, at/above grace → FREEZE")
    void inactiveAtGrace_freezes() {
        assertEquals(Action.FREEZE, decide(false, false, FREEZE_GRACE_TICKS, false));
        assertEquals(Action.FREEZE, decide(false, false, FREEZE_GRACE_TICKS + 50, false));
    }

    @Test
    @DisplayName("inactive + already frozen → hold (stay frozen, no churn)")
    void inactiveFrozen_stays() {
        assertEquals(Action.NONE, decide(false, false, 0, true));
        assertEquals(Action.NONE, decide(false, false, FREEZE_GRACE_TICKS * 3, true));
    }

    @Test
    @DisplayName("unplaced + inactive past grace → never FREEZE (the placement tracker must see its nudges land)")
    void unplacedInactive_neverFreezes() {
        assertEquals(Action.NONE, decide(false, true, FREEZE_GRACE_TICKS, false));
        assertEquals(Action.NONE, decide(false, true, FREEZE_GRACE_TICKS * 10, false));
    }

    @Test
    @DisplayName("unplaced + already frozen → UNFREEZE immediately, even while inactive")
    void unplacedFrozen_unfreezesEagerly() {
        assertEquals(Action.UNFREEZE, decide(false, true, 0, true));
        assertEquals(Action.UNFREEZE, decide(false, true, FREEZE_GRACE_TICKS * 10, true));
    }

    @Test
    @DisplayName("once placed, an inactive carriage freezes again (the exemption is not sticky)")
    void placedAfterUnplaced_freezesAgain() {
        assertEquals(Action.FREEZE, decide(false, false, FREEZE_GRACE_TICKS, false));
    }

    @Test
    void anchorsToKeepTicking_wholeTrainWhileAnyGroupIsUnsettled() {
        java.util.Set<Integer> all = java.util.Set.of(0, -3, -6, -9, -12);
        // Nothing settling → nothing exempt on this account.
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of(),
            PhysicsFreezeController.anchorsToKeepTicking(java.util.Set.of(), all));
        // One unsettled group anywhere → every group keeps ticking (a parked body two strides away
        // can sit inside the spawn zone).
        org.junit.jupiter.api.Assertions.assertEquals(all,
            PhysicsFreezeController.anchorsToKeepTicking(java.util.Set.of(-12), all));
        // An unsettled anchor not yet in the visible set is still included.
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of(0, -3, -6, -9, -12, -15),
            PhysicsFreezeController.anchorsToKeepTicking(java.util.Set.of(-15), all));
    }
}
