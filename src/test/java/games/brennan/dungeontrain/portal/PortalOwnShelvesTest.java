package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalRoomBooks.Kind;
import games.brennan.dungeontrain.portal.PortalRoomBooks.Share;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The own-books library's weight in the room lottery: how far it moves, and what pays for it. */
class PortalOwnShelvesTest {

    private static final long SEED = 0x5EEDL;
    private static final int PAIRS = 60_000;
    private static final double TOLERANCE = 0.01;

    private static final String PLAIN_A = "plain_a";
    private static final String PLAIN_B = "plain_b";
    private static final String LIBRARY_X = "library_x";
    private static final String LIBRARY_Y = "library_y";

    /** Two ordinary rooms and two libraries; the libraries carry 0.12 × ½ + 0.08 × ¾ = 0.12 own mass. */
    private static final Map<String, Double> POOL = pool();
    private static final double OWN_MASS = 0.12;

    private static Map<String, Double> pool() {
        Map<String, Double> odds = new LinkedHashMap<>();
        odds.put(PLAIN_A, 0.5);
        odds.put(PLAIN_B, 0.3);
        odds.put(LIBRARY_X, 0.12);
        odds.put(LIBRARY_Y, 0.08);
        return odds;
    }

    private static PortalRoomBooks booksOf(String room) {
        return switch (room) {
            case LIBRARY_X -> mix(1, 1);
            case LIBRARY_Y -> mix(3, 1);
            default -> PortalRoomBooks.DEFAULT;
        };
    }

    /** A library split between the reader's own books and a random player's, nothing else. */
    private static PortalRoomBooks mix(int self, int player) {
        return new PortalRoomBooks(Kind.MIX, self, player, 0, 0,
            PortalRoomBooks.defaultMinBooks(), PortalRoomBooks.NO_MAXIMUM);
    }

    /** A stand-in for the seeded pick: one room per index, drawn at the pool's odds. */
    private static String pickAt(Map<String, Double> odds, long index) {
        double point = new Random(SEED ^ (index * 0x9E3779B97F4A7C15L)).nextDouble();
        double cumulative = 0.0;
        String last = null;
        for (Map.Entry<String, Double> leaf : odds.entrySet()) {
            cumulative += leaf.getValue();
            last = leaf.getKey();
            if (point < cumulative) return last;
        }
        return last;
    }

    private static PortalOwnShelves.Outcome adjust(Map<String, Double> odds, int pairKey, double scale) {
        return PortalOwnShelves.adjust(SEED, pairKey, pickAt(odds, pairKey), () -> odds,
            PortalOwnShelvesTest::booksOf, index -> pickAt(odds, index), scale);
    }

    private static boolean isOwn(PortalOwnShelves.Outcome outcome, int pairKey) {
        return outcome.pinnedToSelf()
            || PortalOwnShelves.isOwn(booksOf(outcome.roomName()), pairKey);
    }

    private static double ownRate(double scale) {
        int own = 0;
        for (int pair = 0; pair < PAIRS; pair++) {
            if (isOwn(adjust(POOL, pair, scale), pair)) own++;
        }
        return (double) own / PAIRS;
    }

    /** What a weight multiplied by {@code scale} comes to as a share of the whole pool. */
    private static double expected(double scale) {
        return scale * OWN_MASS / (1.0 - OWN_MASS + scale * OWN_MASS);
    }

    @Test
    @DisplayName("The pool's own-books mass is each library's odds times its Self share")
    void ownMassIsOddsTimesSelfShare() {
        assertEquals(OWN_MASS, PortalOwnShelves.ownMass(POOL, PortalOwnShelvesTest::booksOf), 1.0e-9);
        assertEquals(0.0, PortalOwnShelves.selfFraction(PortalRoomBooks.DEFAULT), 0.0);
        assertEquals(0.75, PortalOwnShelves.selfFraction(mix(3, 1)), 1.0e-9);
    }

    @Test
    @DisplayName("A scale of 1 is the pick exactly as it was")
    void scaleOfOneIsTheIdentity() {
        for (int pair = -500; pair <= 500; pair++) {
            PortalOwnShelves.Outcome outcome = adjust(POOL, pair, 1.0);
            assertEquals(pickAt(POOL, pair), outcome.roomName(), "pair " + pair);
            assertFalse(outcome.pinnedToSelf(), "pair " + pair);
        }
        assertEquals(OWN_MASS, ownRate(1.0), TOLERANCE);
    }

