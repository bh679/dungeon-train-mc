package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalRoomBooks.Kind;
import games.brennan.dungeontrain.portal.PortalRoomBooks.Share;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The library setting: its tokens, its totality, its weighted roll and the band of author it accepts. */
class PortalRoomBooksTest {

    /** The configured floor a freshly authored room starts at — read, not assumed, so the test tracks it. */
    private static final int DEFAULT_MIN = PortalRoomBooks.defaultMinBooks();

    private static PortalRoomBooks mix(int self, int player, int signature) {
        return new PortalRoomBooks(Kind.MIX, self, player, signature, DEFAULT_MIN,
            PortalRoomBooks.NO_MAXIMUM);
    }

    // ---- kinds ----

    @Test
    @DisplayName("Kind ids round-trip and are unique — the stored tag is these strings")
    void kindIdsRoundTrip() {
        Set<String> seen = new HashSet<>();
        for (Kind k : Kind.values()) {
            assertTrue(seen.add(k.id()), "duplicate id " + k.id());
            assertSame(k, Kind.parse(k.id()));
            assertSame(k, Kind.parse(k.id().toUpperCase(java.util.Locale.ROOT)));
            assertSame(k, Kind.parse("  " + k.id() + " "));
        }
    }

    @Test
    @DisplayName("Parsing is total — a hand-edited typo leaves the room unlocked, never fails a stamp")
    void parseIsTotal() {
        assertSame(Kind.OFF, PortalRoomBooks.parse(null).kind());
        assertSame(Kind.OFF, PortalRoomBooks.parse("").kind());
        assertSame(Kind.OFF, PortalRoomBooks.parse("   ").kind());
        assertSame(Kind.OFF, PortalRoomBooks.parse("mixx").kind());

        // A number that is not a number falls back rather than taking the segment down with it.
        PortalRoomBooks garbled = PortalRoomBooks.parse("mix:two:3:x:ten:40");
        assertSame(Kind.MIX, garbled.kind());
        assertEquals(PortalRoomBooks.DEFAULT_WEIGHT, garbled.selfWeight());
        assertEquals(3, garbled.playerWeight());
        assertEquals(PortalRoomBooks.DEFAULT_WEIGHT, garbled.signatureWeight());
        assertEquals(DEFAULT_MIN, garbled.minBooks());
        assertEquals(40, garbled.maxBooks());
    }

    @Test
    @DisplayName("Off is the default and the only kind that does not stock an author")
    void offIsTheOnlyUnlockedKind() {
        assertSame(Kind.OFF, PortalRoomBooks.DEFAULT.kind());
        assertFalse(PortalRoomBooks.DEFAULT.locks());
        assertTrue(new PortalRoomBooks(Kind.MIX).locks());
    }

    @Test
    @DisplayName("The editor button walks both kinds and comes back round, keeping everything else")
    void nextCyclesThroughEverything() {
        PortalRoomBooks at = new PortalRoomBooks(Kind.OFF, 4, 5, 6, 7, 8);
        Set<Kind> walked = new HashSet<>();
        for (int i = 0; i < Kind.values().length; i++) {
            assertTrue(walked.add(at.kind()), "cycle revisited " + at.kind() + " early");
            at = at.next();
            assertEquals(4, at.selfWeight(), "stepping the kind must not disturb the weights");
            assertEquals(7, at.minBooks(), "...nor the band of author");
            assertEquals(8, at.maxBooks());
        }
        assertEquals(Kind.values().length, walked.size());
        assertSame(Kind.OFF, at.kind(), "the cycle must return to where it started");
    }

    // ---- the stored segment ----

    @Test
    @DisplayName("Numbers are written only when they say something the defaults do not")
    void numbersAreWrittenOnlyWhenTheyMatter() {
        assertEquals("off", PortalRoomBooks.DEFAULT.id());
        assertEquals("off", new PortalRoomBooks(Kind.OFF, 5, 2, 9, 3, 4).id(),
            "an unlocked room's dials mean nothing and would be a migration for nothing");

        assertEquals("mix", new PortalRoomBooks(Kind.MIX).id());
        assertEquals("mix", PortalRoomBooks.parse("mix:1:1:1").id());
        assertEquals("mix:2:1:1", mix(2, 1, 1).id());
        assertEquals("mix:0:3:7", mix(0, 3, 7).id());

        // A band forces the weights in front of it, at their defaults if that is what they are.
        assertEquals("mix:1:1:1:5:40",
            new PortalRoomBooks(Kind.MIX, 1, 1, 1, 5, 40).id());
        assertEquals("mix:2:3:1:5:0",
            new PortalRoomBooks(Kind.MIX, 2, 3, 1, 5, PortalRoomBooks.NO_MAXIMUM).id());
    }

