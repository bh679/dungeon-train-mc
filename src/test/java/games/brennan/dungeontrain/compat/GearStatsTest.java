package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.compat.GearValueFormula.Slot;
import games.brennan.dungeontrain.compat.GearValueFormula.Stats;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GearStats#read} on real vanilla stacks reads the stats {@link GearValueFormulaTest} assumes,
 * and {@link GearResaleFloor#floorEmeralds} never lets a resale profit.
 */
final class GearStatsTest {

    private static final double EPS = 1e-6;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("a diamond sword reads as 7 damage, 1.6 speed, 1561 durability")
    void diamondSword() {
        Stats s = GearStats.read(new ItemStack(Items.DIAMOND_SWORD), Slot.SWORD);
        assertEquals(7, s.damage(), EPS);
        assertEquals(1.6, s.attackSpeed(), EPS);
        assertEquals(1561, s.maxDurability(), EPS);
        assertEquals(1561, s.durability(), EPS);
        assertEquals("diamond", s.material());
        assertFalse(s.fireImmune());
    }

    @Test
    @DisplayName("a netherite pickaxe reads mining speed 9, tier 3 and fire immunity")
    void netheritePickaxe() {
        Stats s = GearStats.read(new ItemStack(Items.NETHERITE_PICKAXE), Slot.PICKAXE);
        assertEquals(6, s.damage(), EPS);
        assertEquals(9, s.miningSpeed(), EPS);
        assertEquals(3, s.miningTier());
        assertTrue(s.fireImmune());
    }

    @Test
    @DisplayName("tiers follow the tool's incorrect-for tag")
    void miningTiers() {
        assertEquals(0, GearStats.read(new ItemStack(Items.GOLDEN_PICKAXE), Slot.PICKAXE).miningTier());
        assertEquals(1, GearStats.read(new ItemStack(Items.STONE_PICKAXE), Slot.PICKAXE).miningTier());
        assertEquals(2, GearStats.read(new ItemStack(Items.IRON_PICKAXE), Slot.PICKAXE).miningTier());
    }

    @Test
    @DisplayName("netherite armour reads toughness and knockback resistance")
    void netheriteChestplate() {
        Stats s = GearStats.read(new ItemStack(Items.NETHERITE_CHESTPLATE), Slot.CHESTPLATE);
        assertEquals(8, s.armor(), EPS);
        assertEquals(3, s.toughness(), EPS);
        assertEquals(0.1, s.knockbackResistance(), EPS);
    }

    @Test
    @DisplayName("wear reads as durability left")
    void worn() {
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.setDamageValue(100);
        assertEquals(150, GearStats.read(sword, Slot.SWORD).durability(), EPS);
    }

    @Test
    @DisplayName("the resale floor is value x payout margin, rounded up")
    void resaleFloor() {
        assertEquals(165, GearResaleFloor.floorEmeralds(219.5, 0.75));
        assertEquals(3, GearResaleFloor.floorEmeralds(4.0, 0.75));
        for (double value = 0.1; value < 1000; value *= 1.37) {
            assertTrue(GearResaleFloor.floorEmeralds(value, 0.75) >= value * 0.75 - EPS, "value " + value);
        }
    }
}
