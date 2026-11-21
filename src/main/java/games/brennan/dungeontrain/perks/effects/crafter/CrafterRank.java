package games.brennan.dungeontrain.perks.effects.crafter;

import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The one place Crafter effect code reads a rank: {@link Perks#rank(Player, String)} behind
 * null-safe helpers, so every mixin asks the same question the same way.
 */
public final class CrafterRank {

    private CrafterRank() {}

    public static int of(Entity entity, String perkId) {
        return entity instanceof Player player ? Perks.rank(player, perkId) : 0;
    }

    public static int enchantCost(Entity e) { return of(e, CrafterPerks.ENCHANT_COST); }
    public static int cookingTime(Entity e) { return of(e, CrafterPerks.COOKING_TIME); }
    public static int reenchant(Entity e) { return of(e, CrafterPerks.RE_ENCHANT); }
    public static int enchantMax(Entity e) { return of(e, CrafterPerks.ENCHANT_MAX); }
    public static int enchantBonus(Entity e) { return of(e, CrafterPerks.ENCHANT_BONUS); }

    /**
     * Enchant Bonus reach: is {@code stack} the very object sitting in one of {@code player}'s armour
     * or hand slots? Identity, not equality, so a copy on a tooltip or in a chest never qualifies.
     */
    public static boolean isWornOrHeld(Player player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) return false;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot == EquipmentSlot.BODY) continue;
            if (player.getItemBySlot(slot) == stack) return true;
        }
        return false;
    }
}
