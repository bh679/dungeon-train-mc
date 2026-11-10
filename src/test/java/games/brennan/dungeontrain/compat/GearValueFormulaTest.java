package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.compat.GearValueFormula.Reference;
import games.brennan.dungeontrain.compat.GearValueFormula.Slot;
import games.brennan.dungeontrain.compat.GearValueFormula.Stats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GearValueFormula} against the figures on the comparison page it was tuned on, using
 * vanilla 1.21.1 stats. Iron prices are Trade Everything's recipe values today.
 */
final class GearValueFormulaTest {

    private static final double TOLERANCE = 0.15;

    /** Iron sword: 2 iron ingots (8/16 each) + a stick (0.125/16). */
    private static final double IRON_SWORD_EMERALDS = 16.125 / 16;
    private static final double IRON_PICKAXE_EMERALDS = 24.25 / 16;
    private static final double IRON_CHESTPLATE_EMERALDS = 64.0 / 16;

    private static Stats sword(String material, double damage, double durability, boolean fire) {
        return new Stats(Slot.SWORD, material, damage, 1.6, 0, 0, 0, 0, 0, durability, durability, fire);
    }

    private static Stats pickaxe(String material, double damage, double speed, int tier, double durability, boolean fire) {
        return new Stats(Slot.PICKAXE, material, damage, 1.2, speed, tier, 0, 0, 0, durability, durability, fire);
    }

    private static Stats chestplate(String material, double armor, double toughness, double kb, double durability, boolean fire) {
        return new Stats(Slot.CHESTPLATE, material, 0, 0, 0, 0, armor, toughness, kb, durability, durability, fire);
    }

    private static final Stats IRON_SWORD = sword("iron", 6, 250, false);
    private static final Stats DIAMOND_SWORD = sword("diamond", 7, 1561, false);
    private static final Stats NETHERITE_SWORD = sword("netherite", 8, 2031, true);
    private static final Reference SWORDS = new Reference(IRON_SWORD, DIAMOND_SWORD, IRON_SWORD_EMERALDS);

    private static final Stats IRON_PICKAXE = pickaxe("iron", 4, 6, 2, 250, false);
    private static final Stats DIAMOND_PICKAXE = pickaxe("diamond", 5, 8, 3, 1561, false);
    private static final Stats NETHERITE_PICKAXE = pickaxe("netherite", 6, 9, 3, 2031, true);
    private static final Reference PICKAXES = new Reference(IRON_PICKAXE, DIAMOND_PICKAXE, IRON_PICKAXE_EMERALDS);

    private static final Stats IRON_CHESTPLATE = chestplate("iron", 6, 0, 0, 240, false);
    private static final Stats DIAMOND_CHESTPLATE = chestplate("diamond", 8, 2, 0, 528, false);
    private static final Stats NETHERITE_CHESTPLATE = chestplate("netherite", 8, 3, 0.1, 592, true);
    private static final Reference CHESTPLATES = new Reference(IRON_CHESTPLATE, DIAMOND_CHESTPLATE, IRON_CHESTPLATE_EMERALDS);

    @Test
    @DisplayName("iron keeps today's price; diamond and netherite are priced by their stats")
    void materialLadder() {
        assertEquals(IRON_SWORD_EMERALDS, GearValueFormula.statEmeralds(IRON_SWORD, SWORDS), 1e-9);
        assertEquals(5.4, GearValueFormula.statEmeralds(DIAMOND_SWORD, SWORDS), TOLERANCE);
        assertEquals(11.7, GearValueFormula.statEmeralds(NETHERITE_SWORD, SWORDS), TOLERANCE);
        assertEquals(4.0, GearValueFormula.statEmeralds(IRON_CHESTPLATE, CHESTPLATES), 1e-9);
        assertEquals(13.7, GearValueFormula.statEmeralds(DIAMOND_CHESTPLATE, CHESTPLATES), TOLERANCE);
    }

    @Test
    @DisplayName("netherite sells for about 2x diamond, not 13x")
    void netheriteNoLongerDominates() {
        double ratio = GearValueFormula.statEmeralds(NETHERITE_SWORD, SWORDS)
            / GearValueFormula.statEmeralds(DIAMOND_SWORD, SWORDS);
        assertTrue(ratio > 1.5 && ratio < 3, "ratio " + ratio);
    }

    @Test
    @DisplayName("better stat rolls sell for more, and past diamond the curve grows linearly")
    void statsMatter() {
        double plain = GearValueFormula.statEmeralds(DIAMOND_SWORD, SWORDS);
        double rolled = GearValueFormula.statEmeralds(sword("diamond", 8.4, 1561, false), SWORDS);
        double deep = GearValueFormula.statEmeralds(sword("diamond", 28, 1561, false), SWORDS);
        assertEquals(10.8, rolled, TOLERANCE);
        assertEquals(87.1, deep, TOLERANCE);
        assertTrue(rolled > 1.9 * plain);
    }

