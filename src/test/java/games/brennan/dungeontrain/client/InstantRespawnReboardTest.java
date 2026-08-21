package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the gate in {@link InstantRespawnReboard#shouldStartNewWorld} — the decision
 * that turns vanilla's Immediate Respawn game rule into a fresh Dungeon Train world.
 *
 * <p>Each {@code false} case here is a way the reboard could fire when it must not:
 * off the client thread (the packet handler runs twice per death), for another
 * player's death on a LAN world, while the narrative death screen is about to take
 * the run end itself, on a remote server where no world can be created, or a second
 * time for a death already being reboarded.</p>
 */
final class InstantRespawnReboardTest {

    /** clientThread, localPlayerDied, showDeathScreen, singleplayer, launchScheduled. */
    private static boolean decide(boolean clientThread, boolean localPlayerDied,
                                  boolean showDeathScreen, boolean singleplayer,
                                  boolean launchScheduled) {
        return InstantRespawnReboard.shouldStartNewWorld(
                clientThread, localPlayerDied, showDeathScreen, singleplayer, launchScheduled);
    }

    @Test
    @DisplayName("immediate respawn + own death + singleplayer + first pass → new world")
    void firesOnImmediateRespawnDeath() {
        assertTrue(decide(true, true, false, true, false));
    }

    @Test
    @DisplayName("netty-thread pass declines; the client-thread re-dispatch acts")
    void declinesOffClientThread() {
        assertFalse(decide(false, true, false, true, false));
    }

    @Test
    @DisplayName("another player's death on a LAN world is ignored")
    void ignoresOtherPlayersDeaths() {
        assertFalse(decide(true, false, false, true, false));
    }

    @Test
    @DisplayName("rule off (death screen coming) leaves the narrative screen in charge")
    void leavesNormalDeathsToTheDeathScreen() {
        assertFalse(decide(true, true, true, true, false));
    }

    @Test
    @DisplayName("remote server keeps vanilla immediate respawn — no world to create")
    void doesNotFireOnRemoteServers() {
        assertFalse(decide(true, true, false, false, false));
    }

    @Test
    @DisplayName("a reboard already queued is never queued twice")
    void doesNotDoubleFire() {
        assertFalse(decide(true, true, false, true, true));
    }
}
