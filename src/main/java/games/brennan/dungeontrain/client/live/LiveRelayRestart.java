package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.RestartNotice;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The relay told the live room it is restarting (dp-relay live-restart.js): "The server is restarting.
 * The chat and stream will resume in roughly N min", then "Restarting in 3", 2, 1, and after the restart
 * "back". This holds what this client makes of that.
 *
 * <ul>
 *   <li><b>The window.</b> From the notice until the relay's estimate plus {@link #GRACE_MS}, a failed
 *       relay request is the restart, not the stream ending: {@link LiveStatusPoller} keeps the last
 *       status (same streamer, same playlist) and {@link LiveChatBridge} stays in the room, so Live Eye
 *       and TV holders carry on with the same streamer when the relay is back. The relay holds the
 *       channel for that streamer meanwhile. The "back" line closes the window.</li>
 *   <li><b>The countdown.</b> Chat is polled every 3 s; for {@link #FAST_MS} after the notice it is
 *       polled every second, so 3, 2, 1 arrive a second apart.</li>
 * </ul>
 * Times here are this client's clock; the estimate is the relay's {@code resumeBy} minus the line's own
 * relay timestamp, so the two clocks never need to agree.
 */
public final class LiveRelayRestart {

    /** After the estimate, how long a failed request still reads as the restart. Matches the relay's hold. */
    static final long GRACE_MS = 5 * 60 * 1000L;
    /** Notice → last countdown line is ~8 s on the relay (6 s lead + 3, 2, 1); a little slack on top. */
    static final long FAST_MS = 15_000L;
    static final long FAST_POLL_MS = 1000L;
    /** The relay's first countdown number (COUNTDOWN_FROM): it reads "Restarting in 3", the rest just "2", "1". */
    static final int COUNTDOWN_FROM = 3;
    static final long DEFAULT_ESTIMATE_MS = 5 * 60 * 1000L;

    public static final String NOTICE_KEY = "chat.dungeontrain.live_chat.restart.notice";
    public static final String COUNTDOWN_KEY = "chat.dungeontrain.live_chat.restart.countdown";
    public static final String RESUMED_KEY = "chat.dungeontrain.live_chat.restart.resumed";

    private static volatile long windowEndMs;
    private static volatile long fastUntilMs;

    private LiveRelayRestart() {}

    /** A restart line came in at {@code now} (this client's clock); {@code lineTs} is its relay timestamp. */
    public static void note(RestartNotice n, long lineTs, long now) {
        if (n.resumed()) {
            windowEndMs = 0L;
            fastUntilMs = 0L;
        } else if (n.countdown() > 0) {
            if (n.countdown() == 1) fastUntilMs = 0L; // the last one: back to the usual pace
            if (windowEndMs <= now) windowEndMs = now + DEFAULT_ESTIMATE_MS + GRACE_MS; // joined mid-countdown
        } else if (n.resumeBy() > 0) {
            windowEndMs = now + estimateMs(n, lineTs) + GRACE_MS;
            fastUntilMs = now + FAST_MS;
        }
    }

    /** Whether a failed relay request now is the announced restart. */
    public static boolean inWindow(long now) {
        return now < windowEndMs;
    }

    /** The chat poll interval: every second through the countdown, else {@code normal}. */
    public static long chatPollMs(long now, long normal) {
        return now < fastUntilMs ? FAST_POLL_MS : normal;
    }

    /** Logout / world change. */
    public static void reset() {
        windowEndMs = 0L;
        fastUntilMs = 0L;
    }

    /** How long the relay expects to be gone, from its own two timestamps; 5 min when it can't tell. */
    static long estimateMs(RestartNotice n, long lineTs) {
        if (n.resumeBy() <= 0 || lineTs <= 0 || n.resumeBy() <= lineTs) return DEFAULT_ESTIMATE_MS;
        return n.resumeBy() - lineTs;
    }

    /** The estimate in whole minutes, at least 1, as the relay rounded it. */
    static long minutes(RestartNotice n, long lineTs) {
        return Math.max(1L, Math.round(estimateMs(n, lineTs) / 60_000.0));
    }

    /** The line's text in the player's language: {@code [Live] The server is restarting…}. */
    public static MutableComponent line(RestartNotice n, long lineTs) {
        return LiveChatRules.systemLine(body(n, lineTs).withStyle(ChatFormatting.YELLOW));
    }

    static MutableComponent body(RestartNotice n, long lineTs) {
        if (n.resumed()) return Component.translatable(RESUMED_KEY);
        if (n.countdown() > 0) {
            return n.countdown() >= COUNTDOWN_FROM
                ? Component.translatable(COUNTDOWN_KEY, n.countdown())
                : Component.literal(Integer.toString(n.countdown()));
        }
        return Component.translatable(NOTICE_KEY, minutes(n, lineTs));
    }
}
