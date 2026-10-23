package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Challenges tab is single-player only: who the multiplayer gate refuses, and who it lets through. */
final class MultiplayerChallengeGateTest {

    private static final ResourceLocation ROOT = MultiplayerChallengeGate.TAB_ROOT;
    private static final ResourceLocation APPLE = rl("dungeon_train/apple_a_day");

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath("dungeontrain", path);
    }

    @Test
    @DisplayName("the tab root is a shipped advancement, so a rename can't silently open the gate")
    void tabRootExists() {
        assertTrue(Files.isRegularFile(RepoPaths.advancements().resolve(ROOT.getPath() + ".json")));
    }

    @Test
    @DisplayName("a challenge earned live in multiplayer is refused")
    void challengeInMultiplayerIsBlocked() {
        assertTrue(MultiplayerChallengeGate.blocks(true, false, ROOT, APPLE));
    }

    @Test
    @DisplayName("single-player, replays and the tab root itself pass")
    void allowedCases() {
        assertFalse(MultiplayerChallengeGate.blocks(false, false, ROOT, APPLE));
        assertFalse(MultiplayerChallengeGate.blocks(true, true, ROOT, APPLE));
        assertFalse(MultiplayerChallengeGate.blocks(true, false, ROOT, ROOT));
    }

    @Test
    @DisplayName("other tabs are untouched in multiplayer")
    void otherTabsPass() {
        assertFalse(MultiplayerChallengeGate.blocks(true, false, rl("dungeon_train/root"), rl("dungeon_train/carts_100")));
        assertFalse(MultiplayerChallengeGate.blocks(true, false, null, APPLE));
    }

    @Test
    @DisplayName("the faded set is the tab's members, never the root or another tab")
    void membership() {
        assertTrue(MultiplayerChallengeGate.isMember(ROOT, APPLE));
        assertFalse(MultiplayerChallengeGate.isMember(ROOT, ROOT));
        assertFalse(MultiplayerChallengeGate.isMember(rl("dungeon_train/root"), rl("dungeon_train/carts_100")));
    }
}
