package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.InstantRespawnReboard.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the gate in {@link InstantRespawnReboard#decide} — the decision that turns
 * vanilla's Immediate Respawn game rule into a fresh Dungeon Train world.
 *
 * <p>Each {@code VANILLA} case here is a way the reboard could fire when it must
 * not: off the client thread (the packet handler runs twice per death), for another
 * player's death on a LAN world, while the narrative death screen is about to take
 * the run end itself, on a remote server where no world can be created, or a second
 * time for a death already being reboarded.</p>
 *
 * <p>The {@code DEATH_SCREEN} cases pin the other exception: the player abandoned
 * the run from the pause menu, so the recap opens instead of a new world.</p>
 */
final class InstantRespawnReboardTest {

    /** clientThread, localPlayerDied, showDeathScreen, singleplayer, launchScheduled, abandonRequested. */
    private static Outcome decide(boolean clientThread, boolean localPlayerDied,
                                  boolean showDeathScreen, boolean singleplayer,
                                  boolean launchScheduled, boolean abandonRequested) {
        return InstantRespawnReboard.decide(clientThread, localPlayerDied, showDeathScreen,
                singleplayer, launchScheduled, abandonRequested);
    }

    @Test
    @DisplayName("immediate respawn + own death + singleplayer + first pass → new world")
    void firesOnImmediateRespawnDeath() {
        assertEquals(Outcome.NEW_WORLD, decide(true, true, false, true, false, false));
    }

    @Test
    @DisplayName("netty-thread pass declines; the client-thread re-dispatch acts")
    void declinesOffClientThread() {
        assertEquals(Outcome.VANILLA, decide(false, true, false, true, false, false));
    }

    @Test
    @DisplayName("another player's death on a LAN world is ignored")
    void ignoresOtherPlayersDeaths() {
        assertEquals(Outcome.VANILLA, decide(true, false, false, true, false, false));
    }

    @Test
    @DisplayName("rule off (death screen coming) leaves the narrative screen in charge")
    void leavesNormalDeathsToTheDeathScreen() {
        assertEquals(Outcome.VANILLA, decide(true, true, true, true, false, false));
    }

    @Test
    @DisplayName("remote server keeps vanilla immediate respawn — no world to create")
    void doesNotFireOnRemoteServers() {
        assertEquals(Outcome.VANILLA, decide(true, true, false, false, false, false));
    }

    @Test
    @DisplayName("a reboard already queued is never queued twice")
    void doesNotDoubleFire() {
        assertEquals(Outcome.VANILLA, decide(true, true, false, true, true, false));
    }

    @Test
    @DisplayName("abandoned run + immediate respawn → the death screen, not a new world")
    void abandonShowsTheDeathScreen() {
        assertEquals(Outcome.DEATH_SCREEN, decide(true, true, false, true, false, true));
    }

    @Test
    @DisplayName("abandon never reboards automatically, even on the very first pass")
    void abandonNeverStartsAWorldItself() {
        // Same inputs as firesOnImmediateRespawnDeath apart from the abandon flag.
        assertEquals(Outcome.DEATH_SCREEN, decide(true, true, false, true, false, true));
        assertEquals(Outcome.NEW_WORLD, decide(true, true, false, true, false, false));
    }

    @Test
    @DisplayName("abandon with the rule off is left to vanilla — it opens the screen itself")
    void abandonWithRuleOffIsVanilla() {
        assertEquals(Outcome.VANILLA, decide(true, true, true, true, false, true));
    }

    @Test
    @DisplayName("an expired or never-set abandon flag still reboards")
    void staleAbandonDoesNotBlockTheReboard() {
        assertEquals(Outcome.NEW_WORLD, decide(true, true, false, true, false, false));
    }
}
