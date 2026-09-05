package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for {@link MobExperienceScaling#scaledXp(int, double, int, int, double, double, double, double)}
 * — the experience multiplier math. The slot walk and effect-category filter in the
 * {@code LivingEntity} overload need a Minecraft bootstrap and are verified in-game; here we lock
 * down the curve, the cap, and the "never reduces a drop" guarantee (mirroring
 * {@link ItemStatLevelScalingTest}).
 */
final class MobExperienceScalingTest {

    /** The shipped defaults, so these tests fail if a weight changes without a deliberate update. */
    private static final double PER_STAT = 0.03;
    private static final double PER_ENCHANT = 0.05;
    private static final double PER_EFFECT = 0.20;
    private static final double MAX_MULT = 10.0;

    private static int scaled(int baseXp, double statPoints, int enchantLevels, int effectPoints) {
        return MobExperienceScaling.scaledXp(baseXp, statPoints, enchantLevels, effectPoints,
                PER_STAT, PER_ENCHANT, PER_EFFECT, MAX_MULT);
    }

    @Test
    @DisplayName("a bare, unbuffed mob drops exactly its vanilla experience")
    void noScoreIsVanilla() {
        assertEquals(5, scaled(5, 0, 0, 0));
        assertEquals(10, scaled(10, 0, 0, 0));
    }

    @Test
    @DisplayName("a vanilla full-iron zombie (15 stat points) drops x1.45 -> 7")
    void ironZombie() {
        assertEquals(7, scaled(5, 15, 0, 0)); // 5 * 1.45 = 7.25 -> 7
    }

    @Test
    @DisplayName("a fully-geared late-train mob stacks all three components")
    void gearedMobStacksComponents() {
        // 27 stat + 40 enchant levels + 8 effect points
        //   = 0.81 + 2.00 + 1.60 = 4.41 bonus -> x5.41 -> 5 * 5.41 = 27.05 -> 27
        assertEquals(27, scaled(5, 27, 40, 8));
    }

    @Test
    @DisplayName("each component contributes independently at its own weight")
    void componentsAreIndependent() {
        assertEquals(130, scaled(100, 10, 0, 0));  // 1 + 0.30
        assertEquals(150, scaled(100, 0, 10, 0));  // 1 + 0.50
        assertEquals(300, scaled(100, 0, 0, 10));  // 1 + 2.00
    }

    @Test
    @DisplayName("the multiplier is capped, however absurd the gear")
    void capClamps() {
        assertEquals(50, scaled(5, 100_000, 100_000, 100_000));  // 5 * 10.0
        assertEquals(5, MobExperienceScaling.scaledXp(5, 1000, 1000, 1000,
                PER_STAT, PER_ENCHANT, PER_EFFECT, 1.0));        // cap 1.0 = vanilla
    }

    @Test
    @DisplayName("a mob dropping no experience keeps dropping none")
    void zeroBaseStaysZero() {
        assertEquals(0, scaled(0, 50, 50, 50));
        assertEquals(-1, scaled(-1, 50, 50, 50));
    }

    @Test
    @DisplayName("scaling never reduces a drop below vanilla")
    void neverReduces() {
        assertEquals(5, scaled(5, -100, -100, -100));            // negative points ignored
        assertEquals(5, MobExperienceScaling.scaledXp(5, 10, 10, 10, 0, 0, 0, MAX_MULT));
        assertEquals(1, scaled(1, 1, 0, 0));                     // 1 * 1.03 = 1.03 -> 1, not 0
    }

    @Test
    @DisplayName("a zeroed weight disables just that component")
    void zeroedWeightDisablesOneComponent() {
        assertEquals(100, MobExperienceScaling.scaledXp(100, 50, 0, 0,
                0, PER_ENCHANT, PER_EFFECT, MAX_MULT));
        assertEquals(150, MobExperienceScaling.scaledXp(100, 50, 10, 0,
                0, PER_ENCHANT, PER_EFFECT, MAX_MULT));
    }

    @Test
    @DisplayName("a non-finite stat total falls back to vanilla rather than a garbage drop")
    void nonFiniteScoreIsIgnored() {
        // ItemPowerScore already discards absurd amounts, so this is belt-and-braces — but a NaN
        // or infinite multiplier would round to a nonsense int, so the pure core refuses it.
        assertEquals(5, scaled(5, Double.NaN, 0, 0));
        assertEquals(5, scaled(5, Double.POSITIVE_INFINITY, 0, 0));
    }
}
