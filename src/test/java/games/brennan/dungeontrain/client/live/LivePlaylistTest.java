package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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

    private static String window(long first, int count, boolean ended) {
        StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:" + first + "\n");
        for (long i = first; i < first + count; i++) b.append("#EXTINF:10.000000,\n").append(seg(i)).append('\n');
        if (ended) b.append("#EXT-X-ENDLIST\n");
        return b.toString();
    }

    private static String seg(long sn) {
        return String.format("seg%05d.ts", sn);
    }

    private static Set<String> segs(long... sns) {
        Set<String> out = new java.util.HashSet<>();
        for (long sn : sns) out.add(seg(sn));
        return out;
    }

    private static TreeSet<Long> seqs(long... sns) {
        TreeSet<Long> out = new TreeSet<>();
        for (long sn : sns) out.add(sn);
        return out;
    }

    private static String pub(String text, Set<String> uploaded, TreeSet<Long> skipped) {
        Optional<String> p = LivePlaylist.publishable(text, uploaded, skipped);
        assertTrue(p.isPresent(), "expected something to publish");
        return p.get();
    }

    /** sequence number → segment name, as a player numbers the published playlist. */
    private static java.util.Map<Long, String> numbering(String published) {
        LivePlaylist p = LivePlaylist.parse(published);
        java.util.Map<Long, String> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < p.segments().size(); i++) out.put(p.mediaSequence() + i, p.segments().get(i));
        return out;
    }

    @Test
    void readsMediaSequence() {
        assertEquals(3, LivePlaylist.parse(window(3, 2, false)).mediaSequence());
        assertEquals(0, LivePlaylist.parse("#EXTM3U\nseg00000.ts\n").mediaSequence());
    }

    @Test
    void allPresentPublishesThePlaylistAsIs() {
        String text = window(3, 3, true);
        assertEquals(text, pub(text, segs(3, 4, 5), seqs()));
    }

    @Test
    void pendingSegmentCutsThePlaylistBeforeItAndHoldsBackEndlist() {
        String out = pub(window(3, 4, true), segs(3, 5, 6), seqs());
        assertEquals(List.of(seg(3)), LivePlaylist.parse(out).segments());
        assertFalse(out.contains("#EXT-X-ENDLIST"));
        assertEquals(3, LivePlaylist.parse(out).mediaSequence());
    }

    @Test
    void nothingPublishableIsEmpty() {
        assertTrue(LivePlaylist.publishable(window(3, 3, false), segs(4, 5), seqs()).isEmpty());
        assertTrue(LivePlaylist.publishable(window(3, 2, false), segs(), seqs(3, 4)).isEmpty());
        assertTrue(LivePlaylist.publishable(null, segs(), seqs()).isEmpty());
    }

    @Test
    void givenUpSegmentIsDroppedWithItsExtinfAndMarksADiscontinuity() {
        String out = pub(window(3, 3, true), segs(3, 5), seqs(4));
        assertEquals("""
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:3
            #EXTINF:10.000000,
            seg00003.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:10.000000,
            seg00005.ts
            #EXT-X-ENDLIST
            """, out);
    }

    @Test
    void givenUpRunGetsOneDiscontinuity() {
        String out = pub(window(3, 4, false), segs(3, 6), seqs(4, 5));
        assertEquals(1, out.split("#EXT-X-DISCONTINUITY\n", -1).length - 1);
        assertEquals(List.of(seg(3), seg(6)), LivePlaylist.parse(out).segments());
    }

    @Test
    void numberingStaysStableAsTheWindowSlidesPastAGivenUpSegment() {
        TreeSet<Long> skipped = seqs(4);
        java.util.Map<Long, String> seen = new java.util.HashMap<>();
        for (long first = 0; first <= 8; first++) {
            Set<String> up = new java.util.HashSet<>();
            for (long sn = first; sn < first + 6; sn++) if (sn != 4) up.add(seg(sn));
            String out = pub(window(first, 6, false), up, skipped);
            assertFalse(out.contains(seg(4)));
            for (var e : numbering(out).entrySet()) {
                String before = seen.putIfAbsent(e.getKey(), e.getValue());
                if (before != null) assertEquals(before, e.getValue(), "sequence " + e.getKey() + " renamed at window " + first);
            }
        }
    }

    @Test
    void slidOffDiscontinuityIsCountedInDiscontinuitySequence() {
        // seg 4 given up; its discontinuity sits on seg 5. While 5 is listed it carries the tag…
        String with5 = pub(window(5, 3, false), segs(5, 6, 7), seqs(4));
        assertTrue(with5.contains("#EXT-X-MEDIA-SEQUENCE:4\n"));
        assertTrue(with5.contains("#EXT-X-DISCONTINUITY\n#EXTINF:10.000000,\nseg00005.ts"));
        assertFalse(with5.contains("#EXT-X-DISCONTINUITY-SEQUENCE"));
        // …and once 5 slides off, the count moves into DISCONTINUITY-SEQUENCE.
        String after = pub(window(6, 3, false), segs(6, 7, 8), seqs(4));
        assertTrue(after.contains("#EXT-X-MEDIA-SEQUENCE:5\n#EXT-X-DISCONTINUITY-SEQUENCE:1\n"));
        assertFalse(after.contains("#EXT-X-DISCONTINUITY\n"));
    }

    @Test
    void existingDiscontinuitySequenceIsAddedTo() {
        String text = window(6, 2, false).replace("#EXT-X-MEDIA-SEQUENCE:6\n", "#EXT-X-MEDIA-SEQUENCE:6\n#EXT-X-DISCONTINUITY-SEQUENCE:2\n");
        String out = pub(text, segs(6, 7), seqs(1, 4));
        assertTrue(out.contains("#EXT-X-MEDIA-SEQUENCE:4\n#EXT-X-DISCONTINUITY-SEQUENCE:4\n"));
    }
}
