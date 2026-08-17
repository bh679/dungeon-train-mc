package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalRoomBooks.Kind;
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

/** The author-lock setting: its tokens, its totality, its cycle, and the weighted roll behind Random. */
class PortalRoomBooksTest {

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
        assertSame(PortalRoomBooks.DEFAULT, PortalRoomBooks.parse(null));
        assertSame(Kind.OFF, PortalRoomBooks.parse("").kind());
        assertSame(Kind.OFF, PortalRoomBooks.parse("   ").kind());
        assertSame(Kind.OFF, PortalRoomBooks.parse("signatre").kind());
        assertSame(Kind.OFF, PortalRoomBooks.parse("random_player").kind());

        // A weight that is not a number falls back rather than taking the segment down with it.
        PortalRoomBooks garbled = PortalRoomBooks.parse("random:two:3:x");
        assertSame(Kind.RANDOM, garbled.kind());
        assertEquals(PortalRoomBooks.DEFAULT_WEIGHT, garbled.selfWeight());
        assertEquals(3, garbled.playerWeight());
        assertEquals(PortalRoomBooks.DEFAULT_WEIGHT, garbled.signatureWeight());
    }

    @Test
    @DisplayName("Off is the default and the only kind that does not lock")
    void offIsTheOnlyUnlockedKind() {
        assertSame(Kind.OFF, PortalRoomBooks.DEFAULT.kind());
        assertFalse(PortalRoomBooks.DEFAULT.locks());
        for (Kind k : Kind.values()) {
            if (k != Kind.OFF) assertTrue(k.locks(), k.id() + " should lock");
        }
    }

    @Test
    @DisplayName("Each kind asks the relay directory for the kind it means")
    void directoryKindMatchesTheSetting() {
        assertEquals("self", Kind.SELF.directoryKind());
        assertEquals("player", Kind.PLAYER.directoryKind());
        assertEquals("signature", Kind.SIGNATURE.directoryKind());
        // Off and Random never ask — callers gate on locks() and resolve Random first — but both must
        // still name a real kind rather than put null into a URL.
        assertEquals("player", Kind.OFF.directoryKind());
        assertEquals("player", Kind.RANDOM.directoryKind());
    }

    @Test
    @DisplayName("The editor button walks every kind and comes back round, keeping the weights")
    void nextCyclesThroughEverything() {
        Set<Kind> walked = new HashSet<>();
        PortalRoomBooks at = new PortalRoomBooks(Kind.OFF, 4, 5, 6);
        for (int i = 0; i < Kind.values().length; i++) {
            assertTrue(walked.add(at.kind()), "cycle revisited " + at.kind() + " early");
            at = at.next();
            assertEquals(4, at.selfWeight(), "stepping the kind must not disturb the weights");
            assertEquals(5, at.playerWeight());
            assertEquals(6, at.signatureWeight());
        }
        assertEquals(Kind.values().length, walked.size());
        assertSame(Kind.OFF, at.kind(), "the cycle must return to where it started");
    }

    // ---- the stored segment ----

    @Test
    @DisplayName("The four kinds that predate Random still write the bare token they always did")
    void plainKindsWriteBareTokens() {
        for (Kind k : new Kind[]{Kind.OFF, Kind.SELF, Kind.PLAYER, Kind.SIGNATURE}) {
            // Even carrying weights: they mean nothing off Random, so writing them would put a
            // migration in front of every tag already on disk for no gain.
            assertEquals(k.id(), new PortalRoomBooks(k, 7, 2, 9).id(), k.id());
        }
    }

    @Test
    @DisplayName("Random writes its weights only when they are not all even")
    void randomWritesWeightsOnlyWhenTheyMatter() {
        assertEquals("random", new PortalRoomBooks(Kind.RANDOM).id());
        assertEquals("random", PortalRoomBooks.parse("random:1:1:1").id());
        assertEquals("random:2:1:1", new PortalRoomBooks(Kind.RANDOM, 2, 1, 1).id());
        assertEquals("random:0:3:7", new PortalRoomBooks(Kind.RANDOM, 0, 3, 7).id());
    }

    @Test
    @DisplayName("Every kind and weighting round-trips through its segment")
    void segmentsRoundTrip() {
        for (Kind k : Kind.values()) {
            for (int[] w : new int[][]{{1, 1, 1}, {0, 1, 1}, {5, 0, 2}, {99, 99, 99}, {0, 0, 0}}) {
                PortalRoomBooks original = new PortalRoomBooks(k, w[0], w[1], w[2]);
                PortalRoomBooks back = PortalRoomBooks.parse(original.id());
                assertSame(k, back.kind(), original.id());
                // Weights only survive where they mean something, which is exactly where they are written.
                if (original.weightsApply()) {
                    assertEquals(original.selfWeight(), back.selfWeight(), original.id());
                    assertEquals(original.playerWeight(), back.playerWeight(), original.id());
                    assertEquals(original.signatureWeight(), back.signatureWeight(), original.id());
                }
            }
        }
    }

    @Test
    @DisplayName("Weights are clamped rather than trusted")
    void weightsAreClamped() {
        PortalRoomBooks clamped = new PortalRoomBooks(Kind.RANDOM, -5, 1000, 3);
        assertEquals(PortalRoomBooks.MIN_WEIGHT, clamped.selfWeight());
        assertEquals(PortalRoomBooks.MAX_WEIGHT, clamped.playerWeight());
        assertEquals(3, clamped.signatureWeight());
    }

    // ---- the roll ----

    @Test
    @DisplayName("Everything but Random answers itself, whatever the weights say")
    void plainKindsResolveToThemselves() {
        for (Kind k : new Kind[]{Kind.OFF, Kind.SELF, Kind.PLAYER, Kind.SIGNATURE}) {
            for (long seed = 0; seed < 20; seed++) {
                assertSame(k, new PortalRoomBooks(k, 9, 1, 1).resolveKind(seed));
            }
        }
    }

    @Test
    @DisplayName("Random never rolls Off — a Random room is always somebody's library")
    void randomNeverRollsOff() {
        PortalRoomBooks books = new PortalRoomBooks(Kind.RANDOM);
        for (long seed = 0; seed < 500; seed++) {
            Kind rolled = books.resolveKind(seed);
            assertTrue(rolled.locks(), "seed " + seed + " rolled " + rolled);
            assertTrue(rolled == Kind.SELF || rolled == Kind.PLAYER || rolled == Kind.SIGNATURE);
        }
    }

    @Test
    @DisplayName("A zero weight takes that kind out of the roll entirely")
    void zeroWeightIsExcluded() {
        PortalRoomBooks noSelf = new PortalRoomBooks(Kind.RANDOM, 0, 1, 1);
        for (long seed = 0; seed < 500; seed++) {
            assertFalse(noSelf.resolveKind(seed) == Kind.SELF, "seed " + seed + " rolled Self");
        }

        // ...and a single non-zero weight makes the roll a certainty.
        PortalRoomBooks onlySignature = new PortalRoomBooks(Kind.RANDOM, 0, 0, 4);
        for (long seed = 0; seed < 200; seed++) {
            assertSame(Kind.SIGNATURE, onlySignature.resolveKind(seed));
        }
    }

    @Test
    @DisplayName("All-zero weights roll evenly rather than resolving to nothing")
    void allZeroWeightsRollUniformly() {
        // An author who zeroes all three has said something contradictory; the useful reading is "no
        // preference". A room that could not pick an author because of arithmetic would look broken.
        Map<Kind, Integer> counts = tally(new PortalRoomBooks(Kind.RANDOM, 0, 0, 0), 3000);
        for (Kind k : new Kind[]{Kind.SELF, Kind.PLAYER, Kind.SIGNATURE}) {
            assertTrue(counts.getOrDefault(k, 0) > 0, k + " never came up");
        }
    }

    @Test
    @DisplayName("The roll honours the weights it was given")
    void rollFollowsTheWeights() {
        // Eight parts Self to one each of the others: Self should dominate by a wide margin, and the
        // other two should still both appear.
        Map<Kind, Integer> counts = tally(new PortalRoomBooks(Kind.RANDOM, 8, 1, 1), 3000);
        int self = counts.getOrDefault(Kind.SELF, 0);
        int player = counts.getOrDefault(Kind.PLAYER, 0);
        int signature = counts.getOrDefault(Kind.SIGNATURE, 0);
        assertTrue(self > player * 3, "self " + self + " vs player " + player);
        assertTrue(self > signature * 3, "self " + self + " vs signature " + signature);
        assertTrue(player > 0 && signature > 0, "an unweighted kind must still be reachable");
    }

    @Test
    @DisplayName("The same room rolls the same answer every time it is asked")
    void theRollIsStablePerSeed() {
        PortalRoomBooks books = new PortalRoomBooks(Kind.RANDOM, 3, 4, 5);
        for (long seed = 0; seed < 50; seed++) {
            Kind first = books.resolveKind(seed);
            for (int repeat = 0; repeat < 5; repeat++) {
                assertSame(first, books.resolveKind(seed), "seed " + seed + " is not stable");
            }
        }
    }

    @Test
    @DisplayName("Only Random has weights to step")
    void weightsApplyOnlyToRandom() {
        assertTrue(new PortalRoomBooks(Kind.RANDOM).weightsApply());
        for (Kind k : new Kind[]{Kind.OFF, Kind.SELF, Kind.PLAYER, Kind.SIGNATURE}) {
            assertFalse(new PortalRoomBooks(k).weightsApply(), k.id());
        }
    }

    @Test
    @DisplayName("A share reads and writes through the same accessor")
    void weightForRoundTrips() {
        PortalRoomBooks books = new PortalRoomBooks(Kind.RANDOM, 1, 2, 3);
        assertEquals(1, books.weightFor(Kind.SELF));
        assertEquals(2, books.weightFor(Kind.PLAYER));
        assertEquals(3, books.weightFor(Kind.SIGNATURE));
        assertEquals(9, books.withWeightFor(Kind.PLAYER, 9).weightFor(Kind.PLAYER));
        // A kind with no share of its own is left alone rather than writing somewhere arbitrary.
        assertSame(books, books.withWeightFor(Kind.RANDOM, 9));
    }

    @Test
    @DisplayName("Every kind has a label for the editor row")
    void everyKindHasALabel() {
        for (Kind k : Kind.values()) {
            assertFalse(k.displayName().isBlank(), k.id() + " has no label");
        }
    }

    /** How often each kind comes up across {@code seeds} consecutive room keys. */
    private static Map<Kind, Integer> tally(PortalRoomBooks books, int seeds) {
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        for (long seed = 0; seed < seeds; seed++) {
            counts.merge(books.resolveKind(seed), 1, Integer::sum);
        }
        return counts;
    }
}
