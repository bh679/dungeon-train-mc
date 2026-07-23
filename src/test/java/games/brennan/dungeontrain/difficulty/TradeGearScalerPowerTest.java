package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic coverage of {@link TradeGearScaler#enchantPower}: the step curve
 * (+1 step per N tiers → 15, 25, 35…), the 60 cap, and the guard against a
 * zero tiers-per-step config. No NeoForge bootstrap.
 */
final class TradeGearScalerPowerTest {

    @Test
    @DisplayName("power steps +10 every tiersPerStep tiers: 15, 25, 35…")
    void powerSteps() {
        assertEquals(15, TradeGearScaler.enchantPower(0, 5));
        assertEquals(15, TradeGearScaler.enchantPower(4, 5));
        assertEquals(25, TradeGearScaler.enchantPower(5, 5));
        assertEquals(25, TradeGearScaler.enchantPower(9, 5));
        assertEquals(35, TradeGearScaler.enchantPower(10, 5));
        assertEquals(45, TradeGearScaler.enchantPower(15, 5));
        assertEquals(55, TradeGearScaler.enchantPower(20, 5));
    }

    @Test
    @DisplayName("power caps at 60 no matter how deep the tier")
    void powerCaps() {
        assertEquals(60, TradeGearScaler.enchantPower(25, 5));
        assertEquals(60, TradeGearScaler.enchantPower(1000, 5));
    }

    @Test
    @DisplayName("tiersPerStep below 1 is clamped, not a division by zero")
    void tiersPerStepClamped() {
        assertEquals(TradeGearScaler.enchantPower(7, 1), TradeGearScaler.enchantPower(7, 0));
    }
}
