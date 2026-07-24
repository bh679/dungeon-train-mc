package games.brennan.dungeontrain.difficulty;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure math for {@link EnchantLevelCap#capFor(ResourceLocation, int)}: hardcoded vanilla base +
 * tier/10 bonus, with a fallback for unknown ids. No registry / ItemStack needed.
 */
class EnchantLevelCapTest {

    private static ResourceLocation mc(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    @Test
    @DisplayName("Base cap = the enchant's vanilla max at tier 0 (no bonus)")
    void baseAtTierZero() {
        assertEquals(5, EnchantLevelCap.capFor(mc("sharpness"), 0));
        assertEquals(4, EnchantLevelCap.capFor(mc("protection"), 0));
        assertEquals(3, EnchantLevelCap.capFor(mc("fortune"), 0));
        assertEquals(3, EnchantLevelCap.capFor(mc("unbreaking"), 0));
        assertEquals(1, EnchantLevelCap.capFor(mc("mending"), 0));
    }

    @Test
    @DisplayName("Bonus is tier / 10 (integer), added to the vanilla base")
    void bonusRisesPerTenTiers() {
        assertEquals(5, EnchantLevelCap.capFor(mc("sharpness"), 9));   // still +0
        assertEquals(6, EnchantLevelCap.capFor(mc("sharpness"), 10));  // +1
        assertEquals(7, EnchantLevelCap.capFor(mc("sharpness"), 20));  // +2
        assertEquals(6, EnchantLevelCap.capFor(mc("fortune"), 30));    // 3 + 3
        assertEquals(9, EnchantLevelCap.capFor(mc("protection"), 50)); // 4 + 5
    }

    @Test
    @DisplayName("Unknown / modded enchant ids fall back to the flat base (5) + bonus")
    void unknownUsesFallback() {
        assertEquals(EnchantLevelCap.FALLBACK_MAX, EnchantLevelCap.capFor(mc("totally_made_up"), 0));
        assertEquals(EnchantLevelCap.FALLBACK_MAX + 2, EnchantLevelCap.capFor(mc("some_mod_enchant"), 20));
        assertEquals(5, EnchantLevelCap.capFor(ResourceLocation.parse("othermod:megabane"), 5));
    }

    @Test
    @DisplayName("Negative / zero tiers add no bonus")
    void nonPositiveTiers() {
        assertEquals(5, EnchantLevelCap.capFor(mc("sharpness"), 0));
        assertEquals(5, EnchantLevelCap.capFor(mc("sharpness"), -100));
    }
}
