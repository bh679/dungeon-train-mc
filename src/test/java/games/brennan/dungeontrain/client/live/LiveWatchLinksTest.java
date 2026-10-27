package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void theAlsoOnLineLinksTikTokOnlyForAnOfficialTikTokUrl() {
        String url = "https://www.tiktok.com/@dungeontrain/live";
        Component line = LiveWatchLinks.alsoOnLine(url);
        assertEquals("chat.dungeontrain.live.also_on", key(line));
        Object[] args = ((net.minecraft.network.chat.contents.TranslatableContents) line.getContents()).getArgs();
        assertEquals(1, args.length);
        Component tiktok = (Component) args[0];
        assertEquals("chat.dungeontrain.live.link.tiktok", key(tiktok));
        assertEquals(net.minecraft.ChatFormatting.AQUA.getColor(), tiktok.getStyle().getColor().getValue());
        assertTrue(tiktok.getStyle().isUnderlined());
        assertEquals(net.minecraft.network.chat.ClickEvent.Action.OPEN_URL, tiktok.getStyle().getClickEvent().getAction());
        assertEquals(url, tiktok.getStyle().getClickEvent().getValue());

        assertNull(LiveWatchLinks.alsoOnLine(null));
        assertNull(LiveWatchLinks.alsoOnLine("https://twitch.tv/x"));
        assertNull(LiveWatchLinks.alsoOnLine("http://www.tiktok.com/@dungeontrain/live"));
    }

    private static String key(Component c) {
        return ((net.minecraft.network.chat.contents.TranslatableContents) c.getContents()).getKey();
    }
}
