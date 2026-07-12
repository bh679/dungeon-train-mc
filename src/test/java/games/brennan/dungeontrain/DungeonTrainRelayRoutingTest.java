package games.brennan.dungeontrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private static final String PUBLIC_CAP = "55800db451a785a14030978edf0e352179a160287da24981";

    @Test
    void mainBuildReportsToLiveFeed() {
        String url = DtCore.relayBaseUrlForBranch("main");
        assertTrue(url.endsWith("/" + LIVE_CAP), "main must use the live relay cap, was: " + url);
    }

    @Test
    void featureBranchReportsToDevChannel() {
        assertTrue(DtCore.relayBaseUrlForBranch("dev/discord-dev-channel").endsWith("/" + DEV_CAP));
        assertTrue(DtCore.relayBaseUrlForBranch("claude/some-worktree-slug").endsWith("/" + DEV_CAP));
    }

    @Test
    void manifestRoutesToPublicCapOnlyOnMain() {
        // The public "manifest" death report posts to the dedicated public cap ONLY on a main build;
        // every other branch returns null → it falls through to the build's default cap (dev channel).
        assertTrue(DtCore.manifestWebhookOverrideForBranch("main").endsWith("/" + PUBLIC_CAP + "/hook"),
                "main must route the manifest to the public cap");
        assertNull(DtCore.manifestWebhookOverrideForBranch("dev/some-feature"));
        assertNull(DtCore.manifestWebhookOverrideForBranch("claude/worktree-slug"));
        assertNull(DtCore.manifestWebhookOverrideForBranch("?"));
    }

    @Test
    void surveyResultsCopyRoutesToDedicatedCapOnlyOnMain() {
        // The survey-results copy posts to the dedicated survey-results cap ONLY on a main build;
        // every other branch returns null → the copy falls through to the build's default cap (dev).
        String main = DtCore.surveyResultsWebhookOverrideForBranch("main");
        assertNotNull(main, "main must route the survey copy to a dedicated cap");
        assertTrue(main.endsWith("/hook"), "survey-results cap must target the relay /hook, was: " + main);
        assertNull(DtCore.surveyResultsWebhookOverrideForBranch("dev/some-feature"));
        assertNull(DtCore.surveyResultsWebhookOverrideForBranch("claude/worktree-slug"));
        assertNull(DtCore.surveyResultsWebhookOverrideForBranch("?"));
    }

    @Test
    void surveyLinkGuildIdSplitsMainVsDev() {
        // The jump-link guild id matches the channel the original posted into: live server on main,
        // dev server otherwise (so the link resolves in the right server).
        assertEquals(DtCore.LIVE_GUILD_ID, DtCore.linkGuildIdForBranch("main"));
        assertEquals(DtCore.DEV_GUILD_ID, DtCore.linkGuildIdForBranch("dev/some-feature"));
        assertEquals(DtCore.DEV_GUILD_ID, DtCore.linkGuildIdForBranch("claude/worktree-slug"));
        assertEquals(DtCore.DEV_GUILD_ID, DtCore.linkGuildIdForBranch("?"));
    }

    @Test
    void misdetectedBranchFailsSafeToDev() {
        // Detached HEAD bakes a short SHA; total git-detection failure bakes "?". Neither is "main",
        // so both must route to the dev channel — never live.
        assertTrue(DtCore.relayBaseUrlForBranch("a1b2c3d").endsWith("/" + DEV_CAP));
        assertTrue(DtCore.relayBaseUrlForBranch("?").endsWith("/" + DEV_CAP));
        assertNotEquals(DtCore.relayBaseUrlForBranch("main"),
                DtCore.relayBaseUrlForBranch("?"));
    }
}
