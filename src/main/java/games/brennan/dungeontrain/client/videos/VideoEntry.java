package games.brennan.dungeontrain.client.videos;

import java.util.Locale;

/**
 * One row of the relay's curated videos-about-the-game list, as the main-menu Videos page shows it.
 *
 * <p>Immutable and Minecraft-free on purpose: {@link VideoQuery} filters and sorts these in plain
 * Java so the ordering rules can be unit-tested without a client. The shape mirrors the relay's
 * {@code videos.toPlayer()} — what a viewer needs to pick a video and open it, nothing about how the
 * relay came by it.</p>
 *
 * @param id       relay row id — stable across refreshes, used as the thumbnail cache key
 * @param url      the video's page, already validated as {@code http(s)://} with no whitespace
 * @param platform where it lives; {@link Platform#OTHER} for anything the relay adds later
 * @param videoId  platform id when the relay could parse one (YouTube's 11 chars, Bilibili's BV…),
 *                 else {@code null}; a YouTube id is what the thumbnail URL is built from
 * @param title    the video's title, or {@code null} when the relay has none yet
 * @param day      publish date as {@code YYYY-MM-DD}, or {@code null}
 * @param views    best-effort view count, or {@link #VIEWS_UNKNOWN} when no platform gave one
 * @param channel  uploader / channel display name, or {@code null} when unknown
 * @param devFav   the operator's ★ — the "Dev faves" filter and the "Dev picks" sort key
 * @param live     a Twitch streamer marker whose channel the relay has verified live with the game
 *                 right now (cleared by the relay when the stream ends)
 */
public record VideoEntry(int id, String url, Platform platform, String videoId, String title, String day,
                         long views, String channel, boolean devFav, boolean live) {

    /** {@link #views} when the relay served no count — sorted below every known count. */
    public static final long VIEWS_UNKNOWN = -1L;

    /** Where a video lives. Each carries the tile colour drawn when there is no thumbnail. */
    public enum Platform {
        YOUTUBE("youtube", 0xFFE53935),
        BILIBILI("bilibili", 0xFF23ADE5),
        TWITCH("twitch", 0xFF9146FF),
        INSTAGRAM("instagram", 0xFFD62976),
        OTHER("other", 0xFF6B6B6B);

        private final String key;
        private final int tileColour;

        Platform(String key, int tileColour) {
            this.key = key;
            this.tileColour = tileColour;
        }

        /** Lang-key suffix and the relay's wire value. */
        public String key() {
            return key;
        }

        /** Opaque ARGB for the placeholder tile. */
        public int tileColour() {
            return tileColour;
        }

        /** The relay's platform string → an enum, {@link #OTHER} for anything unknown. */
        public static Platform fromWire(String s) {
            if (s == null) return OTHER;
            String k = s.trim().toLowerCase(Locale.ROOT);
            for (Platform p : values()) {
                if (p.key.equals(k)) return p;
            }
            return OTHER;
        }
    }

    public VideoEntry {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url");
        if (platform == null) platform = Platform.OTHER;
        if (views < 0) views = VIEWS_UNKNOWN;
    }

    /** True when the relay served a view count for this row. */
    public boolean hasViews() {
        return views != VIEWS_UNKNOWN;
    }

    /**
     * True for a Twitch <em>streamer marker</em>: a bare {@code twitch.tv/<login>} row the relay saves
     * once per day a channel streamed the game, with no VOD id behind it. These are not videos — the
     * Videos page groups them into one chip per streamer ({@link TwitchStreamers}) instead of listing
     * them, and {@link VideoQuery} keeps them out of the video rows.
     */
    public boolean isStreamMarker() {
        return platform == Platform.TWITCH && (videoId == null || videoId.isBlank());
    }

    /** True when {@link #channel} names someone. */
    public boolean hasChannel() {
        return channel != null && !channel.isBlank();
    }

    /** {@link #title} when present, else the URL — a row always has something to read. */
    public String displayTitle() {
        return title != null && !title.isBlank() ? title : url;
    }
}