    @Test
    @DisplayName("Every kind, weighting and band round-trips through its segment")
    void segmentsRoundTrip() {
        for (Kind k : Kind.values()) {
            for (int[] v : new int[][]{{1, 1, 1, DEFAULT_MIN, 0}, {0, 1, 1, DEFAULT_MIN, 0},
                    {5, 0, 2, 3, 12}, {99, 99, 99, 999, 999}, {0, 0, 0, 0, 0}}) {
                PortalRoomBooks original = new PortalRoomBooks(k, v[0], v[1], v[2], v[3], v[4]);
                PortalRoomBooks back = PortalRoomBooks.parse(original.id());
                assertSame(k, back.kind(), original.id());
                // The numbers survive only where they mean something, which is where they are written.
                if (original.locks()) {
                    assertEquals(original, back, original.id());
                }
            }
        }
    }

    @Test
    @DisplayName("The single-share tokens this setting briefly had still mean what they said")
    void developmentEraTokensStillRead() {
        // None ever shipped, but a room authored during development should not silently unlock.
        for (Share share : Share.values()) {
            PortalRoomBooks read = PortalRoomBooks.parse(share.id());
            assertSame(Kind.MIX, read.kind(), share.id());
            assertEquals(1, read.weightFor(share), share.id());
            for (Share other : Share.values()) {
                if (other != share) assertEquals(0, read.weightFor(other), share.id());
            }
        }
    }

    @Test
    @DisplayName("`random`, what Mix was briefly called, still reads as Mix")
    void theOldMixTokenStillReads() {
        // A room authored during development carries `random` in its tag. Reading that as Off would
        // silently turn somebody's library back into an ordinary room without telling them.
        assertSame(Kind.MIX, PortalRoomBooks.parse("random").kind());
        PortalRoomBooks weighted = PortalRoomBooks.parse("random:2:3:4");
        assertSame(Kind.MIX, weighted.kind());
        assertEquals(2, weighted.selfWeight());
        assertEquals(3, weighted.playerWeight());
        assertEquals(4, weighted.signatureWeight());
        // It is re-written under the current name, so the old spelling dies out on the next save.
        assertEquals("mix:2:3:4", weighted.id());
    }

    @Test
    @DisplayName("Weights and bounds are clamped rather than trusted")
    void numbersAreClamped() {
        PortalRoomBooks clamped = new PortalRoomBooks(Kind.MIX, -5, 1000, 3, -1, 100000);
        assertEquals(PortalRoomBooks.MIN_WEIGHT, clamped.selfWeight());
        assertEquals(PortalRoomBooks.MAX_WEIGHT, clamped.playerWeight());
        assertEquals(3, clamped.signatureWeight());
        assertEquals(PortalRoomBooks.MIN_BOOK_BOUND, clamped.minBooks());
        assertEquals(PortalRoomBooks.MAX_BOOK_BOUND, clamped.maxBooks());
    }

    // ---- which authors qualify ----

    @Test
    @DisplayName("An author must clear the floor, strictly")
    void minimumIsStrictlyMoreThan() {
        PortalRoomBooks books = new PortalRoomBooks(Kind.MIX, 1, 1, 1, 10, PortalRoomBooks.NO_MAXIMUM);
        assertFalse(books.accepts(9));
        assertFalse(books.accepts(10), "ten means an eleventh book is needed");
        assertTrue(books.accepts(11));
        assertTrue(books.accepts(500), "no ceiling means no ceiling");
    }

