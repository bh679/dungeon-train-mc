package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import org.jetbrains.annotations.Nullable;
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
        return line(null);
    }

    /**
     * The line for this build; with the streamer's own channel ({@code own}, their linked Twitch, when
     * they opted in to the restream) it ends "…and on your channel [twitch.tv/x]".
     */
    static Component line(@Nullable LiveFeedClient.Twitch own) {
        return line(VersionInfo.BRANCH, own);
    }

    static Component line(String branch, @Nullable LiveFeedClient.Twitch own) {
        String twitch = twitchUrlForBranch(branch);
        MutableComponent tv = link("chat.dungeontrain.live.link.tv", tvUrlForBranch(branch));
        boolean ownChannel = own != null && own.restream();
        MutableComponent line;
        if (twitch == null) {
            line = ownChannel
                ? Component.translatable("chat.dungeontrain.live.watch_link_own", tv, ownLink(own))
                : Component.translatable("chat.dungeontrain.live.watch_link", tv);
        } else {
            MutableComponent tw = link("chat.dungeontrain.live.link.twitch", twitch);
            line = ownChannel
                ? Component.translatable("chat.dungeontrain.live.watch_links_own", tw, tv, ownLink(own))
                : Component.translatable("chat.dungeontrain.live.watch_links", tw, tv);
        }
        return line.withStyle(ChatFormatting.GREEN);
    }

    /** "twitch.tv/login" as a link. */
    private static MutableComponent ownLink(LiveFeedClient.Twitch own) {
        return Component.literal("twitch.tv/" + own.login())
            .withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, own.url()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(own.url()))));
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
