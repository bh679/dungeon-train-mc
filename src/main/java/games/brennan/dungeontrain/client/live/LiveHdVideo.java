package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import org.jetbrains.annotations.Nullable;

/**
 * HD Streams: the picture a stream goes out at once the claim's {@link LiveFeedClient.Video} is applied.
 * The relay's video only ever RAISES a streamer on the default size or above (720p → 1080p, and the bitrate
 * to match); a streamer who chose a smaller Video size keeps theirs, since they picked it for frame rate.
 * A remembered upload too slow to carry the HD bitrate keeps the stream at its own size too: 1080p squeezed
 * into a 720p bitrate looks worse than the 720p it replaces. An unmeasured upload (or adaptive quality off)
 * takes HD, and the governor lowers the bitrate if the link then can't keep up.
 */
public final class LiveHdVideo {
    /** The Options → Stream Video size default (720p): at or above it a streamer takes HD. */
    static final int DEFAULT_WIDTH = 1280;

    private LiveHdVideo() { }

    /**
     * Whether {@code video} applies to a streamer whose Video size is {@code configuredWidth} and whose
     * last stream measured {@code learnedKbps} of upload (0 = not measured, or adaptive quality off),
     * sending {@code audioKbps} of sound beside the picture.
     */
    public static boolean applies(int configuredWidth, @Nullable LiveFeedClient.Video video, int learnedKbps, int audioKbps) {
        if (video == null || configuredWidth < DEFAULT_WIDTH) return false;
        return learnedKbps <= 0 || video.kbps() + audioKbps <= LiveQualityGovernor.FIT_SHARE * learnedKbps;
    }

    /** The broadcast width: {@code configuredWidth}, raised to the claim's when {@code hd}. */
    public static int width(int configuredWidth, @Nullable LiveFeedClient.Video video, boolean hd) {
        return hd && video != null ? Math.max(configuredWidth, video.width()) : configuredWidth;
    }

    /** The video bitrate ceiling: {@code configuredKbps}, raised to the claim's when {@code hd}. */
    public static int kbps(int configuredKbps, @Nullable LiveFeedClient.Video video, boolean hd) {
        return hd && video != null ? Math.max(configuredKbps, video.kbps()) : configuredKbps;
    }
}
