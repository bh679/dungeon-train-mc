package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

/**
 * A streamer's Twitch as a link in Live chat, for {@link LiveChatBridge}: the relay's line when they come
 * on air ({@code live-channel-link.js}), and a line of this client's own on joining their room through a
 * TV. A viewer who joins just as the stream starts could be due both, so one that repeats the channel
 * they were just linked is dropped.
 *
 * <p>The join line waits up to {@link #JOIN_WINDOW_MS} after joining, as the status may not have answered
 * yet. After that a stream coming on air is the relay's line to tell.</p>
 *
 * <p>Client tick thread only.</p>
 */
final class LiveChannelLink {

    static final String GO_LIVE_KEY = "chat.dungeontrain.live.channel.live";
    static final String JOIN_KEY = "chat.dungeontrain.live.channel.join";
    static final long JOIN_WINDOW_MS = 10_000;
    /** The relay's line lands within seconds of a stream showing as live. */
    static final long REPEAT_MS = 120_000;

    private long joinUntilMs;
    @Nullable private String linkedLogin;
    private long linkedMs;

    /** This client just entered a room as a viewer: link its streamer, once the status names them. */
    void onJoined(long now) {
        joinUntilMs = now + JOIN_WINDOW_MS;
    }

    /** Left the room: nothing is due, nothing to repeat. */
    void reset() {
        joinUntilMs = 0;
        linkedLogin = null;
        linkedMs = 0;
    }

    /** The join line due for {@code stream} (the room's stream as the status has it), or null; given once. */
    @Nullable
    Component joinLine(@Nullable LiveFeedClient.Status stream, long now) {
        if (joinUntilMs == 0) return null;
        if (now > joinUntilMs) {
            joinUntilMs = 0;
            return null;
        }
        if (stream == null || !stream.live() || stream.ended() || stream.twitchLogin() == null) return null;
        joinUntilMs = 0;
        String login = stream.twitchLogin().toLowerCase(java.util.Locale.ROOT);
        remember(login, now);
        return joinLine(stream.streamer(), login);
    }

    /** Whether the relay's line for {@code channel} is news to this reader. */
    boolean showRelayLine(LiveFeedClient.LinkedChannel channel, long now) {
        boolean repeat = channel.login().equals(linkedLogin) && now - linkedMs < REPEAT_MS;
        if (repeat) return false;
        // A stream the join line is still waiting on has just been linked by this line.
        joinUntilMs = 0;
        remember(channel.login(), now);
        return true;
    }

    private void remember(String login, long now) {
        linkedLogin = login;
        linkedMs = now;
    }

    static Component goLiveLine(LiveFeedClient.LinkedChannel channel) {
        return line(GO_LIVE_KEY, channel.name(), channel.login());
    }

    static Component joinLine(@Nullable String streamer, String login) {
        return line(JOIN_KEY, streamer, login);
    }

    private static Component line(String key, @Nullable String name, String login) {
        String shown = LiveChatRules.clean(name, LiveChatRules.MAX_AUTHOR);
        return LiveChatRules.systemLine(Component.translatable(key, shown.isBlank() ? login : shown, link(login))
            .withStyle(ChatFormatting.GRAY));
    }

    /** "twitch.tv/login" as a link; the login is checked by {@code LiveFeedClient} before it gets here. */
    private static MutableComponent link(String login) {
        String url = "https://twitch.tv/" + login;
        return Component.literal("twitch.tv/" + login)
            .withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url))));
    }
}
