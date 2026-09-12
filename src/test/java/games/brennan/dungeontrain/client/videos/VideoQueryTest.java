package games.brennan.dungeontrain.client.videos;

import org.junit.jupiter.api.Test;

import java.util.List;

import static games.brennan.dungeontrain.client.videos.VideoEntry.Platform.BILIBILI;
import static games.brennan.dungeontrain.client.videos.VideoEntry.Platform.TWITCH;
import static games.brennan.dungeontrain.client.videos.VideoEntry.Platform.YOUTUBE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class VideoQueryTest {

    /** A video row: every fixture carries a platform id, so a Twitch one is a VOD, not a streamer marker. */
    private static VideoEntry v(int id, VideoEntry.Platform p, String day, long views, String channel, boolean fav) {
        return new VideoEntry(id, "https://example.com/" + id, p, "v" + id, "T" + id, day, views, channel, fav);
    }

    /** A Twitch streamer marker: bare channel URL, no id — the strip's, never the list's. */
    private static VideoEntry marker(int id, String login, String day) {
        return new VideoEntry(id, "https://www.twitch.tv/" + login, TWITCH, null, login, day, VideoEntry.VIEWS_UNKNOWN, login, false);
    }

    private static final VideoEntry A = v(1, YOUTUBE, "2026-09-01", 500, "Alpha", false);
    private static final VideoEntry B = v(2, BILIBILI, "2026-09-05", 20, "beta", true);
    private static final VideoEntry C = v(3, TWITCH, null, VideoEntry.VIEWS_UNKNOWN, null, false);
    private static final VideoEntry D = v(4, YOUTUBE, "2026-08-20", 20, "ALPHA", true);
    private static final List<VideoEntry> ALL = List.of(C, A, D, B);

    private static List<Integer> ids(List<VideoEntry> l) {
        return l.stream().map(VideoEntry::id).toList();
    }

    @Test
    void viewsSortPutsUnknownLastAndBreaksTiesByDay() {
        assertEquals(List.of(1, 2, 4, 3), ids(VideoQuery.apply(ALL, VideoQuery.Filter.ALL, VideoQuery.Sort.VIEWS)));
    }

    @Test
    void recentSortPutsUndatedLast() {
        assertEquals(List.of(2, 1, 4, 3), ids(VideoQuery.apply(ALL, VideoQuery.Filter.ALL, VideoQuery.Sort.RECENT)));
    }

    @Test
    void devPicksSortStarsFirstThenViews() {
        assertEquals(List.of(2, 4, 1, 3), ids(VideoQuery.apply(ALL, VideoQuery.Filter.ALL, VideoQuery.Sort.DEV_PICKS)));
    }

    @Test
    void platformTogglesHideAndShow() {
        VideoQuery.Filter f = VideoQuery.Filter.ALL.togglePlatform(BILIBILI);   // hide Bilibili
        assertEquals(List.of(1, 4, 3), ids(VideoQuery.apply(ALL, f, VideoQuery.Sort.VIEWS)));
        assertEquals(List.of(1, 2, 4, 3), ids(VideoQuery.apply(ALL, f.togglePlatform(BILIBILI), VideoQuery.Sort.VIEWS)), "toggle back on");
        VideoQuery.Filter none = f.togglePlatform(YOUTUBE).togglePlatform(TWITCH)
                .togglePlatform(VideoEntry.Platform.INSTAGRAM).togglePlatform(VideoEntry.Platform.OTHER);
        assertEquals(List.of(), ids(VideoQuery.apply(ALL, none, VideoQuery.Sort.VIEWS)), "all off shows nothing");
        assertEquals(List.of(1, 4), ids(VideoQuery.apply(ALL, none.togglePlatform(YOUTUBE), VideoQuery.Sort.VIEWS)));
    }

    @Test
    void uploaderQueryIsACaseInsensitiveSubstring() {
        // "Alpha" and "ALPHA" are one uploader; a half-typed name already narrows.
        assertEquals(List.of(1, 4), ids(VideoQuery.apply(ALL, VideoQuery.Filter.ALL.withChannelQuery("alph"), VideoQuery.Sort.VIEWS)));
        assertEquals(List.of(1, 4), ids(VideoQuery.apply(ALL, VideoQuery.Filter.ALL.withChannelQuery("  LPH "), VideoQuery.Sort.VIEWS)));
        assertEquals(List.of(), ids(VideoQuery.apply(ALL, VideoQuery.Filter.ALL.withChannelQuery("zzz"), VideoQuery.Sort.VIEWS)));
        assertEquals(List.of(1, 2, 4, 3), ids(VideoQuery.apply(ALL, VideoQuery.Filter.ALL.withChannelQuery("   "), VideoQuery.Sort.VIEWS)), "blank = everyone, including no-channel rows");
    }

    @Test
    void starFilterCombinesWithTheOthers() {
        assertEquals(List.of(2, 4), ids(VideoQuery.apply(ALL, VideoQuery.Filter.ALL.withDevFavOnly(true), VideoQuery.Sort.VIEWS)));
        assertEquals(List.of(4), ids(VideoQuery.apply(ALL,
                VideoQuery.Filter.ALL.togglePlatform(BILIBILI).withDevFavOnly(true), VideoQuery.Sort.VIEWS)));
    }

    @Test
    void channelSuggestionsNarrowWithPrefixMatchesFirst() {
        List<VideoEntry> more = List.of(A, B, D, v(9, TWITCH, null, 1, "Gamma Alpine", false));
        assertEquals(List.of("Alpha", "beta", "Gamma Alpine"), VideoQuery.channels(more, ""));
        assertEquals(List.of("Alpha", "Gamma Alpine"), VideoQuery.channels(more, "al"), "prefix match before substring match");
        assertEquals(List.of("beta"), VideoQuery.channels(more, "ET"));
        assertEquals(List.of(), VideoQuery.channels(more, "q"));
    }

    @Test
    void streamerMarkersNeverReachTheListButStillCountAsTwitchForTheToggles() {
        VideoEntry m = marker(7, "droneleg", "2026-08-21");
        List<VideoEntry> withMarker = List.of(m, C, A);
        assertEquals(List.of(1, 3), ids(VideoQuery.apply(withMarker, VideoQuery.Filter.ALL, VideoQuery.Sort.VIEWS)), "marker dropped, VOD kept");
        assertEquals(List.of(), ids(VideoQuery.apply(withMarker, VideoQuery.Filter.ALL.withChannelQuery("droneleg"), VideoQuery.Sort.VIEWS)), "no filter resurrects a marker");
        assertEquals(2, VideoQuery.videoCount(withMarker));
        assertEquals(List.of(TWITCH), VideoQuery.platforms(List.of(m)), "a marker alone still lights the Twitch toggle");
        assertEquals(List.of("droneleg"), VideoQuery.channels(List.of(m)), "and still suggests its uploader");
    }

    private static List<String> rowKeys(List<VideoQuery.Row> rows) {
        return rows.stream().map(r -> switch (r) {
            case VideoQuery.VideoRow vr -> "v" + vr.video().id();
            case VideoQuery.StreamerRow sr -> "s:" + sr.streamer().name();
        }).toList();
    }

    @Test
    void applyRowsMergesStreamersIntoTheOneListUnderTheirOwnToggle() {
        // droneleg streamed on two days (newest marker id 8, day 09-03); C is a Twitch VOD with no views.
        List<VideoEntry> all = List.of(marker(7, "droneleg", "2026-08-21"), C, A, B, marker(8, "droneleg", "2026-09-03"));
        assertEquals(List.of("v1", "v2", "s:droneleg", "v3"), rowKeys(VideoQuery.applyRows(all, VideoQuery.Filter.ALL, VideoQuery.Sort.VIEWS)),
                "Views: the streamer sinks under every counted video, then ranks by last day against the undated VOD");
        assertEquals(List.of("v2", "s:droneleg", "v1", "v3"), rowKeys(VideoQuery.applyRows(all, VideoQuery.Filter.ALL, VideoQuery.Sort.RECENT)),
                "Recent: interleaved by last stream day");
        assertEquals(List.of("v1", "v2", "v3"), rowKeys(VideoQuery.applyRows(all, VideoQuery.Filter.ALL.withStreamers(false), VideoQuery.Sort.VIEWS)),
                "streamers toggle off drops streamer rows but keeps the Twitch VOD");
        assertEquals(List.of("v1", "v2", "s:droneleg"), rowKeys(VideoQuery.applyRows(all, VideoQuery.Filter.ALL.togglePlatform(TWITCH), VideoQuery.Sort.VIEWS)),
                "Twitch platform toggle off drops the VOD but keeps the streamer");
        assertEquals(List.of("s:droneleg"), rowKeys(VideoQuery.applyRows(all, VideoQuery.Filter.ALL.withChannelQuery("drone"), VideoQuery.Sort.VIEWS)),
                "uploader box narrows streamers too");
        assertEquals(List.of("v2"), rowKeys(VideoQuery.applyRows(all, VideoQuery.Filter.ALL.withDevFavOnly(true), VideoQuery.Sort.DEV_PICKS)),
                "★ filter hides an unstarred streamer");
    }

    @Test
    void applyNeverMutatesItsInput() {
        List<VideoEntry> input = List.of(C, A, D, B);
        VideoQuery.apply(input, VideoQuery.Filter.ALL, VideoQuery.Sort.VIEWS);
        assertEquals(List.of(3, 1, 4, 2), ids(input));
    }

    @Test
    void channelsAreDistinctCaseInsensitiveAndSorted() {
        assertEquals(List.of("Alpha", "beta"), VideoQuery.channels(ALL));
    }

    @Test
    void platformsListsOnlyThosePresentInEnumOrder() {
        assertEquals(List.of(YOUTUBE, BILIBILI, TWITCH), VideoQuery.platforms(ALL));
        assertEquals(List.of(), VideoQuery.platforms(List.of()));
    }

    @Test
    void compactViews() {
        assertNull(VideoQuery.compactViews(VideoEntry.VIEWS_UNKNOWN));
        assertEquals("0", VideoQuery.compactViews(0));
        assertEquals("999", VideoQuery.compactViews(999));
        assertEquals("1K", VideoQuery.compactViews(1_000));
        assertEquals("1.3K", VideoQuery.compactViews(1_250));
        assertEquals("12.5K", VideoQuery.compactViews(12_499));
        assertEquals("3.4M", VideoQuery.compactViews(3_400_000));
    }

    @Test
    void sortCyclesThroughAllThree() {
        assertSame(VideoQuery.Sort.RECENT, VideoQuery.Sort.VIEWS.next());
        assertSame(VideoQuery.Sort.DEV_PICKS, VideoQuery.Sort.RECENT.next());
        assertSame(VideoQuery.Sort.VIEWS, VideoQuery.Sort.DEV_PICKS.next());
    }

    @Test
    void platformFromWireIsLenient() {
        assertSame(YOUTUBE, VideoEntry.Platform.fromWire(" YouTube "));
        assertSame(VideoEntry.Platform.OTHER, VideoEntry.Platform.fromWire("tiktok"));
        assertSame(VideoEntry.Platform.OTHER, VideoEntry.Platform.fromWire(null));
    }
}
