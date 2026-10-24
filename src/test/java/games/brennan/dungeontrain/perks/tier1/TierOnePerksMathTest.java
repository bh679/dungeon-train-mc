package games.brennan.dungeontrain.perks.tier1;

import games.brennan.dungeontrain.client.TradeValueTooltip;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure calculations behind the nine tier-1 perks, pinned so retuning is deliberate. */
final class TierOnePerksMathTest {

    @Test
    @DisplayName("attribute perks: +2 health, +1% speed, +0.5 damage per rank; never negative")
    void attributeAmounts() {
        assertEquals(0.0, AttributePerks.healthBonus(0));
        assertEquals(6.0, AttributePerks.healthBonus(3));
        assertEquals(0.0, AttributePerks.healthBonus(-2));
        assertEquals(0.10, AttributePerks.speedMultiplierBonus(10), 1e-9);
        assertEquals(2.0, AttributePerks.damageBonus(4));
    }

    @Test
    @DisplayName("book and quill stack: 1 + rank, capped at 16")
    void bookStack() {
        assertEquals(1, BookQuillStacking.stackSizeFor(0));
        assertEquals(4, BookQuillStacking.stackSizeFor(3));
        assertEquals(16, BookQuillStacking.stackSizeFor(40));
    }

    @Test
    @DisplayName("friendliness: feeling +0.1 per rank; tame gets rank extra rolls")
    void friendliness() {
        assertEquals(0.5f, Friendliness.feelingBonus(5), 1e-6);
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
    @DisplayName("craft level stats: rolled tier tracks quality, material gap halves it per step")
    void craftLevelStats() {
        assertEquals(0, CraftLevelStats.rolledTier(0, 1.0, 0));
        assertEquals(20, CraftLevelStats.rolledTier(20, 1.0, 0));
        assertEquals(10, CraftLevelStats.rolledTier(20, 1.0, 1));
        assertEquals(5, CraftLevelStats.rolledTier(20, 1.0, 2));
        assertEquals(10, CraftLevelStats.rolledTier(20, 0.5, 0));
        assertEquals(5, CraftLevelStats.materialStage("netherite_sword"));
        assertEquals(4, CraftLevelStats.materialStage("diamond_chestplate"));
        assertEquals(3, CraftLevelStats.materialStage("iron_axe"));
        assertEquals(3, CraftLevelStats.materialStage("chainmail_helmet"));
        assertEquals(2, CraftLevelStats.materialStage("stone_pickaxe"));
        assertEquals(1, CraftLevelStats.materialStage("wooden_sword"));
        assertEquals(1, CraftLevelStats.materialStage("golden_boots"));
        assertEquals(3, CraftLevelStats.materialStage("somemod_blade"));

        // more rank, higher expected quality
        double q0 = 0, q5 = 0;
        RandomSource a = RandomSource.create(7), b = RandomSource.create(7);
        for (int i = 0; i < 2000; i++) { q0 += CraftLevelStats.qualityRoll(0, a); q5 += CraftLevelStats.qualityRoll(5, b); }
        assertTrue(q0 / 2000 < 0.55 && q0 / 2000 > 0.45, "rank 0 mean ~0.5");
        assertTrue(q5 / 2000 > 0.8, "rank 5 mean ~6/7");
    }

    @Test
    @DisplayName("drift chance: x(1 + 0.15 rank), capped at 1")
    void driftChance() {
        assertEquals(1.0, DriftChance.multiplier(0));
        assertEquals(2.5, DriftChance.multiplier(10), 1e-9);
        assertEquals(0.25, DriftChance.boosted(0.10, 2.5), 1e-9);
        assertEquals(1.0, DriftChance.boosted(0.5, 2.5));
        assertEquals(0.0, DriftChance.boosted(0.0, 2.5));
        assertEquals(0.3, DriftChance.boosted(0.3, 0.5), 1e-9, "a multiplier below 1 never lowers the chance");
    }

    @Test
    @DisplayName("trade value formatting")
    void tradeValueFormat() {
        assertEquals("nothing", TradeValueTooltip.formatSixteenths(0));
        assertEquals("5/16 of an emerald", TradeValueTooltip.formatSixteenths(5));
        assertEquals("1 emerald", TradeValueTooltip.formatSixteenths(16));
        assertEquals("2 emeralds 3/16", TradeValueTooltip.formatSixteenths(35));
    }
}
