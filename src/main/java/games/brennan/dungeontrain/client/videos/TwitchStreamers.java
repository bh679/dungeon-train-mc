package games.brennan.dungeontrain.client.videos;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Folds the relay's Twitch <em>streamer markers</em> — one bare {@code twitch.tv/<login>} row per day
 * a channel streamed the game — into one {@link Streamer} per channel, for the Videos page
 * list. The relay saves a marker because a live stream has no VOD to link yet, so the
 * catalogue carries a dozen identical rows for one regular streamer; players want the streamer
 * once, with how often and how recently they streamed. {@link VideoQuery#applyRows} puts these in
 * the Videos list beside the videos, behind their own toolbar toggle.
 *
 * <p>Pure and Minecraft-free like {@link VideoQuery}, so {@code TwitchStreamersTest} pins the
 * grouping without a client. Every method returns a new list; inputs are never touched.</p>
 */
public final class TwitchStreamers {

    /**
     * One streamer.
     *
     * @param key        canonical channel key ({@code twitch.tv/<login>}) the markers were grouped on
     * @param name       display name — the newest marker's {@code channel}, else the login from the URL
     * @param url        the newest marker's URL — what a click opens
     * @param streamDays how many distinct days the relay saw this channel streaming
     * @param lastDay    the most recent of those days ({@code YYYY-MM-DD}), or {@code null} when no
     *                   marker carried a day
     * @param devFav     true when any marker is the operator's ★
     * @param newestId   the newest marker's relay id — the list's final sort tiebreak
     * @param live       true when any marker (in practice today's) is relay-verified live right now
     */
    public record Streamer(String key, String name, String url, int streamDays, String lastDay, boolean devFav,
                           int newestId, boolean live) {
        public boolean hasLastDay() {
            return lastDay != null;
        }
    }

    private TwitchStreamers() {}

    /**
     * Every streamer in {@code entries}, from its marker rows only (VODs and other platforms are
     * ignored — they are list rows). Ordered most stream days first, then most recent day, then name.
     */
    public static List<Streamer> group(List<VideoEntry> entries) {
        // Insertion-ordered so an equal-count, equal-day tie still resolves the same way on every call.
        Map<String, Acc> byKey = new LinkedHashMap<>();
        for (VideoEntry v : entries) {
            if (!v.isStreamMarker()) continue;
            String key = channelKey(v.url());
            if (key == null) continue;
            byKey.computeIfAbsent(key, Acc::new).add(v);
        }
        List<Streamer> out = new ArrayList<>(byKey.size());
        for (Acc a : byKey.values()) {
            out.add(a.toStreamer());
        }
        out.sort(Comparator.comparingInt(Streamer::streamDays).reversed()
                .thenComparing(Streamer::lastDay, Comparator.nullsLast(Comparator.<String>reverseOrder()))
                .thenComparing(s -> s.name().toLowerCase(Locale.ROOT)));
        return List.copyOf(out);
    }

    /**
     * Canonical form of a channel URL for grouping: lower-case host without {@code www.}, lower-case
     * path without a trailing slash, query and fragment dropped — {@code https://Twitch.tv/DrOneLeg/}
     * and {@code https://www.twitch.tv/droneleg?x=1} are one channel. Mirrors the relay's
     * {@code videos.js channelKey()}. {@code null} for anything unparseable.
     */
    static String channelKey(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI u = URI.create(url.trim());
            String host = u.getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            String path = u.getRawPath() == null ? "" : u.getRawPath().toLowerCase(Locale.ROOT);
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return host + path;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The {@code <login>} segment of a canonical key, or {@code null} when there is none. */
    static String loginFromKey(String key) {
        if (key == null) return null;
        int slash = key.indexOf('/');
        if (slash < 0 || slash == key.length() - 1) return null;
        String rest = key.substring(slash + 1);
        int next = rest.indexOf('/');
        return next < 0 ? rest : rest.substring(0, next);
    }

    /** Running fold for one channel; the newest marker (by day, then id) supplies name and URL. */
    private static final class Acc {
        private final String key;
        private final TreeSet<String> days = new TreeSet<>();
        private boolean undated;
        private boolean devFav;
        private boolean live;
        private VideoEntry newest;

        Acc(String key) {
            this.key = key;
        }

        void add(VideoEntry v) {
            if (v.day() != null) days.add(v.day());
            else undated = true;
            devFav |= v.devFav();
            live |= v.live();
            if (newest == null || isNewer(v, newest)) newest = v;
        }

        private static boolean isNewer(VideoEntry a, VideoEntry b) {
            if (a.day() == null) return b.day() == null && a.id() > b.id();
            if (b.day() == null) return true;
            int c = a.day().compareTo(b.day());
            return c > 0 || (c == 0 && a.id() > b.id());
        }

        Streamer toStreamer() {
            String name = newest.hasChannel() ? newest.channel().trim() : loginFromKey(key);
            if (name == null || name.isBlank()) name = key;
            // Every undated marker is one more day we know of but cannot place.
            int count = days.size() + (undated ? 1 : 0);
            return new Streamer(key, name, newest.url(), count, days.isEmpty() ? null : days.last(), devFav,
                    newest.id(), live);
        }
    }
}
