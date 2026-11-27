package games.brennan.dungeontrain.perks.effects.crafter;

import games.brennan.dungeontrain.registry.ModDataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Re-enchant's item rules on the enchanting table: which stacks count as a re-enchant, whether the
 * holder may still re-enchant one, the stack the roll is made with, and the per-item stamp.
 * The pure numbers are in {@link CrafterPerks}.
 */
public final class Reenchant {

    private Reenchant() {}

    /** Whether {@code stack} already carries enchantments (on itself, or stored in a book). */
    public static boolean isReenchant(ItemStack stack) {
        return stack != null && !stack.isEmpty() && EnchantmentHelper.hasAnyEnchantments(stack);
    }

    /** Times {@code stack} has been re-enchanted (0 when never). */
    public static int used(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        Integer n = stack.get(ModDataComponents.REENCHANTS.get());
        return n == null ? 0 : Math.max(0, n);
    }

    /**
     * Whether an already enchanted {@code stack} may go back on the table for {@code player}: its item
     * takes enchantments (an enchanted book counts, one at a time) and it has re-enchants left.
     */
    public static boolean canGoOnTable(Player player, ItemStack stack) {
        if (!isReenchant(stack)) return false;
        boolean enchantable = stack.is(Items.ENCHANTED_BOOK) ? stack.getCount() == 1 : stack.getItem().isEnchantable(stack);
        return enchantable && CrafterPerks.allowsReenchant(CrafterRank.reenchant(player), used(stack));
    }

    /**
     * The stack a table roll and price are made with: an enchanted book rolls as a plain book (vanilla
     * gives an enchanted book no enchantability and no candidates); anything else as itself.
     */
    public static ItemStack rollStack(ItemStack stack) {
        return stack != null && stack.is(Items.ENCHANTED_BOOK) ? new ItemStack(Items.BOOK) : stack;
    }

    /** Count one more re-enchant on {@code stack}. */
    public static void stamp(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        stack.set(ModDataComponents.REENCHANTS.get(), used(stack) + 1);
    }
}
