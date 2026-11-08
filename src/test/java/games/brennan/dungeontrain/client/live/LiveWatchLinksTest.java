package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LiveWatchLinksTest {

    @Test
    void mainBuildLinksTwitchAndTheLivePage() {
        assertEquals("https://twitch.tv/brennanhattongames", LiveWatchLinks.twitchUrlForBranch("main"));
        assertEquals("https://dungeon.training/live/", LiveWatchLinks.tvUrlForBranch("main"));
    }

    @Test
    void devBuildLinksOnlyTheDevPage() {
        for (String branch : new String[] {"dev/some-feature", "?", ""}) {
            assertNull(LiveWatchLinks.twitchUrlForBranch(branch), branch);
            assertEquals("https://dungeon.training/livedev/", LiveWatchLinks.tvUrlForBranch(branch), branch);
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
        assertEquals("chat.dungeontrain.live.watch_link_own", key(LiveWatchLinks.line("main", on)));
        assertEquals("chat.dungeontrain.live.watch_link", key(LiveWatchLinks.line("main", off)));
        assertEquals("chat.dungeontrain.live.watch_link", key(LiveWatchLinks.line("main", null)));
        assertEquals("chat.dungeontrain.live.watch_link_own", key(LiveWatchLinks.line("dev/x", on)));
        assertEquals("chat.dungeontrain.live.watch_link", key(LiveWatchLinks.line("dev/x", null)));
    }

    @Test
    void mainBuildShowsTvTwitchYouTubeAndKickInThatOrder() {
        List<LiveWatchLinks.Destination> d = LiveWatchLinks.destinations("main");
        assertEquals(List.of(LiveChatRules.Icon.TV, LiveChatRules.Icon.TWITCH, LiveChatRules.Icon.YOUTUBE, LiveChatRules.Icon.KICK),
            d.stream().map(LiveWatchLinks.Destination::icon).toList());
        assertEquals(List.of(LiveWatchLinks.TV_URL, LiveWatchLinks.TWITCH_URL, LiveWatchLinks.YOUTUBE_URL, LiveWatchLinks.KICK_URL),
            d.stream().map(LiveWatchLinks.Destination::url).toList());
    }

    @Test
    void devBuildShowsOnlyTheDevPage() {
        assertEquals(List.of(new LiveWatchLinks.Destination(LiveChatRules.Icon.TV, "chat.dungeontrain.live.link.tv", LiveWatchLinks.DEV_TV_URL)),
            LiveWatchLinks.destinations("dev/x"));
    }

    @Test
    void eachLogoIsAClickableGlyphInTheIconFont() {
        Component row = LiveWatchLinks.iconRow(LiveWatchLinks.destinations("main"));
        List<Component> icons = row.getSiblings().stream().filter(c -> c.getStyle().getClickEvent() != null).toList();
        assertEquals(4, icons.size());
        for (Component icon : icons) {
            assertEquals(LiveChatRules.ICON_FONT, icon.getStyle().getFont());
            assertEquals(ClickEvent.Action.OPEN_URL, icon.getStyle().getClickEvent().getAction());
        }
        assertEquals(String.valueOf(LiveChatRules.Icon.YOUTUBE.glyph), icons.get(2).getString());
        assertEquals(LiveWatchLinks.YOUTUBE_URL, icons.get(2).getStyle().getClickEvent().getValue());
    }

    private static String key(Component c) {
        return ((net.minecraft.network.chat.contents.TranslatableContents) c.getContents()).getKey();
    }
}
