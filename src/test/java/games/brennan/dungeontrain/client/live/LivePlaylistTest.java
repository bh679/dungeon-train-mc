package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
