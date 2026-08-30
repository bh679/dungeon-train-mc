package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deny list that decides whether a death gets the ragdoll animation. Every entry here is a
 * death that must stay INSTANT — a held death that shouldn't have been held leaves the player
 * alive at 0 hearts for a second, which is exactly the bug this list exists to prevent.
 */
class DeathRagdollGateTest {

    /** An ordinary in-run death aboard the train: the only case that gets the animation. */
    private static boolean ordinaryDeath() {
        return DeathRagdollEvents.shouldHold(true, 30, true, false, false, false, false, false);
    }

    @Test
    @DisplayName("an ordinary death is held for the animation")
    void ordinaryDeathIsHeld() {
        assertTrue(ordinaryDeath());
    }

    @Test
    @DisplayName("the config kill switch restores instant deaths")
    void disabledByConfig() {
        assertFalse(DeathRagdollEvents.shouldHold(false, 30, true, false, false, false, false, false));
    }

    @Test
    @DisplayName("a zero-length hold is not a hold")
    void zeroHoldTicks() {
        assertFalse(DeathRagdollEvents.shouldHold(true, 0, true, false, false, false, false, false));
    }

    @Test
    @DisplayName("without the ragdoll mod there is nothing to wait for")
    void modAbsent() {
        assertFalse(DeathRagdollEvents.shouldHold(true, 30, false, false, false, false, false, false));
    }

    @Test
    @DisplayName("spectator and creative players have no body to ragdoll")
    void spectatorOrCreative() {
        assertFalse(DeathRagdollEvents.shouldHold(true, 30, true, true, false, false, false, false));
    }

    @Test
    @DisplayName("builder worlds keep the vanilla death screen and the vanilla death")
    void builderWorld() {
        assertFalse(DeathRagdollEvents.shouldHold(true, 30, true, false, true, false, false, false));
    }

    @Test
    @DisplayName("abandoning a run from the pause menu ends it immediately")
    void abandonRun() {
        assertFalse(DeathRagdollEvents.shouldHold(true, 30, true, false, false, true, false, false));
    }

    @Test
    @DisplayName("/kill and the void bypass invulnerability, so they can't be held through")
    void bypassCause() {
        assertFalse(DeathRagdollEvents.shouldHold(true, 30, true, false, false, false, true, false));
    }

    @Test
    @DisplayName("a body below the world would ragdoll into the void forever")
    void belowWorld() {
        assertFalse(DeathRagdollEvents.shouldHold(true, 30, true, false, false, false, false, true));
    }
}
