package games.brennan.dungeontrain.client.videos;

import org.junit.jupiter.api.Test;

import java.util.List;

import static games.brennan.dungeontrain.client.videos.VideoEntry.Platform.TWITCH;
import static games.brennan.dungeontrain.client.videos.VideoEntry.Platform.YOUTUBE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwitchStreamersTest {

    private static VideoEntry marker(int id, String url, String channel, String day, boolean fav) {
        return new VideoEntry(id, url, TWITCH, null, channel, day, VideoEntry.VIEWS_UNKNOWN, channel, fav);
    }

    private static VideoEntry vod(int id, String channel, String day) {
        return new VideoEntry(id, "https://www.twitch.tv/videos/" + id, TWITCH, "" + id, "VOD " + id, day, 10, channel, false);
    }

    // The live relay's shape on 2026-09-12: one regular, two occasional, spelled inconsistently.
    private static final List<VideoEntry> LIVE = List.of(
            marker(80, "https://www.twitch.tv/xxx606xxx_", "xxx606xxx_", "2026-08-30", false),
            marker(78, "https://twitch.tv/midnight_catt", "midnight_catt", "2026-09-02", false),
            vod(76, "Midnight_Catt", "2026-09-01"),
            marker(75, "https://twitch.tv/midnight_catt", "midnight_catt", "2026-09-01", false),
            marker(68, "https://www.twitch.tv/xxx606xxx_", "xxx606xxx_", "2026-08-30", false),   // same day twice
            marker(66, "https://www.twitch.tv/xxx606xxx_/", "xxx606xxx_", "2026-08-29", true),
            marker(53, "https://www.twitch.tv/droneleg", "DrOneLeg", "2026-08-21", false),
            marker(51, "https://Twitch.tv/DrOneLeg?x=1", "droneleg", "2026-08-20", false),
            marker(16, "https://www.twitch.tv/droneleg", "droneleg", "2026-07-13", false),
            new VideoEntry(1, "https://youtu.be/abc", YOUTUBE, "abc", "yt", "2026-09-01", 5, "Someone", false));

    @Test
    void groupsMarkersByCanonicalChannelIgnoringVodsAndOtherPlatforms() {
        List<TwitchStreamers.Streamer> s = TwitchStreamers.group(LIVE);
        assertEquals(List.of("twitch.tv/droneleg", "twitch.tv/midnight_catt", "twitch.tv/xxx606xxx_"),
                s.stream().map(TwitchStreamers.Streamer::key).toList(), "most stream days first; ties by latest day");
        assertEquals(List.of(3, 2, 2), s.stream().map(TwitchStreamers.Streamer::streamDays).toList(), "distinct days, not rows");
    }

    @Test
    void newestMarkerNamesTheStreamerAndSuppliesTheUrl() {
        TwitchStreamers.Streamer d = TwitchStreamers.group(LIVE).get(0);
        assertEquals("DrOneLeg", d.name(), "the relay's backfilled display name on the newest row wins");
        assertEquals("https://www.twitch.tv/droneleg", d.url());
        assertEquals("2026-08-21", d.lastDay());
        assertFalse(d.devFav());
        TwitchStreamers.Streamer x = TwitchStreamers.group(LIVE).get(2);
        assertTrue(x.devFav(), "one starred marker stars the streamer");
        assertEquals("2026-08-30", x.lastDay());
    }

    @Test
    void loginFallsBackToTheUrlAndUndatedMarkersCountOnce() {
        List<TwitchStreamers.Streamer> s = TwitchStreamers.group(List.of(
                marker(2, "https://www.twitch.tv/NyoomBomb", null, null, false),
                marker(3, "https://www.twitch.tv/nyoombomb", null, null, false)));
        assertEquals(1, s.size());
        assertEquals("nyoombomb", s.get(0).name());
        assertEquals(1, s.get(0).streamDays());
        assertNull(s.get(0).lastDay());
        assertFalse(s.get(0).hasLastDay());
    }

    @Test
    void channelKeyMirrorsTheRelay() {
        assertEquals("twitch.tv/droneleg", TwitchStreamers.channelKey("https://Twitch.tv/DrOneLeg/"));
        assertEquals("twitch.tv/droneleg", TwitchStreamers.channelKey("https://www.twitch.tv/droneleg?x=1#f"));
        assertNull(TwitchStreamers.channelKey("not a url"));
        assertNull(TwitchStreamers.channelKey(null));
        assertEquals("droneleg", TwitchStreamers.loginFromKey("twitch.tv/droneleg/about"));
        assertNull(TwitchStreamers.loginFromKey("twitch.tv"));
    }

    @Test
    void filterFollowsTheToolbar() {
        List<TwitchStreamers.Streamer> all = TwitchStreamers.group(LIVE);
        assertEquals(3, TwitchStreamers.filter(all, VideoQuery.Filter.ALL).size());
        assertEquals(List.of(), TwitchStreamers.filter(all, VideoQuery.Filter.ALL.togglePlatform(TWITCH)), "Twitch toggle off hides the strip");
        assertEquals(List.of("Midnight_Catt".toLowerCase()), TwitchStreamers.filter(all, VideoQuery.Filter.ALL.withChannelQuery("NIGHT"))
                .stream().map(s -> s.name().toLowerCase()).toList(), "uploader query is a case-insensitive substring");
        assertEquals(List.of("xxx606xxx_"), TwitchStreamers.filter(all, VideoQuery.Filter.ALL.withDevFavOnly(true))
                .stream().map(TwitchStreamers.Streamer::name).toList());
        assertEquals(3, TwitchStreamers.filter(all, null).size());
    }

    @Test
    void groupNeverMutatesItsInputAndReturnsNothingForNoMarkers() {
        List<VideoEntry> input = List.of(LIVE.get(0), LIVE.get(1));
        TwitchStreamers.group(input);
        assertEquals(80, input.get(0).id());
        assertEquals(List.of(), TwitchStreamers.group(List.of(vod(1, "x", "2026-01-01"))));
    }
}
