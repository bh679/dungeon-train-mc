package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.PassengerDefenceEvents.PlayerStrike;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static games.brennan.dungeontrain.event.PassengerDefenceEvents.DEFENCE_WINDOW_TICKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for <b>That's What Friends Are For</b> ({@link PassengerDefenceEvents}):
 * whether a dying mob was attacking a passenger, and which player defended it.
 */
final class PassengerDefenceTest {

    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long NOW = 10_000L;

    @Test
    @DisplayName("live target on a PlayerMob counts with no remembered aggression")
    void liveTarget() {
        assertTrue(PassengerDefenceEvents.wasAttackingPassenger(true, null, NOW));
    }

    @Test
    @DisplayName("target lost (fell into the void / retargeted the player) but recent aggression counts")
    void lostTarget_recentAggression() {
        assertTrue(PassengerDefenceEvents.wasAttackingPassenger(false, NOW - 120L, NOW));
        assertTrue(PassengerDefenceEvents.wasAttackingPassenger(false, NOW - DEFENCE_WINDOW_TICKS, NOW));
    }

    @Test
    @DisplayName("aggression past the window, or none at all, does not count")
    void staleOrNoAggression() {
        assertFalse(PassengerDefenceEvents.wasAttackingPassenger(false, NOW - DEFENCE_WINDOW_TICKS - 1, NOW));
        assertFalse(PassengerDefenceEvents.wasAttackingPassenger(false, null, NOW));
    }

    @Test
    @DisplayName("a player resolved as the killer is the defender")
    void resolvedPlayerKiller() {
        assertEquals(Optional.of(P), PassengerDefenceEvents.defender(P, false, null, NOW));
        assertEquals(Optional.of(P),
            PassengerDefenceEvents.defender(P, true, new PlayerStrike(Q, NOW - 5), NOW));
    }

    @Test
    @DisplayName("void death credited to the PlayerMob still goes to the player who shoved it")
    void indirectDeath_lastPlayerStrike() {
        assertEquals(Optional.of(P),
            PassengerDefenceEvents.defender(null, true, new PlayerStrike(P, NOW - 200), NOW));
    }

    @Test
    @DisplayName("a PlayerMob's own direct kill is not the player's")
    void directNonPlayerKill() {
        assertEquals(Optional.empty(),
            PassengerDefenceEvents.defender(null, false, new PlayerStrike(P, NOW - 5), NOW));
    }

    @Test
    @DisplayName("an old player strike, or none, credits nobody")
    void stalePlayerStrike() {
        assertEquals(Optional.empty(), PassengerDefenceEvents.defender(
            null, true, new PlayerStrike(P, NOW - DEFENCE_WINDOW_TICKS - 1), NOW));
        assertEquals(Optional.empty(), PassengerDefenceEvents.defender(null, true, null, NOW));
    }
}
