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

    private static VideoEntry v(int id, VideoEntry.Platform p, String day, long views, String channel, boolean fav) {
        return new VideoEntry(id, "https://example.com/" + id, p, null, "T" + id, day, views, channel, fav);
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
    void filtersNarrowByPlatformChannelAndStar() {
        assertEquals(List.of(1, 4), ids(VideoQuery.apply(ALL, VideoQuery.Filter.ALL.withPlatform(YOUTUBE), VideoQuery.Sort.VIEWS)));
        // Channel match is case-insensitive: "Alpha" and "ALPHA" are one uploader.
        assertEquals(List.of(1, 4), ids(VideoQuery.apply(ALL, VideoQuery.Filter.ALL.withChannel("alpha"), VideoQuery.Sort.VIEWS)));
        assertEquals(List.of(2, 4), ids(VideoQuery.apply(ALL, VideoQuery.Filter.ALL.withDevFavOnly(true), VideoQuery.Sort.VIEWS)));
        assertEquals(List.of(4), ids(VideoQuery.apply(ALL,
                VideoQuery.Filter.ALL.withPlatform(YOUTUBE).withDevFavOnly(true), VideoQuery.Sort.VIEWS)));
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
