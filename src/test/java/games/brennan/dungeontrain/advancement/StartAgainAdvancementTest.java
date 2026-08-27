package games.brennan.dungeontrain.advancement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Command classification for {@link StartAgainAdvancement#isRevokeEverything(String)} — the
 * "did they wipe the slate?" test that arms "It's Not That Simple". String-based on purpose,
 * so it is testable without a Brigadier parse tree (same shape as
 * {@link games.brennan.dungeontrain.cheat.CommandAllowlist}).
 */
class StartAgainAdvancementTest {

    @Test
    @DisplayName("revoke-everything spellings all match")
    void revokeEverythingMatches() {
        assertTrue(StartAgainAdvancement.isRevokeEverything("/advancement revoke @s everything"));
        assertTrue(StartAgainAdvancement.isRevokeEverything("advancement revoke @s everything"));
        assertTrue(StartAgainAdvancement.isRevokeEverything("  /advancement   revoke   @s   everything  "));
        assertTrue(StartAgainAdvancement.isRevokeEverything("/minecraft:advancement revoke @s everything"));
        assertTrue(StartAgainAdvancement.isRevokeEverything("/Advancement REVOKE @s EVERYTHING"));
        assertTrue(StartAgainAdvancement.isRevokeEverything("advancement revoke Brennan everything"));
    }

    @Test
    @DisplayName("Granting is not wiping")
    void grantDoesNotMatch() {
        assertFalse(StartAgainAdvancement.isRevokeEverything("/advancement grant @s everything"));
        assertFalse(StartAgainAdvancement.isRevokeEverything("/advancement set @s everything"));
    }

    @Test
    @DisplayName("A partial revoke is not a wipe")
    void partialRevokeDoesNotMatch() {
        assertFalse(StartAgainAdvancement.isRevokeEverything(
            "/advancement revoke @s only dungeontrain:dungeon_train/completionist"));
        assertFalse(StartAgainAdvancement.isRevokeEverything(
            "/advancement revoke @s from minecraft:story/root"));
        assertFalse(StartAgainAdvancement.isRevokeEverything(
            "/advancement revoke @s through minecraft:story/root"));
    }

    @Test
    @DisplayName("Other commands and junk input never match")
    void otherInputDoesNotMatch() {
        assertFalse(StartAgainAdvancement.isRevokeEverything("/give @s everything"));
        assertFalse(StartAgainAdvancement.isRevokeEverything("/advancement revoke"));
        assertFalse(StartAgainAdvancement.isRevokeEverything("/advancement"));
        assertFalse(StartAgainAdvancement.isRevokeEverything("/"));
        assertFalse(StartAgainAdvancement.isRevokeEverything("   "));
        assertFalse(StartAgainAdvancement.isRevokeEverything(""));
        assertFalse(StartAgainAdvancement.isRevokeEverything(null));
    }
}
