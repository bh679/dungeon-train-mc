package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import org.jetbrains.annotations.Nullable;

/**
 * HD Streams: the picture a stream goes out at once the claim's {@link LiveFeedClient.Video} is applied.
 * The relay's video only ever RAISES a streamer on the default size or above (720p → 1080p, and the bitrate
 * to match); a streamer who chose a smaller Video size keeps theirs, since they picked it for frame rate.
 * The adaptive governor still starts lower on a slow remembered upload, so HD never stalls a weak link.
 */
public final class LiveHdVideo {
    /** The Options → Stream Video size default (720p): at or above it a streamer takes HD. */
    static final int DEFAULT_WIDTH = 1280;

    private LiveHdVideo() { }

    /** Whether {@code video} applies to a streamer whose Video size is {@code configuredWidth}. */
    public static boolean applies(int configuredWidth, @Nullable LiveFeedClient.Video video) {
        return video != null && configuredWidth >= DEFAULT_WIDTH;
    }

    /** The broadcast width: the configured one, raised to the claim's when HD applies. */
    public static int width(int configuredWidth, @Nullable LiveFeedClient.Video video) {
        return applies(configuredWidth, video) ? Math.max(configuredWidth, video.width()) : configuredWidth;
    }

    /** The video bitrate ceiling: the configured one, raised to the claim's when HD applies. */
    public static int kbps(int configuredKbps, int configuredWidth, @Nullable LiveFeedClient.Video video) {
        return applies(configuredWidth, video) ? Math.max(configuredKbps, video.kbps()) : configuredKbps;
    }
}
