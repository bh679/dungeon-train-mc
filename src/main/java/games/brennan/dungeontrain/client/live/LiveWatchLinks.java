package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.VersionInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * The "You are live on [Twitch] &amp; [DungeonTrain TV]" line said with the going-live line: where the
 * streamer's audience is watching. Which links follow the cap the stream goes to
 * ({@code DungeonTrain.relayBaseUrlForBranch}): a {@code main} build streams on the live cap, which the
 * relay mirrors to Twitch and the website's live page plays; every other build streams on the dev cap,
 * which only the website's livedev page plays.
 */
final class LiveWatchLinks {

    static final String TWITCH_URL = "https://twitch.tv/brennanhattongames";
    static final String TV_URL = "https://brennan.games/dungeontrain/live/";
    static final String DEV_TV_URL = "https://brennan.games/dungeontrain/livedev/";

    private LiveWatchLinks() {}

    /** Null on a dev-cap build: Twitch mirrors only the live cap. */
    static String twitchUrlForBranch(String branch) {
        return isLiveCap(branch) ? TWITCH_URL : null;
    }

    static String tvUrlForBranch(String branch) {
        return isLiveCap(branch) ? TV_URL : DEV_TV_URL;
    }

    /** The line for this build. */
    static Component line() {
        String branch = VersionInfo.BRANCH;
        String twitch = twitchUrlForBranch(branch);
        MutableComponent tv = link("chat.dungeontrain.live.link.tv", tvUrlForBranch(branch));
        MutableComponent line = twitch == null
            ? Component.translatable("chat.dungeontrain.live.watch_link", tv)
            : Component.translatable("chat.dungeontrain.live.watch_links",
                link("chat.dungeontrain.live.link.twitch", twitch), tv);
        return line.withStyle(ChatFormatting.GREEN);
    }

    private static boolean isLiveCap(String branch) {
        return "main".equals(branch);
    }

    private static MutableComponent link(String key, String url) {
        return Component.translatable(key)
            .withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url))));
    }
}
