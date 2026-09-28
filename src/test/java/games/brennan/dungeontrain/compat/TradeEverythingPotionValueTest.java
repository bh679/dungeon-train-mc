package games.brennan.dungeontrain.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TradeEverythingBridge#potionEmeralds} and the 22-per-emerald conversion —
 * potions pay out 1 emerald, more for stronger, longer or lingering brews.
 */
final class TradeEverythingPotionValueTest {

    /** TE's default payout margin ({@code result_multiplier}). */
    private static final double PAYOUT_MARGIN = 0.75;

    private static int payoutEmeralds(int emeralds) {
        int sixteenths = TradeEverythingBridge.emeraldsToSixteenths(emeralds);
        return (int) Math.floor(sixteenths * PAYOUT_MARGIN / 16.0);
    }

    @Test
    @DisplayName("a plain effect potion pays exactly 1 emerald")
    void basePotion() {
        assertEquals(1, TradeEverythingBridge.potionEmeralds(0, false, false));
        assertEquals(1, payoutEmeralds(1));
    }

    @Test
    @DisplayName("power tiers: amplifier, extended duration and lingering each add an emerald")
    void powerTiers() {
        assertEquals(2, TradeEverythingBridge.potionEmeralds(1, false, false)); // Strength II
        assertEquals(2, TradeEverythingBridge.potionEmeralds(0, true, false));  // Long Night Vision
        assertEquals(2, TradeEverythingBridge.potionEmeralds(0, false, true));  // Lingering Swiftness
        assertEquals(3, TradeEverythingBridge.potionEmeralds(0, true, true));   // Lingering Long Regen
        assertEquals(4, TradeEverythingBridge.potionEmeralds(3, false, false)); // Turtle Master
        assertEquals(6, TradeEverythingBridge.potionEmeralds(5, false, false)); // Strong Turtle Master
    }

    @Test
    @DisplayName("absurd command-given amplifiers are capped")
    void amplifierCapped() {
        int cap = TradeEverythingBridge.POTION_BASE_EMERALDS + TradeEverythingBridge.MAX_POTION_AMPLIFIER_BONUS;
        assertEquals(cap, TradeEverythingBridge.potionEmeralds(255, false, false));
        assertEquals(1, TradeEverythingBridge.potionEmeralds(-1, false, false));
    }

    @Test
    @DisplayName("every reachable tier pays out exactly its emerald count after the margin")
    void payoutFloorsToExactEmeralds() {
        int max = TradeEverythingBridge.potionEmeralds(Integer.MAX_VALUE, true, true);
        for (int n = 1; n <= max; n++) {
            assertEquals(n, payoutEmeralds(n), "tier " + n);
        }
    }
}
