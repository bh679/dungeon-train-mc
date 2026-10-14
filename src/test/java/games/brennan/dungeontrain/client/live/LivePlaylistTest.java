package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivePlaylistTest {

    @Test
    void listsSegmentsInOrderAndIgnoresTags() {
        LivePlaylist p = LivePlaylist.parse("""
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:3
            #EXTINF:10.000000,
            seg00003.ts
            #EXTINF:10.000000,
            seg00004.ts

            #EXTINF:9.960000,
            seg00005.ts
            """);
        assertEquals(List.of("seg00003.ts", "seg00004.ts", "seg00005.ts"), p.segments());
        assertFalse(p.ended());
    }

    @Test
    void endlistMarksTheStreamOver() {
        LivePlaylist p = LivePlaylist.parse("#EXTM3U\n#EXTINF:10,\nseg00000.ts\n#EXT-X-ENDLIST\n");
        assertTrue(p.ended());
        assertEquals(List.of("seg00000.ts"), p.segments());
    }

    @Test
    void refusesPathsAndTolerateGarbage() {
        LivePlaylist p = LivePlaylist.parse("../etc/passwd\r\nsub/seg.ts\r\nC:\\x.ts\r\nseg00001.ts\r\n");
        assertEquals(List.of("seg00001.ts"), p.segments());
        assertEquals(List.of(), LivePlaylist.parse(null).segments());
        assertEquals(List.of(), LivePlaylist.parse("").segments());
    }

    private static final String LIVE = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-TARGETDURATION:10
        #EXT-X-MEDIA-SEQUENCE:3
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXTINF:10.000000,
        seg00003.ts
        #EXTINF:10.000000,
        seg00004.ts
        #EXTINF:9.960000,
        seg00005.ts
        #EXT-X-ENDLIST
        """;

    @Test
    void publishableIsUnchangedWhenEverySegmentIsUp() {
        assertSame(LIVE, LivePlaylist.publishable(LIVE, s -> true, s -> false));
    }

    @Test
    void publishableCutsTheTailAtASegmentStillBeingRetried() {
        String out = LivePlaylist.publishable(LIVE, s -> !s.equals("seg00004.ts"), s -> false);
        LivePlaylist p = LivePlaylist.parse(out);
        assertEquals(List.of("seg00003.ts"), p.segments());
        assertFalse(p.ended(), "ENDLIST kept on a cut tail");
        assertTrue(out.contains("#EXT-X-MEDIA-SEQUENCE:3"));
        assertEquals(1, out.split("#EXTINF").length - 1, "an #EXTINF lost its segment: " + out);
    }

    @Test
    void publishableCutsTheHeadAtAGivenUpSegmentAndRaisesTheSequence() {
        String out = LivePlaylist.publishable(LIVE, s -> !s.equals("seg00004.ts"), s -> s.equals("seg00004.ts"));
        LivePlaylist p = LivePlaylist.parse(out);
        assertEquals(List.of("seg00005.ts"), p.segments());
        assertTrue(p.ended());
        assertTrue(out.contains("#EXT-X-MEDIA-SEQUENCE:5"), out);
        assertTrue(out.startsWith("#EXTM3U\n"), out);
        assertTrue(out.contains("#EXTINF:9.960000,\nseg00005.ts"), out);
    }

    @Test
    void publishableIsNullWhenNothingIsUp() {
        assertNull(LivePlaylist.publishable(LIVE, s -> false, s -> false));
        assertNull(LivePlaylist.publishable(LIVE, s -> false, s -> s.equals("seg00005.ts")));
    }
}
