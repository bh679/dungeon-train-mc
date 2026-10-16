package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import net.minecraft.network.chat.Component;
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

    @Test
    void theLineNamesTheStreamersOwnChannelOnlyWithRestreamOn() {
        LiveFeedClient.Twitch on = new LiveFeedClient.Twitch("pat_1", "https://twitch.tv/pat_1", true, false);
        LiveFeedClient.Twitch off = new LiveFeedClient.Twitch("pat_1", "https://twitch.tv/pat_1", false, false);
        assertEquals("chat.dungeontrain.live.watch_links_own", key(LiveWatchLinks.line("main", on)));
        assertEquals("chat.dungeontrain.live.watch_links", key(LiveWatchLinks.line("main", off)));
        assertEquals("chat.dungeontrain.live.watch_links", key(LiveWatchLinks.line("main", null)));
        assertEquals("chat.dungeontrain.live.watch_link_own", key(LiveWatchLinks.line("dev/x", on)));
        assertEquals("chat.dungeontrain.live.watch_link", key(LiveWatchLinks.line("dev/x", null)));
    }

    private static String key(Component c) {
        return ((net.minecraft.network.chat.contents.TranslatableContents) c.getContents()).getKey();
    }
}