    @Test
    @DisplayName("A ceiling keeps a modest room modest, and zero means there is none")
    void maximumBoundsTheOtherEnd() {
        PortalRoomBooks banded = new PortalRoomBooks(Kind.MIX, 1, 1, 1, 5, 20);
        assertFalse(banded.accepts(5));
        assertTrue(banded.accepts(6));
        assertTrue(banded.accepts(20));
        assertFalse(banded.accepts(21));

        PortalRoomBooks open = new PortalRoomBooks(Kind.MIX, 1, 1, 1, 5, PortalRoomBooks.NO_MAXIMUM);
        assertTrue(open.accepts(9999));
    }

    // ---- the roll ----

    @Test
    @DisplayName("A zero weight takes that share out of the roll entirely")
    void zeroWeightIsExcluded() {
        PortalRoomBooks noSelf = mix(0, 1, 1);
        for (long seed = 0; seed < 500; seed++) {
            assertFalse(noSelf.resolveShare(seed) == Share.SELF, "seed " + seed + " rolled Self");
        }

        // ...and a single non-zero weight makes the roll a certainty — this is how a room says
        // "always my own writing", which is why there is no separate setting for it.
        PortalRoomBooks onlySelf = mix(4, 0, 0);
        for (long seed = 0; seed < 200; seed++) {
            assertSame(Share.SELF, onlySelf.resolveShare(seed));
        }
    }

    @Test
    @DisplayName("All-zero weights roll evenly rather than resolving to nothing")
    void allZeroWeightsRollUniformly() {
        // An author who zeroes all three has said something contradictory; the useful reading is "no
        // preference". A room that could not pick an author because of arithmetic would look broken.
        Map<Share, Integer> counts = tally(mix(0, 0, 0), 3000);
        for (Share share : Share.values()) {
            assertTrue(counts.getOrDefault(share, 0) > 0, share + " never came up");
        }
    }

    @Test
    @DisplayName("The roll honours the weights it was given")
    void rollFollowsTheWeights() {
        Map<Share, Integer> counts = tally(mix(8, 1, 1), 3000);
        int self = counts.getOrDefault(Share.SELF, 0);
        int player = counts.getOrDefault(Share.PLAYER, 0);
        int signature = counts.getOrDefault(Share.SIGNATURE, 0);
        assertTrue(self > player * 3, "self " + self + " vs player " + player);
        assertTrue(self > signature * 3, "self " + self + " vs signature " + signature);
        assertTrue(player > 0 && signature > 0, "an unweighted share must still be reachable");
    }

    @Test
    @DisplayName("The same room rolls the same answer every time it is asked")
    void theRollIsStablePerSeed() {
        PortalRoomBooks books = mix(3, 4, 5);
        for (long seed = 0; seed < 50; seed++) {
            Share first = books.resolveShare(seed);
            for (int repeat = 0; repeat < 5; repeat++) {
                assertSame(first, books.resolveShare(seed), "seed " + seed + " is not stable");
            }
        }
    }

    @Test
    @DisplayName("A share reads and writes through the same accessor")
    void weightForRoundTrips() {
        PortalRoomBooks books = mix(1, 2, 3);
        assertEquals(1, books.weightFor(Share.SELF));
        assertEquals(2, books.weightFor(Share.PLAYER));
        assertEquals(3, books.weightFor(Share.SIGNATURE));
        assertEquals(9, books.withWeightFor(Share.PLAYER, 9).weightFor(Share.PLAYER));
        // ...and setting one leaves the other two alone.
        assertEquals(1, books.withWeightFor(Share.PLAYER, 9).weightFor(Share.SELF));
        assertEquals(3, books.withWeightFor(Share.PLAYER, 9).weightFor(Share.SIGNATURE));
    }

    @Test
    @DisplayName("Every kind and share has a label for the editor")
    void everythingHasALabel() {
        for (Kind k : Kind.values()) assertFalse(k.displayName().isBlank(), k.id());
        for (Share s : Share.values()) assertFalse(s.displayName().isBlank(), s.id());
    }

    /** How often each share comes up across {@code seeds} consecutive room keys. */
    private static Map<Share, Integer> tally(PortalRoomBooks books, int seeds) {
        Map<Share, Integer> counts = new EnumMap<>(Share.class);
        for (long seed = 0; seed < seeds; seed++) {
            counts.merge(books.resolveShare(seed), 1, Integer::sum);
        }
        return counts;
    }
}
