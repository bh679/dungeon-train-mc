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

/**
 * Enchant Bonus: worn armour and held weapons act one enchantment level higher on everything they
 * already have. Every gameplay read of an item's enchantments goes through NeoForge's
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
        int bonus = CrafterPerks.bonusLevels(CrafterRank.enchantBonus(owner));
        if (bonus <= 0) return;
        ItemStack stack = event.getStack();
        if (!CrafterRank.isWornOrHeld(owner, stack)) return;
        raise(event.getEnchantments(), bonus);
    }

    /** Add {@code bonus} to every enchantment present. In place on the event's mutable view. */
    static void raise(ItemEnchantments.Mutable levels, int bonus) {
        List<Holder<Enchantment>> keys = new ArrayList<>(levels.keySet());
        for (Holder<Enchantment> key : keys) {
            levels.set(key, levels.getLevel(key) + bonus);
        }
    }
}
