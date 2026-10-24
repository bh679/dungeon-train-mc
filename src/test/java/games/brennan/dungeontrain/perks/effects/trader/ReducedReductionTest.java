package games.brennan.dungeontrain.perks.effects.trader;

import games.brennan.tradeeverything.trade.DemandCurve;
import games.brennan.tradeeverything.trade.DemandLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure maths of Reduced Reduction, plus its effect on a real Trade Everything ledger. */
final class ReducedReductionTest {

    /** Any item id; the ledger never looks it up. */
    private static final String WHEAT = "minecraft:wheat";
    /**
     * Half an emerald each: well above the curve's 1/64-emerald floor (an item worth no more
     * than that never loses price), so 40 sales of 8 clear the 8 free emeralds many times over.
     */
    private static final double WHEAT_EMERALDS = 0.5;

    @Test
    @DisplayName("rank 0 remembers the whole sale")
    void rankZeroIsFullWeight() {
        assertEquals(1.0, ReducedReduction.saleWeight(0));
        assertEquals(1.0, ReducedReduction.saleWeight(-3));
    }

    @Test
    @DisplayName("rank 5 tires the villager half as fast")
    void halfRateAtRankFive() {
        assertEquals(0.5, ReducedReduction.saleWeight(ReducedReduction.HALF_RATE_RANK), 1e-12);
        assertEquals(0.5, ReducedReduction.saleWeight(5), 1e-12);
    }

    @Test
    @DisplayName("weight keeps falling with rank, never reaching 25%")
    void monotoneTowardsFloor() {
        for (int rank = 1; rank <= 100; rank++) {
            double w = ReducedReduction.saleWeight(rank);
            assertTrue(w < ReducedReduction.saleWeight(rank - 1), "rank " + rank);
            assertTrue(w > ReducedReduction.MIN_WEIGHT, "rank " + rank);
        }
        assertEquals(ReducedReduction.MIN_WEIGHT, ReducedReduction.saleWeight(Integer.MAX_VALUE), 1e-9);
    }

    @Test
    @DisplayName("scaledSold keeps the old tally and only a share of the gain")
    void scaledSoldArithmetic() {
        assertEquals(10.0 + 4.0 * 0.5, ReducedReduction.scaledSold(10.0, 14.0, 0.5), 1e-12);
        assertEquals(10.0, ReducedReduction.scaledSold(10.0, 8.0, 0.5), "a tally that fell is not scaled below itself");
    }

    @Test
    @DisplayName("weight 1 hands the ledger back untouched")
    void fullWeightIsIdentity() {
        DemandCurve curve = DemandCurve.defaults();
        DemandLedger after = DemandLedger.EMPTY.withSale(WHEAT, 8, WHEAT_EMERALDS, 0L, curve);
        assertSame(after, ReducedReduction.scaledLedger(WHEAT, 0.0, after, 1.0));
        assertSame(after, ReducedReduction.scaledLedger("minecraft:stick", 0.0, after, 0.5));
    }

    @Test
    @DisplayName("a rank-5 villager prices N sales like an unperked villager prices N/2")
    void halfWeightHalvesTheTally() {
        DemandCurve curve = DemandCurve.defaults();
        double weight = ReducedReduction.saleWeight(5);
        DemandLedger perked = DemandLedger.EMPTY;
        DemandLedger plain = DemandLedger.EMPTY;
        int sales = 40;
        for (int i = 0; i < sales; i++) {
            double prev = perked.soldEmeralds(WHEAT, 0L, curve);
            perked = ReducedReduction.scaledLedger(WHEAT, prev, perked.withSale(WHEAT, 8, WHEAT_EMERALDS, 0L, curve), weight);
            if (i % 2 == 0) plain = plain.withSale(WHEAT, 8, WHEAT_EMERALDS, 0L, curve);
        }
        assertEquals(plain.soldEmeralds(WHEAT, 0L, curve), perked.soldEmeralds(WHEAT, 0L, curve), 1e-9);
        assertEquals(plain.priceFraction(WHEAT, WHEAT_EMERALDS, 0L, curve),
            perked.priceFraction(WHEAT, WHEAT_EMERALDS, 0L, curve), 1e-9);
        assertTrue(perked.priceFraction(WHEAT, WHEAT_EMERALDS, 0L, curve) < DemandCurve.FULL,
            "160 emeralds of trade should still tire a rank-2 villager");
    }
}
