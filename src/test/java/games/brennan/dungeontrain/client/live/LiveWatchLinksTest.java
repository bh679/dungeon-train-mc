package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LiveWatchLinksTest {

    @Test
    void mainBuildLinksTwitchAndTheLivePage() {
        assertEquals("https://twitch.tv/brennanhattongames", LiveWatchLinks.twitchUrlForBranch("main"));
        assertEquals("https://brennan.games/dungeontrain/live/", LiveWatchLinks.tvUrlForBranch("main"));
    }

    @Test
    void devBuildLinksOnlyTheDevPage() {
        for (String branch : new String[] {"dev/some-feature", "?", ""}) {
            assertNull(LiveWatchLinks.twitchUrlForBranch(branch), branch);
            assertEquals("https://brennan.games/dungeontrain/livedev/", LiveWatchLinks.tvUrlForBranch(branch), branch);
        }
    }

    @Test
    void nullBranchIsADevBuild() {
        assertNull(LiveWatchLinks.twitchUrlForBranch(null));
        assertEquals(LiveWatchLinks.DEV_TV_URL, LiveWatchLinks.tvUrlForBranch(null));
    }
}
