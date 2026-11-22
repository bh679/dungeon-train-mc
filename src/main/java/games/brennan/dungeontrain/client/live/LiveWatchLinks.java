package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import org.jetbrains.annotations.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * The "You are live on [Dungeon Train] [Twitch] [YouTube] [Kick]" line said with the going-live line: where the
 * streamer's audience is watching, as a row of clickable logos ({@link LiveChatRules#ICON_FONT} glyphs,
 * hover for the name and address). Which logos follow the cap the stream goes to
 * ({@code DungeonTrain.relayBaseUrlForBranch}): a {@code main} build streams on the live cap, which the
 * relay simulcasts to Twitch, YouTube and Kick and the website's live page plays; every other build
 * streams on the dev cap, which only the website's livedev page plays.
 *
 * <p>Only platforms the push really reaches today are listed. Facebook, TikTok and Instagram join once
 * they can go live (follower gates, per-stream keys). A platform the relay says is paused right now
 * ({@link LiveFeedClient.Outputs}, the claim reply's {@code outputs}) is left out, so nobody is sent to a
 * dead channel; an older relay that says nothing leaves them all in.</p>
 */
final class LiveWatchLinks {

    static final String TWITCH_URL = "https://twitch.tv/brennanhattongames";
    static final String YOUTUBE_URL = "https://www.youtube.com/@dungeontrain";
    static final String KICK_URL = "https://kick.com/dungeontrain";
    static final String TV_URL = "https://dungeon.training/live/";
    static final String DEV_TV_URL = "https://dungeon.training/livedev/";

    /** One logo in the row: its glyph, the lang key of its name (the hover), and where it opens. */
    record Destination(LiveChatRules.Icon icon, String nameKey, String url) {}

    private LiveWatchLinks() {}

    /** Null on a dev-cap build: Twitch mirrors only the live cap. */
    static String twitchUrlForBranch(String branch) {
        return isLiveCap(branch) ? TWITCH_URL : null;
    }

    static String tvUrlForBranch(String branch) {
        return isLiveCap(branch) ? TV_URL : DEV_TV_URL;
    }

    /** The logos for this build, in the order they are shown. */
    static List<Destination> destinations(String branch) {
        return destinations(branch, LiveFeedClient.Outputs.NONE_PAUSED);
    }

    /** The logos for this build, without the platforms {@code outputs} says are paused. The website always stays. */
    static List<Destination> destinations(String branch, LiveFeedClient.Outputs outputs) {
        Destination tv = new Destination(LiveChatRules.Icon.DUNGEON_TRAIN, "chat.dungeontrain.live.link.tv", tvUrlForBranch(branch));
        if (!isLiveCap(branch)) return List.of(tv);
        List<Destination> out = new ArrayList<>();
        out.add(tv);
        if (!outputs.twitchPaused()) out.add(new Destination(LiveChatRules.Icon.TWITCH, "chat.dungeontrain.live.link.twitch", TWITCH_URL));
        if (!outputs.youtubePaused()) out.add(new Destination(LiveChatRules.Icon.YOUTUBE, "chat.dungeontrain.live.link.youtube", YOUTUBE_URL));
        if (!outputs.kickPaused()) out.add(new Destination(LiveChatRules.Icon.KICK, "chat.dungeontrain.live.link.kick", KICK_URL));
        return List.copyOf(out);
    }

    /** The line for this build. */
    static Component line() {
        return line(VersionInfo.BRANCH, null, LiveFeedClient.Outputs.NONE_PAUSED);
    }

    /**
     * The line for this stream's claim: its paused platforms left out, and, with the streamer's own channel
     * (their linked Twitch, when they opted in to the restream and the restream is running) it ends
     * "…and on your channel [twitch.tv/x]".
     */
    static Component line(@Nullable LiveFeedClient.Claim claim) {
        return claim == null ? line() : line(VersionInfo.BRANCH, claim.twitch(), claim.outputs());
    }

    static Component line(String branch, @Nullable LiveFeedClient.Twitch own) {
        return line(branch, own, LiveFeedClient.Outputs.NONE_PAUSED);
    }

    static Component line(String branch, @Nullable LiveFeedClient.Twitch own, LiveFeedClient.Outputs outputs) {
        MutableComponent row = iconRow(destinations(branch, outputs));
        MutableComponent line = own != null && own.restream() && !outputs.restreamPaused()
            ? Component.translatable("chat.dungeontrain.live.watch_link_own", row, ownLink(own))
            : Component.translatable("chat.dungeontrain.live.watch_link", row);
        return line.withStyle(ChatFormatting.GREEN);
    }

    /** The logos, one space apart. */
    static MutableComponent iconRow(List<Destination> destinations) {
        MutableComponent row = Component.empty();
        for (int i = 0; i < destinations.size(); i++) {
            if (i > 0) row.append(" ");
            row.append(icon(destinations.get(i)));
        }
        return row;
    }

    /** One logo: white so the glyph keeps its own colours, clickable, hover "Name" then the address. */
    private static MutableComponent icon(Destination d) {
        Component hover = Component.translatable(d.nameKey()).withStyle(ChatFormatting.AQUA)
            .append(Component.literal("\n" + d.url()).withStyle(ChatFormatting.GRAY));
        return Component.literal(String.valueOf(d.icon().glyph))
            .withStyle(s -> s.withFont(LiveChatRules.ICON_FONT).withColor(ChatFormatting.WHITE)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, d.url()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
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
}
