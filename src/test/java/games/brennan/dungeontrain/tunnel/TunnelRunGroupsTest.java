package games.brennan.dungeontrain.tunnel;

import games.brennan.dungeontrain.template.TemplateGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** One group per tunnel across chunks — {@link TunnelRunGroups}. */
final class TunnelRunGroupsTest {

    /** In-memory {@link TunnelRunGroups.EdgeBook} with the same first-answer-wins rule as the SavedData. */
    private static final class Book implements TunnelRunGroups.EdgeBook {
        final Map<Integer, TunnelRunGroups.Edge[]> edges = new HashMap<>();

        @Override
        public TunnelRunGroups.Edge westOf(int chunkX) {
            TunnelRunGroups.Edge[] e = edges.get(chunkX);
            return e == null ? null : e[0];
        }

        @Override
        public TunnelRunGroups.Edge eastOf(int chunkX) {
            TunnelRunGroups.Edge[] e = edges.get(chunkX);
            return e == null ? null : e[1];
        }

        @Override
        public void record(int chunkX, TunnelRunGroups.Edge west, TunnelRunGroups.Edge east) {
            TunnelRunGroups.Edge[] e = edges.computeIfAbsent(chunkX, k -> new TunnelRunGroups.Edge[2]);
            if (e[0] == null) e[0] = west;
            if (e[1] == null) e[1] = east;
        }
    }

    /** Rolls a group named after the key, recording every key it was asked for. */
    private static final class KeyRoller implements TunnelRunGroups.Roller {
        final List<Long> keys = new ArrayList<>();

        @Override
        public TemplateGroup roll(long key, int worldX) {
            keys.add(key);
            return TemplateGroup.of("g" + Long.toUnsignedString(key, 36));
        }
    }

    private static boolean[] cols(int from, int toInclusive) {
        boolean[] q = new boolean[16];
        for (int i = from; i <= toInclusive; i++) q[i] = true;
        return q;
    }

    private static TemplateGroup[] resolve(Book book, int chunkX, boolean[] q, boolean prev, boolean next,
                                           TunnelRunGroups.Roller roller) {
        return TunnelRunGroups.resolve(book, chunkX, TunnelRunGroups.runs(q, prev, next), roller);
    }

    @Test
    @DisplayName("runs split on gaps and know which edges carry on")
    void runSplitting() {
        boolean[] q = cols(0, 3);
        q[8] = true;
        q[9] = true;
        q[15] = true;
        List<TunnelRunGroups.Run> runs = TunnelRunGroups.runs(q, true, true);
        assertEquals(List.of(
            new TunnelRunGroups.Run(0, 3, true, false),
            new TunnelRunGroups.Run(8, 9, false, false),
            new TunnelRunGroups.Run(15, 15, false, true)), runs);
    }

    @Test
    @DisplayName("a tunnel generated west to east keeps the group its entrance rolled")
    void inOrderInheritsEntranceGroup() {
        Book book = new Book();
        KeyRoller roller = new KeyRoller();
        // Entrance at column 4 of chunk 0, then three whole chunks, exit in chunk 4.
        TemplateGroup entrance = resolve(book, 0, cols(4, 15), false, true, roller)[4];
        for (int cx = 1; cx <= 3; cx++) {
            TemplateGroup[] g = resolve(book, cx, cols(0, 15), true, true, roller);
            assertEquals(entrance, g[0]);
            assertEquals(entrance, g[15]);
        }
        assertEquals(entrance, resolve(book, 4, cols(0, 6), true, false, roller)[6]);
        assertEquals(List.of(4L), roller.keys, "only the entrance rolls");
    }

