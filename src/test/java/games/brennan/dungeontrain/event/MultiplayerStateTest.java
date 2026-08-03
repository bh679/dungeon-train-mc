package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared "is this multiplayer?" definition behind both the {@code multiplayer_join} advancement
 * and the world-info telemetry the explorer's play-mode stat is built from. Pure — the predicate
 * takes the three server facts as plain arguments, so no running server is needed.
 */
class MultiplayerStateTest {

    @AfterEach
    void clearStickyFlag() {
        MultiplayerState.reset();
    }

    @Test
    @DisplayName("a solo player on their own integrated server is single-player")
    void soloIsSinglePlayer() {
        assertFalse(MultiplayerState.multiplayerJoin(false, true, 1));
    }

    @Test
    @DisplayName("a dedicated server is multiplayer even with one player on it")
    void dedicatedIsMultiplayer() {
        assertTrue(MultiplayerState.multiplayerJoin(true, false, 1));
    }

    @Test
    @DisplayName("a guest joining someone else's world is multiplayer (not the singleplayer owner)")
    void guestIsMultiplayer() {
        assertTrue(MultiplayerState.multiplayerJoin(false, false, 1),
                "a non-owner join is multiplayer on its own, before a second player is counted");
    }

    @Test
    @DisplayName("the LAN host's own world becomes multiplayer once a second player is connected")
    void secondPlayerMakesLanHostMultiplayer() {
        assertTrue(MultiplayerState.multiplayerJoin(false, true, 2));
    }

    @Test
    @DisplayName("the flag is sticky for the server run: it survives the guest leaving")
    void stickyAcrossTheRun() {
        // No server instance here — drive the sticky state through the predicate the same way
        // observeJoin does, then assert the flag holds until reset (server stop).
        assertFalse(MultiplayerState.isMultiplayer(null), "a fresh run starts single-player");
        assertTrue(MultiplayerState.multiplayerJoin(false, true, 2), "guest joins → multiplayer");
        MultiplayerState.markMultiplayer();
        assertTrue(MultiplayerState.isMultiplayer(null), "still multiplayer after the guest leaves");
    }

    @Test
    @DisplayName("a server stop resets it, so a later solo run reports single-player again")
    void resetOnServerStop() {
        MultiplayerState.markMultiplayer();
        MultiplayerState.reset();
        assertFalse(MultiplayerState.isMultiplayer(null));
    }
}
