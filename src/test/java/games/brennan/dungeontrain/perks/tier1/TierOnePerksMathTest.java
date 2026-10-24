package games.brennan.dungeontrain.perks.tier1;

import games.brennan.dungeontrain.client.TradeValueTooltip;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure calculations behind the nine tier-1 perks, pinned so retuning is deliberate. */
final class TierOnePerksMathTest {

    @Test
    @DisplayName("attribute perks: +1 health, +1% speed, +0.1 damage per rank; uncapped, never negative")
    void attributeAmounts() {
        assertEquals(0.0, AttributePerks.healthBonus(0));
        assertEquals(3.0, AttributePerks.healthBonus(3));
        assertEquals(200.0, AttributePerks.healthBonus(200));
        assertEquals(0.0, AttributePerks.healthBonus(-2));
        assertEquals(0.10, AttributePerks.speedMultiplierBonus(10), 1e-9);
        assertEquals(0.4, AttributePerks.damageBonus(4), 1e-9);
    }

    @Test
    @DisplayName("book and quill stack: 1 + rank, up to the game's own 99")
    void bookStack() {
        assertEquals(1, BookQuillStacking.stackSizeFor(0));
        assertEquals(4, BookQuillStacking.stackSizeFor(3));
        assertEquals(41, BookQuillStacking.stackSizeFor(40));
        assertEquals(99, BookQuillStacking.stackSizeFor(500));
    }

    @Test
    @DisplayName("friendliness: each rank closes 6% of the feeling gap, never full; tame gets rank extra rolls")
    void friendliness() {
        assertEquals(5.0f, Friendliness.feelingFor(5f, 0, 10f), 1e-6);
        assertEquals(5.3f, Friendliness.feelingFor(5f, 1, 10f), 1e-5);
        assertEquals(7.31f, Friendliness.feelingFor(5f, 10, 10f), 0.01);
        assertTrue(Friendliness.feelingFor(5f, 200, 10f) < 10f, "never reaches the top");
        assertEquals(10f, Friendliness.feelingFor(10f, 5, 10f), 1e-6);
        assertEquals(1.0 / 3, Friendliness.tameChance(3, 0), 1e-9);
        assertTrue(Friendliness.tameChance(3, 3) > 0.8 && Friendliness.tameChance(3, 3) < 0.81);

        RandomSource always = RandomSource.create(1) ;
        // vanilla success stays success, rank 0 never re-rolls
        assertEquals(0, Friendliness.tameRoll(0, 3, 5, always));
        assertEquals(2, Friendliness.tameRoll(2, 3, 0, always));
        // with many ranks a miss almost surely turns into a hit
        int hits = 0;
        RandomSource rng = RandomSource.create(42);
        for (int i = 0; i < 1000; i++) if (Friendliness.tameRoll(1, 3, 10, rng) == 0) hits++;
        assertTrue(hits > 970, "rank 10 should rescue nearly every miss, got " + hits);
    }

    @Test
    @DisplayName("bigger backpacks: p = 0.25 per rank, whole part guaranteed, fraction rolled")
    void biggerBackpacks() {
        assertEquals(0, BiggerBackpacks.extraCopies(0, 0.0));
        assertEquals(1, BiggerBackpacks.extraCopies(1, 0.10));
        assertEquals(0, BiggerBackpacks.extraCopies(1, 0.30));
        assertEquals(1, BiggerBackpacks.extraCopies(4, 0.99), "rank 4 always doubles");
        assertEquals(2, BiggerBackpacks.extraCopies(8, 0.99), "rank 8 always triples");
        assertEquals(2, BiggerBackpacks.extraCopies(6, 0.40));
        assertEquals(1, BiggerBackpacks.extraCopies(6, 0.60));
    }

    @Test
    @DisplayName("craft level stats: ceiling starts at half the chest tier, closes 10% per rank, never reached")
    void craftLevelStats() {
        assertEquals(0.0, CraftLevelStats.ceiling(0));
        assertEquals(0.5, CraftLevelStats.ceiling(1), 1e-9);
        assertEquals(0.55, CraftLevelStats.ceiling(2), 1e-9);
        assertTrue(CraftLevelStats.ceiling(10) > 0.80 && CraftLevelStats.ceiling(10) < 0.81);
        assertTrue(CraftLevelStats.ceiling(100) < 1.0);
        assertEquals(0, CraftLevelStats.rolledTier(0, 1.0, 5));
        assertEquals(10, CraftLevelStats.rolledTier(20, 1.0, 1));
        assertEquals(5, CraftLevelStats.rolledTier(20, 0.5, 1));
        for (int rank = 1; rank <= 500; rank *= 2) {
            assertTrue(CraftLevelStats.rolledTier(40, 1.0, rank) < 40, "never the chest tier, rank " + rank);
        }
        assertTrue(CraftLevelStats.rolledTier(40, 1.0, 20) > CraftLevelStats.rolledTier(40, 1.0, 5));

        // more rank, higher expected quality
        double q0 = 0, q5 = 0;
        RandomSource a = RandomSource.create(7), b = RandomSource.create(7);
        for (int i = 0; i < 2000; i++) { q0 += CraftLevelStats.qualityRoll(0, a); q5 += CraftLevelStats.qualityRoll(5, b); }
        assertTrue(q0 / 2000 < 0.55 && q0 / 2000 > 0.45, "rank 0 mean ~0.5");
        assertTrue(q5 / 2000 > 0.8, "rank 5 mean ~6/7");
    }

    @Test
    @DisplayName("drift chance: each rank closes 2% of the gap to certainty, never reaches it")
    void driftChance() {
        assertEquals(0.10, DriftChance.boosted(0.10, 0), 1e-9);
        assertEquals(0.118, DriftChance.boosted(0.10, 1), 1e-9);
        assertTrue(DriftChance.boosted(0.10, 10) > 0.26 && DriftChance.boosted(0.10, 10) < 0.27);
        assertTrue(DriftChance.boosted(0.10, 1000) < 1.0);
        assertEquals(0.0, DriftChance.boosted(0.0, 10));
        assertEquals(1.0, DriftChance.boosted(1.0, 10));
    }

    @Test
    @DisplayName("trade value formatting")
    void tradeValueFormat() {
        assertEquals("nothing", TradeValueTooltip.formatSixteenths(0));
        assertEquals("5/16 of an emerald", TradeValueTooltip.formatSixteenths(5));
        assertEquals("1 emerald", TradeValueTooltip.formatSixteenths(16));
        assertEquals("2 emeralds 3/16", TradeValueTooltip.formatSixteenths(35));
    }

    @Test
    @DisplayName("trade value ceiling: 1 emerald at rank 1, steps grow by 0.8, ~5 emerald blocks at rank 10")
    void tradeValueCeiling() {
        assertEquals(0.0, TradeValueTooltip.ceilingEmeralds(0));
        assertEquals(1.0, TradeValueTooltip.ceilingEmeralds(1), 1e-9);
        assertEquals(2.8, TradeValueTooltip.ceilingEmeralds(2), 1e-9);
        assertEquals(46.0, TradeValueTooltip.ceilingEmeralds(10), 1e-9);
        assertTrue(TradeValueTooltip.visibleAt(16, 1));
        assertFalse(TradeValueTooltip.visibleAt(17, 1));
        assertFalse(TradeValueTooltip.visibleAt(16, 0));
    }
}
