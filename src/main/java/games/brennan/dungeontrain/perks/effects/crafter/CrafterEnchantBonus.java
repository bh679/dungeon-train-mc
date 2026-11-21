package games.brennan.dungeontrain.perks.effects.crafter;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.enchanting.GetEnchantmentLevelEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Enchant Bonus: worn armour and held weapons act higher on the enchantments they already have, each
 * by its group's bonus at the wearer's rank ({@link CrafterPerks#groupBonus}). Every gameplay read of an item's enchantments goes through NeoForge's
 * {@link GetEnchantmentLevelEvent}; the mixins in {@code mixin/} push the entity a read is for onto
 * {@link EnchantContext} (attacks, durability, equipment attributes, block drops, armour effects),
 * and this handler raises the levels when that entity is a player with the perk and the stack is
 * the very object in one of their armour or hand slots. The item itself is never written to.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CrafterEnchantBonus {

    private CrafterEnchantBonus() {}

    @SubscribeEvent
    public static void onGetLevels(GetEnchantmentLevelEvent event) {
        Player owner = EnchantContext.owner();
        if (owner == null) return;
        int rank = CrafterRank.enchantBonus(owner);
        if (rank <= 0) return;
        ItemStack stack = event.getStack();
        if (!CrafterRank.isWornOrHeld(owner, stack)) return;
        raise(event.getEnchantments(), key -> CrafterPerks.groupBonus(rank, EnchantGroups.indexOf(key)));
    }

    /** Add each enchantment's bonus to it. In place on the event's mutable view. */
    static void raise(ItemEnchantments.Mutable levels, ToIntFunction<Holder<Enchantment>> bonus) {
        List<Holder<Enchantment>> keys = new ArrayList<>(levels.keySet());
        for (Holder<Enchantment> key : keys) {
            int add = bonus.applyAsInt(key);
            if (add > 0) levels.set(key, levels.getLevel(key) + add);
        }
    }
}