    @Test
    @DisplayName("Half weight and triple weight land where a weight change would put them")
    void scaleMovesTheWeightNotTheProbability() {
        assertEquals(expected(0.5), ownRate(0.5), TOLERANCE);
        assertEquals(expected(3.0), ownRate(3.0), TOLERANCE);
        assertTrue(ownRate(0.5) < ownRate(1.0) && ownRate(1.0) < ownRate(3.0));
    }

    @Test
    @DisplayName("A scale of 0 never answers an own-books library")
    void scaleOfZeroIsNever() {
        assertEquals(0.0, ownRate(0.0), 0.0);
    }

    @Test
    @DisplayName("Every other outcome keeps its share of what is left")
    void theRestKeepsItsProportions() {
        for (double scale : new double[] {0.5, 1.0, 3.0}) {
            int plainA = 0;
            int notOwn = 0;
            for (int pair = 0; pair < PAIRS; pair++) {
                PortalOwnShelves.Outcome outcome = adjust(POOL, pair, scale);
                if (isOwn(outcome, pair)) continue;
                notOwn++;
                if (PLAIN_A.equals(outcome.roomName())) plainA++;
            }
            assertEquals(0.5 / (1.0 - OWN_MASS), (double) plainA / notOwn, TOLERANCE, "scale " + scale);
        }
    }

    @Test
    @DisplayName("A pair that is own-books at a low scale is own-books at every higher one")
    void theScalesNest() {
        for (int pair = 0; pair < PAIRS; pair++) {
            boolean low = isOwn(adjust(POOL, pair, 0.5), pair);
            boolean plain = isOwn(adjust(POOL, pair, 1.0), pair);
            boolean high = isOwn(adjust(POOL, pair, 3.0), pair);
            assertTrue(!low || plain, "pair " + pair + " is own at 0.5 but not at 1");
            assertTrue(!plain || high, "pair " + pair + " is own at 1 but not at 3");
        }
    }

    @Test
    @DisplayName("The answer for a pair is the same every time it is asked")
    void theAnswerIsStable() {
        for (int pair = -200; pair <= 200; pair++) {
            for (double scale : new double[] {0.5, 3.0}) {
                assertEquals(adjust(POOL, pair, scale), adjust(POOL, pair, scale), "pair " + pair);
            }
        }
    }

    @Test
    @DisplayName("A promoted pair is a library, pinned to Self")
    void promotionPicksALibrary() {
        int promoted = 0;
        for (int pair = 0; pair < PAIRS; pair++) {
            PortalOwnShelves.Outcome outcome = adjust(POOL, pair, 3.0);
            if (!outcome.pinnedToSelf()) continue;
            promoted++;
            assertTrue(booksOf(outcome.roomName()).locks(), "pair " + pair + " pinned a room with no books");
        }
        assertTrue(promoted > 0, "nothing was promoted at triple weight");
    }

    @Test
    @DisplayName("A pool with no library is left alone at any scale")
    void aPoolWithNoLibraryIsUntouched() {
        Map<String, Double> plain = new LinkedHashMap<>();
        plain.put(PLAIN_A, 0.6);
        plain.put(PLAIN_B, 0.4);
        for (int pair = -300; pair <= 300; pair++) {
            for (double scale : new double[] {0.0, 0.5, 3.0}) {
                PortalOwnShelves.Outcome outcome = adjust(plain, pair, scale);
                assertEquals(pickAt(plain, pair), outcome.roomName(), "pair " + pair);
                assertFalse(outcome.pinnedToSelf(), "pair " + pair);
            }
        }
    }

    @Test
    @DisplayName("Pinning keeps the room's book range and always rolls Self")
    void selfOnlyAlwaysRollsSelf() {
        PortalRoomBooks authored = new PortalRoomBooks(Kind.MIX, 3, 1, 1, 1, 24, 120);
        PortalRoomBooks pinned = PortalOwnShelves.selfOnly(authored);
        assertEquals(24, pinned.minBooks());
        assertEquals(120, pinned.maxBooks());
        assertTrue(pinned.locks());
        for (int pair = -400; pair <= 400; pair++) {
            assertSame(Share.SELF, pinned.resolveShare(pair), "pair " + pair);
        }
    }
}
