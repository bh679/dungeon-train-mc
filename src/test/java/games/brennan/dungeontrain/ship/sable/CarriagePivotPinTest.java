package games.brennan.dungeontrain.ship.sable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.ship.sable.CarriagePivotPin.Action;
import static games.brennan.dungeontrain.ship.sable.CarriagePivotPin.PIVOT_EPSILON;
import static games.brennan.dungeontrain.ship.sable.CarriagePivotPin.decideRestore;
import static games.brennan.dungeontrain.ship.sable.CarriagePivotPin.pivotDrifted;
import static games.brennan.dungeontrain.ship.sable.CarriagePivotPin.shouldAttemptPin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for {@link CarriagePivotPin}'s branch matrix — the part most likely to
 * regress, and the only part testable here: {@code Pose3d} / {@code ServerSubLevel} come from
 * sable-companion, which is {@code compileOnly} and absent from the test runtime classpath. Plain
 * booleans and doubles, so no Minecraft bootstrap — same pattern as
 * {@link PhysicsFreezeControllerTest}.
 */
final class CarriagePivotPinTest {

    // ── shouldAttemptPin ────────────────────────────────────────────────

    @Test
    @DisplayName("all three gates clear → attempt the pin")
    void allGatesClear_attempts() {
        assertTrue(shouldAttemptPin(false, true, true));
    }

    @Test
    @DisplayName("inside Sable's physics step → never touch the pivot (Sable didn't move it, and a native re-entry is unsafe)")
    void insidePhysicsStep_skips() {
        assertFalse(shouldAttemptPin(true, true, true));
    }

    @Test
    @DisplayName("not a DT carriage → skip (every non-carriage block change in the world takes this path)")
    void noDriver_skips() {
        assertFalse(shouldAttemptPin(false, false, true));
    }

    @Test
    @DisplayName("driver hasn't captured its spawn pivot yet → skip (nothing to pin to)")
    void noLockedPivot_skips() {
        assertFalse(shouldAttemptPin(false, true, false));
    }

    // ── pivotDrifted ────────────────────────────────────────────────────

    @Test
    @DisplayName("identical pivots have not drifted")
    void identical_noDrift() {
        assertFalse(pivotDrifted(1.5, -2.0, 3.25, 1.5, -2.0, 3.25));
    }

    @Test
    @DisplayName("floating-point round-off below epsilon is not drift")
    void subEpsilon_noDrift() {
        double tiny = PIVOT_EPSILON / 10.0;
        assertFalse(pivotDrifted(1.0 + tiny, 2.0 - tiny, 3.0 + tiny, 1.0, 2.0, 3.0));
    }

    @Test
    @DisplayName("drift on any single axis counts")
    void perAxisDrift_detected() {
        double big = PIVOT_EPSILON * 100.0;
        assertTrue(pivotDrifted(1.0 + big, 2.0, 3.0, 1.0, 2.0, 3.0), "x");
        assertTrue(pivotDrifted(1.0, 2.0 + big, 3.0, 1.0, 2.0, 3.0), "y");
        assertTrue(pivotDrifted(1.0, 2.0, 3.0 + big, 1.0, 2.0, 3.0), "z");
    }

    @Test
    @DisplayName("drift is direction-agnostic — a centre of mass can move either way")
    void negativeDrift_detected() {
        double big = PIVOT_EPSILON * 100.0;
        assertTrue(pivotDrifted(1.0 - big, 2.0, 3.0, 1.0, 2.0, 3.0));
    }

    // ── decideRestore ───────────────────────────────────────────────────

    @Test
    @DisplayName("no drift → SKIP, whatever the freeze/handle state")
    void noDrift_skips() {
        for (boolean frozen : new boolean[] {false, true}) {
            for (boolean handleValid : new boolean[] {false, true}) {
                assertEquals(Action.SKIP, decideRestore(false, frozen, handleValid),
                    "frozen=" + frozen + " handleValid=" + handleValid);
            }
        }
    }

    @Test
    @DisplayName("frozen carriage → PIN_ONLY: DT cancelled Sable's compensating teleport, so the pin alone restores it")
    void frozen_pinsOnly() {
        assertEquals(Action.PIN_ONLY, decideRestore(true, true, true));
        assertEquals(Action.PIN_ONLY, decideRestore(true, true, false));
    }

    @Test
    @DisplayName("unfrozen with no valid handle → PIN_ONLY (there is nothing to teleport)")
    void invalidHandle_pinsOnly() {
        assertEquals(Action.PIN_ONLY, decideRestore(true, false, false));
    }

    @Test
    @DisplayName("unfrozen with a valid handle → PIN_AND_RESTORE: Sable's compensating teleport landed and must be undone too")
    void unfrozenValidHandle_restoresBody() {
        assertEquals(Action.PIN_AND_RESTORE, decideRestore(true, false, true));
    }
}
