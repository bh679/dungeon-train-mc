package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The chunk dimension prefetch samples for exactly the room planning rolls: the same function, the
 * same inputs, the same answer, whether asked once or a hundred ticks apart.
 */
class PortalChunkPrefetchTest {

    private static final long SEED = 0xC0FFEEL;
    private static final String CHUNK_ROOM = "chunk_3x3_wwoo";
    private static final String PLAIN = "plain";
    private static final String LIBRARY = "library_mix";
    private static final String AUTHOR_PARENT = "author_rooms";

    private static Map<String, Double> pool() {
        Map<String, Double> odds = new LinkedHashMap<>();
        odds.put(CHUNK_ROOM, 0.4);
        odds.put(PLAIN, 0.35);
        odds.put(LIBRARY, 0.15);
        odds.put(AUTHOR_PARENT, 0.1);
        return odds;
    }

    private static PortalRoomBooks booksOf(String room) {
        return LIBRARY.equals(room)
            ? new PortalRoomBooks(PortalRoomBooks.Kind.MIX, 1, 1, 0, 0,
                PortalRoomBooks.defaultMinBooks(), PortalRoomBooks.NO_MAXIMUM)
            : PortalRoomBooks.DEFAULT;
    }

    /** A stand-in for the seeded pick at any key. */
    private static String draw(long index) {
        double point = new Random(SEED ^ (index * 0x9E3779B97F4A7C15L)).nextDouble();
        double cumulative = 0.0;
        String last = null;
        for (Map.Entry<String, Double> leaf : pool().entrySet()) {
            cumulative += leaf.getValue();
            last = leaf.getKey();
            if (point < cumulative) return last;
        }
        return last;
    }

    private static PortalCarriageBuilder.RoomRoll roll(int pairKey, String remembered, double scale) {
        LongFunction<String> draw = PortalChunkPrefetchTest::draw;
        return PortalCarriageBuilder.rollRoomFrom(SEED, pairKey, remembered, draw,
            PortalChunkPrefetchTest::pool, PortalChunkPrefetchTest::booksOf, scale);
    }

    private static String prefetched(PortalOwnShelves.Outcome picked) {
        return PortalChunkPrefetch.roomToPrefetch(picked, AUTHOR_PARENT::equals, CHUNK_ROOM::equals);
    }

    /** What planning hands the sampler for a non-author pick: the rolled room's name. */
    private static String planned(PortalOwnShelves.Outcome picked) {
        return CHUNK_ROOM.equals(picked.roomName()) ? picked.roomName() : null;
    }

    @Test
    @DisplayName("Prefetch and planning land on the same room for every pair and library boost")
    void prefetchMatchesPlanning() {
        int chunkRooms = 0;
        for (double scale : new double[] {1.0, 0.5, 3.0}) {
            for (int pair = -2_000; pair < 2_000; pair++) {
                PortalOwnShelves.Outcome early = roll(pair, null, scale).picked();
                PortalOwnShelves.Outcome later = roll(pair, null, scale).picked();
                assertEquals(early, later, "pair " + pair + " rolled twice at scale " + scale);
                if (!AUTHOR_PARENT.equals(later.roomName())) {
                    assertEquals(planned(later), prefetched(early), "pair " + pair);
                }
                if (prefetched(early) != null) chunkRooms++;
            }
        }
        // The pool really does produce chunk rooms, so the equality above is not vacuous.
        assertEquals(true, chunkRooms > 1_000, "chunk rooms rolled: " + chunkRooms);
    }

    @Test
    @DisplayName("The extracted roll is the one planning made inline before it")
    void extractionChangesNothing() {
        for (int pair = -500; pair < 500; pair++) {
            for (double scale : new double[] {1.0, 0.25, 4.0}) {
                LongFunction<String> pickAt = PortalChunkPrefetchTest::draw;
                PortalOwnShelves.Outcome inline = PortalOwnShelves.adjust(SEED, pair, pickAt.apply(pair),
                    PortalChunkPrefetchTest::pool, PortalChunkPrefetchTest::booksOf, pickAt, scale);
                assertEquals(inline, roll(pair, null, scale).picked(), "pair " + pair);
            }
        }
    }

    @Test
    @DisplayName("A remembered visit-ruled room is what both roll, and the redraws still read the pool")
    void rememberedRoomWins() {
        PortalCarriageBuilder.RoomRoll roll = roll(7, CHUNK_ROOM, 1.0);
        assertEquals(CHUNK_ROOM, roll.picked().roomName());
        assertEquals(CHUNK_ROOM, roll.pickAt().apply(7));
        assertEquals(draw(8), roll.pickAt().apply(8));
        assertEquals(CHUNK_ROOM, prefetched(roll.picked()));
    }

    @Test
    @DisplayName("Author room parents and rooms that generate nothing are never prefetched")
    void skipsAuthorParentsAndPlainRooms() {
        assertNull(prefetched(new PortalOwnShelves.Outcome(AUTHOR_PARENT, false)));
        assertNull(prefetched(new PortalOwnShelves.Outcome(PLAIN, false)));
        assertNull(prefetched(new PortalOwnShelves.Outcome(LIBRARY, true)));
        assertNull(prefetched(null));
        assertEquals(CHUNK_ROOM, prefetched(new PortalOwnShelves.Outcome(CHUNK_ROOM, false)));
    }
}
