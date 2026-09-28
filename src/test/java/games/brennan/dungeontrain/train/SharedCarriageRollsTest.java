package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.train.SharedCarriageRolls.Bucket;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three-way shared-slot split: shipped ratio, boundaries, and walk-back determinism. */
class SharedCarriageRollsTest {

    private static final double POOL = 0.65;
    private static final double OWN = 0.30;

    @Test
    void theSameSlotAlwaysDecidesTheSameWay() {
        long seed = 0x1234_5678_9ABC_DEF0L;
        for (int pIdx = 0; pIdx < 50; pIdx++) {
            Bucket first = SharedCarriageRolls.bucket(seed, pIdx, POOL, OWN);
            assertEquals(first, SharedCarriageRolls.bucket(seed, pIdx, POOL, OWN),
                    "walking back over pIdx " + pIdx + " must re-decide identically");
        }
    }

    @Test
    void differentSlotsAndSeedsDecideIndependently() {
        long seed = 99L;
        // Not a strict guarantee of the algorithm, but a stream that produced one bucket for 200
        // consecutive slots would mean the mix had collapsed.
        boolean varies = false;
        Bucket first = SharedCarriageRolls.bucket(seed, 0, POOL, OWN);
        for (int pIdx = 1; pIdx < 200 && !varies; pIdx++) {
            varies = SharedCarriageRolls.bucket(seed, pIdx, POOL, OWN) != first;
        }
        assertTrue(varies, "consecutive slots must not all land in the same bucket");
    }

    @Test
    void everySlotFallsOnTheSideOfTheSplitItsRollPutsItOn() {
        // Stated as an invariant over the raw roll rather than sampled witnesses: the cut points are
        // exactly poolChance and poolChance+ownChance, with no gap or overlap between the three buckets.
        for (int pIdx = 0; pIdx < 5_000; pIdx++) {
            double r = SharedCarriageRolls.roll(11L, pIdx);
            Bucket expected = r < POOL ? Bucket.POOL : r < POOL + OWN ? Bucket.OWN : Bucket.FRESH;
            assertEquals(expected, SharedCarriageRolls.bucket(11L, pIdx, POOL, OWN),
                    "roll " + r + " at pIdx " + pIdx);
        }
    }

    @Test
    void everythingIsPoolWhenOwnAndFreshAreZeroed() {
        for (int pIdx = 0; pIdx < 100; pIdx++) {
            assertEquals(Bucket.POOL, SharedCarriageRolls.bucket(7L, pIdx, 1.0, 0.0));
        }
    }

    @Test
    void everythingIsFreshWhenBothRelayChancesAreZeroed() {
        for (int pIdx = 0; pIdx < 100; pIdx++) {
            assertEquals(Bucket.FRESH, SharedCarriageRolls.bucket(7L, pIdx, 0.0, 0.0));
        }
    }

    @Test
    void aLongTrainLandsNearTheShippedSixtyFiveThirtyFiveSplit() {
        Map<Bucket, Integer> counts = new EnumMap<>(Bucket.class);
        for (Bucket b : Bucket.values()) counts.put(b, 0);
        int n = 20_000;
        for (int pIdx = 0; pIdx < n; pIdx++) {
            Bucket b = SharedCarriageRolls.bucket(0xC0FFEEL, pIdx, POOL, OWN);
            counts.put(b, counts.get(b) + 1);
        }
        assertShare(counts.get(Bucket.POOL) / (double) n, POOL, "pool");
        assertShare(counts.get(Bucket.OWN) / (double) n, OWN, "own");
        assertShare(counts.get(Bucket.FRESH) / (double) n, 1.0 - POOL - OWN, "fresh");
    }

    /** Tolerance is loose enough not to be flaky, tight enough to catch a swapped or dropped share. */
    private static void assertShare(double actual, double expected, String what) {
        assertTrue(Math.abs(actual - expected) < 0.02,
                what + " share was " + actual + ", expected ~" + expected);
    }

    @Test
    void roomRollIsDeterministicIndependentOfTheBucketAndNearTheAskedRate() {
        long seed = 4242L;
        assertEquals(SharedCarriageRolls.roomDrifts(seed, 30, 0.05), SharedCarriageRolls.roomDrifts(seed, 30, 0.05));
        assertFalse(SharedCarriageRolls.roomDrifts(seed, 30, 0.0));
        assertTrue(SharedCarriageRolls.roomDrifts(seed, 30, 1.0));
        double chance = games.brennan.dungeontrain.config.DungeonTrainConfig.DEFAULT_SHARED_ROOM_CHANCE;
        assertEquals(1.0 / 15.0, chance, 1e-9, "one Bedrock Lock room in fifteen drifts by default");
        int drifting = 0, disagree = 0;
        for (int pair = 0; pair < 20_000; pair += 3) {
            boolean drifts = SharedCarriageRolls.roomDrifts(seed, pair, chance);
            if (drifts) drifting++;
            // Independent of the bucket roll: a pair that drifts is not simply "a pair whose bucket roll is low".
            if (drifts != (SharedCarriageRolls.roll(seed, pair) < chance)) disagree++;
        }
        double rate = drifting / (20_000 / 3.0);
        assertTrue(rate > 0.05 && rate < 0.085, "about one in fifteen, got " + rate);
        assertTrue(disagree > 0, "the room roll must not be the bucket roll in disguise");
    }
}
