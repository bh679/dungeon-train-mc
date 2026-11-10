package games.brennan.dungeontrain.difficulty;

import games.brennan.dungeontrain.block.VaultGreen;
import games.brennan.dungeontrain.difficulty.VaultStatBoost.Tier;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class VaultStatBoostTest {

    private static final double EPS = 1.0e-9;
    /** For stats read off vanilla items, which declare some as floats (a sword's speed is {@code -2.4F}). */
    private static final double ITEM_EPS = 1.0e-5;
    private static final double BASE_DAMAGE = 1.0;
    private static final double BASE_SPEED = 4.0;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** The total added amount of {@code attribute} on {@code stack}, as the tooltip would read its modifier. */
    private static double amount(ItemStack stack, Holder<Attribute> attribute) {
        ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        if (mods.modifiers().isEmpty()) mods = stack.getItem().getDefaultAttributeModifiers();
        double total = 0.0;
        for (ItemAttributeModifiers.Entry entry : mods.modifiers()) {
            if (entry.attribute().equals(attribute)) total += entry.modifier().amount();
        }
        return total;
    }

    @Test
    @DisplayName("a negative attack speed modifier gets faster, not slower")
    void negativeSpeedGetsFaster() {
        // Diamond sword: -2.4 on a base of 4 is 1.6 attacks a second; 1.25x is 2.0, so -2.0.
        assertEquals(-2.0, VaultStatBoost.boostedAmount(BASE_SPEED, -2.4, 1.25), EPS);
    }

    @Test
    @DisplayName("a diamond sword takes each tier's damage and speed")
    void swordAtEachTier() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        double[][] expected = {{8.4, 1.76}, {9.8, 1.92}, {8.75, 1.8}};
        Tier[] tiers = {Tier.REGULAR, Tier.OMINOUS, Tier.GREEN};
        for (int i = 0; i < tiers.length; i++) {
            ItemStack boosted = VaultStatBoost.boosted(sword, tiers[i]);
            assertEquals(expected[i][0], amount(boosted, Attributes.ATTACK_DAMAGE) + BASE_DAMAGE, ITEM_EPS, tiers[i].name());
            assertEquals(expected[i][1], amount(boosted, Attributes.ATTACK_SPEED) + BASE_SPEED, ITEM_EPS, tiers[i].name());
        }
    }

    @Test
    @DisplayName("a diamond chestplate takes each tier's armor and toughness")
    void chestplateAtEachTier() {
        ItemStack chest = new ItemStack(Items.DIAMOND_CHESTPLATE);
        double[][] expected = {{9.6, 2.2}, {11.2, 2.4}, {10.0, 2.25}};
        Tier[] tiers = {Tier.REGULAR, Tier.OMINOUS, Tier.GREEN};
        for (int i = 0; i < tiers.length; i++) {
            ItemStack boosted = VaultStatBoost.boosted(chest, tiers[i]);
            assertEquals(expected[i][0], amount(boosted, Attributes.ARMOR), ITEM_EPS, tiers[i].name());
            assertEquals(expected[i][1], amount(boosted, Attributes.ARMOR_TOUGHNESS), ITEM_EPS, tiers[i].name());
        }
    }

    @Test
    @DisplayName("the boost scales rolled stats already on the stack, and leaves the stack itself alone")
    void scalesRolledStatsWithoutMutating() {
        ItemStack rolled = new ItemStack(Items.DIAMOND_CHESTPLATE);
        ItemAttributeModifiers mods = ItemAttributeModifiers.builder()
            .add(Attributes.ARMOR, addValue("armor", 10.0), EquipmentSlotGroup.CHEST)
            .build();
        rolled.set(DataComponents.ATTRIBUTE_MODIFIERS, mods);

        ItemStack boosted = VaultStatBoost.boosted(rolled, Tier.OMINOUS);

        assertEquals(14.0, amount(boosted, Attributes.ARMOR), EPS);
        assertEquals(10.0, amount(rolled, Attributes.ARMOR), EPS);
    }

    @Test
    @DisplayName("other attributes and non-added modifiers are not boosted")
    void onlyAddedCombatStats() {
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        ItemAttributeModifiers mods = ItemAttributeModifiers.builder()
            .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(id("percent"), 0.5,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE), EquipmentSlotGroup.MAINHAND)
            .add(Attributes.KNOCKBACK_RESISTANCE, addValue("kb", 0.1), EquipmentSlotGroup.MAINHAND)
            .build();
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, mods);

        assertSame(stack, VaultStatBoost.boosted(stack, Tier.OMINOUS));
    }

    @Test
    @DisplayName("items with no stats pass through as the same stack")
    void nonGearUntouched() {
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 3);
        List<ItemStack> out = VaultStatBoost.boosted(List.of(diamonds), Tier.OMINOUS);
        assertEquals(1, out.size());
        assertSame(diamonds, out.get(0));
    }

    @Test
    @DisplayName("a vault's block state picks its tier")
    void tierFollowsState() {
        BlockState vault = Blocks.VAULT.defaultBlockState();
        assertEquals(Tier.REGULAR, VaultStatBoost.tierOf(vault));
        assertEquals(Tier.OMINOUS, VaultStatBoost.tierOf(vault.setValue(VaultBlock.OMINOUS, true)));
        assertEquals(Tier.GREEN, VaultStatBoost.tierOf(VaultGreen.withGreen(vault, true)));
    }

    private static AttributeModifier addValue(String path, double amount) {
        return new AttributeModifier(id(path), amount, AttributeModifier.Operation.ADD_VALUE);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("dungeontrain", "test_" + path);
    }
}