    @Test
    @DisplayName("a tunnel generated east to west inherits across the west edges too")
    void reverseOrderInherits() {
        Book book = new Book();
        KeyRoller roller = new KeyRoller();
        TemplateGroup exitChunk = resolve(book, 4, cols(0, 6), true, false, roller)[0];
        for (int cx = 3; cx >= 1; cx--) {
            assertEquals(exitChunk, resolve(book, cx, cols(0, 15), true, true, roller)[8]);
        }
        assertEquals(exitChunk, resolve(book, 0, cols(4, 15), false, true, roller)[4]);
        assertEquals(1, roller.keys.size(), "only the first middle chunk rolled");
    }

    @Test
    @DisplayName("a second chunk-Z row of the same chunk reuses that chunk's answer")
    void sameChunkOtherRow() {
        Book book = new Book();
        KeyRoller roller = new KeyRoller();
        TemplateGroup first = resolve(book, 7, cols(0, 15), true, true, roller)[3];
        TemplateGroup second = resolve(book, 7, cols(0, 15), true, true, roller)[3];
        assertEquals(first, second);
        assertEquals(1, roller.keys.size());
    }

    @Test
    @DisplayName("tunnels a stamp-length apart in one chunk roll separately, keyed by their own starts")
    void separateTunnels() {
        Book book = new Book();
        KeyRoller roller = new KeyRoller();
        boolean[] q = cols(0, 2);
        q[13] = q[14] = true;
        TemplateGroup[] g = resolve(book, 0, q, false, false, roller);
        assertEquals(List.of(0L, 13L), roller.keys);
        assertEquals(TemplateGroup.of("g0"), g[1]);
        assertEquals(TemplateGroup.of("gd"), g[13]);
        assertNull(g[6]);
    }

    @DisplayName("runs a few columns apart are one tunnel — a section stamp spans the gap")
    @Test
    void bridgesShortGapInChunk() {
        Book book = new Book();
        KeyRoller roller = new KeyRoller();
        boolean[] q = cols(1, 4);
        q[10] = q[11] = q[12] = true;
        TemplateGroup[] g = resolve(book, 0, q, false, false, roller);
        assertEquals(List.of(1L), roller.keys);
        assertEquals(g[1], g[12]);
    }

    @Test
    @DisplayName("a soft spot straddling a chunk edge does not split the tunnel")
    void bridgesShortGapAcrossChunks() {
        Book book = new Book();
        KeyRoller roller = new KeyRoller();
        // Chunk 0's run stops 3 columns short of its east edge; chunk 1's resumes at column 2.
        TemplateGroup west = resolve(book, 0, cols(4, 12), false, false, roller)[4];
        TemplateGroup east = resolve(book, 1, cols(2, 15), false, true, roller)[2];
        assertEquals(west, east);
        assertEquals(List.of(4L), roller.keys);
        // …and the other way round.
        Book book2 = new Book();
        KeyRoller roller2 = new KeyRoller();
        TemplateGroup east2 = resolve(book2, 1, cols(2, 15), false, true, roller2)[2];
        TemplateGroup west2 = resolve(book2, 0, cols(4, 12), false, false, roller2)[4];
        assertEquals(east2, west2);
        assertEquals(1, roller2.keys.size());
    }

    @Test
    @DisplayName("a gap of a stamp length or more across a chunk edge is a new tunnel")
    void wideGapAcrossChunksSplits() {
        Book book = new Book();
        KeyRoller roller = new KeyRoller();
        resolve(book, 0, cols(0, 9), false, false, roller);   // 6 short of the edge
        resolve(book, 1, cols(4, 15), false, true, roller);   // 4 in: 10 apart
        assertEquals(List.of(0L, 20L), roller.keys);
    }

    @Test
    @DisplayName("a roll with nothing to choose between means no group filter")
    void noFilter() {
        Book book = new Book();
        TemplateGroup[] g = resolve(book, 2, cols(0, 15), true, true, (key, x) -> null);
        assertNull(g[5]);
        // …and it is remembered as such, so a neighbour does not roll a real group into the tunnel.
        KeyRoller roller = new KeyRoller();
        assertNull(resolve(book, 3, cols(0, 15), true, true, roller)[5]);
        assertEquals(List.of(), roller.keys);
    }
}
