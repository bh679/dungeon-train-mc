package games.brennan.dungeontrain;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies dev-vs-live Discord relay capability selection (see {@link DungeonTrain#relayBaseUrlForBranch}):
 * only a {@code main} build reports to the live community feed; every other branch value (feature
 * branches, worktrees, detached-HEAD short SHA, the {@code "?"} build-time fallback) reports to the dev
 * channel — so dev/test runs can never post to the live feed by accident.
 */
class DungeonTrainRelayRoutingTest {

    // Non-secret capability segments (they ship in the jar; see DungeonTrain's relay constants).
    private static final String LIVE_CAP = "adc3dc432f437e9401092c143dec86767dd06c2a5d94f48f";
    private static final String DEV_CAP = "0e908e2d067e81eb3e31e43e6c4e337182db24232994dc25";

    @Test
    void mainBuildReportsToLiveFeed() {
        String url = DungeonTrain.relayBaseUrlForBranch("main");
        assertTrue(url.endsWith("/" + LIVE_CAP), "main must use the live relay cap, was: " + url);
    }

    @Test
    void featureBranchReportsToDevChannel() {
        assertTrue(DungeonTrain.relayBaseUrlForBranch("dev/discord-dev-channel").endsWith("/" + DEV_CAP));
        assertTrue(DungeonTrain.relayBaseUrlForBranch("claude/some-worktree-slug").endsWith("/" + DEV_CAP));
    }

    @Test
    void misdetectedBranchFailsSafeToDev() {
        // Detached HEAD bakes a short SHA; total git-detection failure bakes "?". Neither is "main",
        // so both must route to the dev channel — never live.
        assertTrue(DungeonTrain.relayBaseUrlForBranch("a1b2c3d").endsWith("/" + DEV_CAP));
        assertTrue(DungeonTrain.relayBaseUrlForBranch("?").endsWith("/" + DEV_CAP));
        assertNotEquals(DungeonTrain.relayBaseUrlForBranch("main"),
                DungeonTrain.relayBaseUrlForBranch("?"));
    }
}
