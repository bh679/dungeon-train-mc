package games.brennan.dungeontrain.perks.effects.crafter;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Enchant Bonus reach: only the very stack in an armour or hand slot qualifies. */
class CrafterRankGearTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Map<EquipmentSlot, ItemStack> gear(EquipmentSlot slot, ItemStack stack) {
        Map<EquipmentSlot, ItemStack> map = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot s : EquipmentSlot.values()) map.put(s, ItemStack.EMPTY);
        map.put(slot, stack);
        return map;
    }

    @Test
    void wornAndHeldStacksQualify() {
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
            assertTrue(CrafterRank.isInGearSlot(stack, gear(slot, stack)::get), slot.getName());
        }
    }

    @Test
    void equalCopyDoesNotQualify() {
        ItemStack worn = new ItemStack(Items.DIAMOND_CHESTPLATE);
        assertFalse(CrafterRank.isInGearSlot(worn.copy(), gear(EquipmentSlot.CHEST, worn)::get));
    }

    @Test
    void inventoryOnlyStackDoesNotQualify() {
        ItemStack held = new ItemStack(Items.DIAMOND_SWORD);
        ItemStack inInventory = new ItemStack(Items.DIAMOND_PICKAXE);
        assertFalse(CrafterRank.isInGearSlot(inInventory, gear(EquipmentSlot.MAINHAND, held)::get));
    }

    @Test
    void bodySlotDoesNotQualify() {
        ItemStack horseArmor = new ItemStack(Items.DIAMOND_HORSE_ARMOR);
        assertFalse(CrafterRank.isInGearSlot(horseArmor, gear(EquipmentSlot.BODY, horseArmor)::get));
    }

    @Test
    void emptyStackNeverQualifies() {
        assertFalse(CrafterRank.isInGearSlot(ItemStack.EMPTY, gear(EquipmentSlot.MAINHAND, ItemStack.EMPTY)::get));
        assertFalse(CrafterRank.isInGearSlot(null, gear(EquipmentSlot.MAINHAND, ItemStack.EMPTY)::get));
    }
}