    @Test
    @DisplayName("wear lowers the price by the square root of durability left")
    void wear() {
        Stats half = new Stats(Slot.SWORD, "diamond", 7, 1.6, 0, 0, 0, 0, 0, 1561 / 2.0, 1561, false);
        assertEquals(Math.sqrt(0.5), GearValueFormula.statEmeralds(half, SWORDS)
            / GearValueFormula.statEmeralds(DIAMOND_SWORD, SWORDS), 1e-9);
    }

    @Test
    @DisplayName("stat enchantments are priced as the stat they add")
    void statEnchantments() {
        double plain = GearValueFormula.emeralds(DIAMOND_SWORD, SWORDS, Map.of());
        assertEquals(11.7, GearValueFormula.emeralds(DIAMOND_SWORD, SWORDS, Map.of("sharpness", 5)) - plain, TOLERANCE);
        assertEquals(5.4, GearValueFormula.emeralds(DIAMOND_SWORD, SWORDS, Map.of("unbreaking", 3)) - plain, TOLERANCE);
    }

    @Test
    @DisplayName("set-price enchantments scale with the material")
    void flatEnchantments() {
        double iron = GearValueFormula.emeralds(IRON_PICKAXE, PICKAXES, Map.of("silk_touch", 1))
            - GearValueFormula.emeralds(IRON_PICKAXE, PICKAXES, Map.of());
        double netherite = GearValueFormula.emeralds(NETHERITE_PICKAXE, PICKAXES, Map.of("silk_touch", 1))
            - GearValueFormula.emeralds(NETHERITE_PICKAXE, PICKAXES, Map.of());
        assertEquals(24, iron, 1e-9);
        assertEquals(72, netherite, 1e-9);
        double knockback = GearValueFormula.emeralds(DIAMOND_SWORD, SWORDS, Map.of("knockback", 2))
            - GearValueFormula.emeralds(DIAMOND_SWORD, SWORDS, Map.of());
        assertEquals(5, knockback, 1e-9);
    }

    @Test
    @DisplayName("Mending is 10x the durability worth, counting stat enchantments")
    void mending() {
        double plain = GearValueFormula.emeralds(DIAMOND_SWORD, SWORDS, Map.of());
        double mending = GearValueFormula.emeralds(DIAMOND_SWORD, SWORDS, Map.of("mending", 1)) - plain;
        assertEquals(53.1, mending, 0.3);
        double sharp = GearValueFormula.emeralds(DIAMOND_SWORD, SWORDS, Map.of("sharpness", 5));
        double sharpMending = GearValueFormula.emeralds(DIAMOND_SWORD, SWORDS, Map.of("sharpness", 5, "mending", 1)) - sharp;
        assertEquals(166.7, sharpMending, 0.5);
    }

    @Test
    @DisplayName("curses take a share off; unknown enchantments add nothing")
    void cursesAndUnknown() {
        double plain = GearValueFormula.emeralds(DIAMOND_SWORD, SWORDS, Map.of());
        assertEquals(plain * 0.85, GearValueFormula.emeralds(DIAMOND_SWORD, SWORDS, Map.of("vanishing_curse", 1)), 1e-9);
        assertEquals(plain, GearValueFormula.emeralds(DIAMOND_SWORD, SWORDS, Map.of("somemod_lifesteal", 3)), 1e-9);
    }

    @Test
    @DisplayName("absurd levels stay finite")
    void absurdLevels() {
        double v = GearValueFormula.emeralds(DIAMOND_SWORD, SWORDS, Map.of("sharpness", 255, "fire_aspect", 255));
        assertTrue(Double.isFinite(v) && v > 0);
    }

    @Test
    @DisplayName("a book is worth what its enchantment adds to the best netherite item")
    void books() {
        Map<Slot, Map.Entry<Stats, Reference>> netherite = Map.of(
            Slot.SWORD, new AbstractMap.SimpleEntry<>(NETHERITE_SWORD, SWORDS),
            Slot.PICKAXE, new AbstractMap.SimpleEntry<>(NETHERITE_PICKAXE, PICKAXES),
            Slot.CHESTPLATE, new AbstractMap.SimpleEntry<>(NETHERITE_CHESTPLATE, CHESTPLATES));
        double silk = GearValueFormula.bookEmeralds(Map.of("silk_touch", 1), netherite::get);
        assertEquals(72.5, silk, 1e-9);
        double mending = GearValueFormula.bookEmeralds(Map.of("mending", 1), netherite::get);
        assertTrue(mending > 150, "mending book " + mending);
    }
}
