package games.brennan.dungeontrain.client.videos;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoCatalogFetcherTest {

    @Test
    void parsesTheRelayShape() {
        List<VideoEntry> out = VideoCatalogFetcher.parse("""
                {"ok":true,"videos":[
                  {"id":7,"url":"https://www.youtube.com/watch?v=abc","platform":"youtube","videoId":"abc",
                   "title":"  A   run ","day":"2026-09-11","views":130,"channel":"Creator","devFav":1},
                  {"id":8,"url":"https://www.twitch.tv/x","platform":"twitch","videoId":null,
                   "title":null,"day":null,"views":null,"channel":null,"devFav":0}
                ]}""");
        assertEquals(2, out.size());
        VideoEntry a = out.get(0);
        assertEquals(7, a.id());
        assertEquals(VideoEntry.Platform.YOUTUBE, a.platform());
        assertEquals("A run", a.title(), "whitespace collapsed");
        assertEquals(130, a.views());
        assertEquals("Creator", a.channel());
        assertTrue(a.devFav());
        VideoEntry b = out.get(1);
        assertNull(b.videoId());
        assertNull(b.title());
        assertEquals("https://www.twitch.tv/x", b.displayTitle(), "URL stands in for a missing title");
        assertFalse(b.hasViews());
        assertFalse(b.devFav());
    }

    @Test
    void devFavAcceptsBooleanOrNumber() {
        List<VideoEntry> out = VideoCatalogFetcher.parse("""
                {"videos":[
                  {"id":1,"url":"https://a.b/1","platform":"youtube","devFav":true},
                  {"id":2,"url":"https://a.b/2","platform":"youtube","devFav":false},
                  {"id":3,"url":"https://a.b/3","platform":"youtube"}
                ]}""");
        assertTrue(out.get(0).devFav());
        assertFalse(out.get(1).devFav());
        assertFalse(out.get(2).devFav());
    }

    @Test
    void badRowsAreDroppedNotFatal() {
        List<VideoEntry> out = VideoCatalogFetcher.parse("""
                {"videos":[
                  {"id":1,"url":"javascript:alert(1)","platform":"youtube"},
                  {"id":2,"url":"https://ok.example/ v","platform":"youtube"},
                  {"url":"https://no.id/","platform":"youtube"},
                  "not an object",
                  {"id":5,"url":"https://ok.example/5","platform":"tiktok","day":"11/09/2026"}
                ]}""");
        assertEquals(1, out.size());
        assertEquals(5, out.get(0).id());
        assertEquals(VideoEntry.Platform.OTHER, out.get(0).platform());
        assertNull(out.get(0).day(), "a malformed day is dropped, the row kept");
    }

    @Test
    void wrongEnvelopeIsAFailureNotAnEmptyCatalogue() {
        assertNull(VideoCatalogFetcher.parse("[]"));
        assertNull(VideoCatalogFetcher.parse("{\"ok\":true}"));
        assertNull(VideoCatalogFetcher.parse("{\"videos\":{}}"));
        assertEquals(0, VideoCatalogFetcher.parse("{\"videos\":[]}").size());
    }
}
