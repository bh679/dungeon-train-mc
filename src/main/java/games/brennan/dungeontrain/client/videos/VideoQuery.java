package games.brennan.dungeontrain.client.videos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/**
 * The Videos page's filter and sort rules, as pure functions over {@link VideoEntry} lists.
 *
 * <p>Nothing here touches Minecraft, so the ordering — which is the part players would notice being
 * wrong — is pinned by {@code VideoQueryTest}. Every method returns a new list; inputs are never
 * reordered in place.</p>
 */
public final class VideoQuery {

    /**
     * What to show. {@link #platforms} is the set of platform toggles that are lit — every platform
     * to begin with, and an empty set shows nothing (the player switched them all off, and the page
     * says so). {@link #channelQuery} is the uploader box's text, matched case-insensitively as a
     * substring so a half-typed name already narrows the list; blank means everyone. {@code
     * devFavOnly} is the ★ toggle.
     */
    public record Filter(Set<VideoEntry.Platform> platforms, String channelQuery, boolean devFavOnly) {
        public static final Filter ALL = new Filter(EnumSet.allOf(VideoEntry.Platform.class), "", false);

        public Filter {
            platforms = platforms == null ? EnumSet.allOf(VideoEntry.Platform.class)
                    : (platforms.isEmpty() ? EnumSet.noneOf(VideoEntry.Platform.class) : EnumSet.copyOf(platforms));
            channelQuery = channelQuery == null ? "" : channelQuery.trim();
        }

        /** Flip one platform's toggle; a new filter, this one untouched. */
        public Filter togglePlatform(VideoEntry.Platform p) {
            EnumSet<VideoEntry.Platform> next = platforms.isEmpty()
                    ? EnumSet.noneOf(VideoEntry.Platform.class) : EnumSet.copyOf(platforms);
            if (!next.remove(p)) next.add(p);
            return new Filter(next, channelQuery, devFavOnly);
        }

        public boolean has(VideoEntry.Platform p) {
            return platforms.contains(p);
        }

        public Filter withChannelQuery(String q) {
            return new Filter(platforms, q, devFavOnly);
        }

        public Filter withDevFavOnly(boolean only) {
            return new Filter(platforms, channelQuery, only);
        }

        boolean matches(VideoEntry v) {
            if (!platforms.contains(v.platform())) return false;
            if (!channelQuery.isEmpty() && !contains(v.channel(), channelQuery)) return false;
            return !devFavOnly || v.devFav();
        }
    }

    /** Case-insensitive substring test; a {@code null} haystack matches nothing. */
    static boolean contains(String hay, String needle) {
        return hay != null && hay.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    /** How to order. Each carries its lang-key suffix under {@code gui.dungeontrain.videos.sort.}. */
    public enum Sort {
        /** Most viewed first; rows with no count sink to the bottom, newest of those first. */
        VIEWS("views"),
        /** Newest publish day first; undated rows last. */
        RECENT("recent"),
        /**
         * Starred rows first, then by views. A numeric dev-pick weight is planned to replace the
         * boolean; when it lands, only this comparator changes.
         */
        DEV_PICKS("dev_picks");

        private final String key;

        Sort(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public Sort next() {
            Sort[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    private VideoQuery() {}

    /** Filter then sort — a new list; {@code entries} is untouched. */
    public static List<VideoEntry> apply(List<VideoEntry> entries, Filter filter, Sort sort) {
        Filter f = filter == null ? Filter.ALL : filter;
        List<VideoEntry> out = new ArrayList<>();
        for (VideoEntry v : entries) {
            if (f.matches(v)) out.add(v);
        }
        out.sort(comparator(sort == null ? Sort.VIEWS : sort));
        return List.copyOf(out);
    }

    /**
     * Distinct uploader names across every row, in case-insensitive alphabetical order — what the
     * uploader box suggests. Rows with no channel contribute nothing.
     */
    public static List<String> channels(List<VideoEntry> entries) {
        return channels(entries, "");
    }

    /**
     * The uploader suggestions for what has been typed so far: every distinct name containing
     * {@code query} (case-insensitive), alphabetical; all of them for a blank query. Names that
     * START with the query come first — that is the completion the player is most likely reaching for.
     */
    public static List<String> channels(List<VideoEntry> entries, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        // Keyed lower-case so "DroneLeg" and "droneleg" are one entry; the first spelling seen wins.
        TreeMap<String, String> distinct = new TreeMap<>();
        for (VideoEntry v : entries) {
            if (v.hasChannel()) {
                distinct.putIfAbsent(v.channel().trim().toLowerCase(Locale.ROOT), v.channel().trim());
            }
        }
        List<String> starts = new ArrayList<>();
        List<String> within = new ArrayList<>();
        for (var e : distinct.entrySet()) {
            if (q.isEmpty() || e.getKey().startsWith(q)) starts.add(e.getValue());
            else if (e.getKey().contains(q)) within.add(e.getValue());
        }
        starts.addAll(within);
        return List.copyOf(starts);
    }

    /** The platforms actually present, in enum order — so the Platform filter never offers an empty state. */
    public static List<VideoEntry.Platform> platforms(List<VideoEntry> entries) {
        List<VideoEntry.Platform> out = new ArrayList<>();
        for (VideoEntry.Platform p : VideoEntry.Platform.values()) {
            for (VideoEntry v : entries) {
                if (v.platform() == p) {
                    out.add(p);
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    /** Human-scale view count: {@code 130}, {@code 1.2K}, {@code 3.4M}; {@code null} when unknown. */
    public static String compactViews(long views) {
        if (views < 0) return null;
        if (views < 1_000) return Long.toString(views);
        if (views < 1_000_000) return trimOne(views / 1_000.0) + "K";
        return trimOne(views / 1_000_000.0) + "M";
    }

    /** One decimal, dropped when it is zero: 1.0 → "1", 1.25 → "1.3". */
    private static String trimOne(double d) {
        String s = String.format(Locale.ROOT, "%.1f", d);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static Comparator<VideoEntry> comparator(Sort sort) {
        Comparator<VideoEntry> byViews = Comparator.comparingLong(VideoEntry::views).reversed();
        // Nulls sort last: an undated row cannot claim "newest".
        Comparator<VideoEntry> byDay = Comparator.comparing(VideoEntry::day,
                Comparator.nullsLast(Comparator.<String>reverseOrder()));
        Comparator<VideoEntry> byId = Comparator.comparingInt(VideoEntry::id).reversed();
        return switch (sort) {
            case VIEWS -> byViews.thenComparing(byDay).thenComparing(byId);
            case RECENT -> byDay.thenComparing(byViews).thenComparing(byId);
            case DEV_PICKS -> Comparator.comparing(VideoEntry::devFav, Comparator.reverseOrder())
                    .thenComparing(byViews).thenComparing(byDay).thenComparing(byId);
        };
    }
}
