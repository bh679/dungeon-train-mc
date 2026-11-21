package games.brennan.dungeontrain.perks.effects.crafter;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.enchanting.EnchantmentLevelSetEvent;

/**
 * Enchant Cost on the enchanting table. Vanilla fills each row's {@code costs[k]} and then uses that
 * same number twice: as the level requirement the player must meet (and the GUI shows) and as the
 * power the roll is made with. This handler lowers the requirement by one level per rank (floor 1)
 * and remembers the untouched power on the menu, so the mixin can feed the roll the original number.
 * Roll quality is unchanged; only the price drops. Vanilla still deducts 1/2/3 levels per row.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CrafterEnchantCost {

    private CrafterEnchantCost() {}

    @SubscribeEvent
    public static void onLevelSet(EnchantmentLevelSetEvent event) {
        if (!(EnchantContext.enchantMenu() instanceof CrafterEnchantMenu menu)) return;
        int row = event.getEnchantRow();
        int[] power = menu.dungeontrain$rollPower();
        if (row < 0 || row >= power.length) return;
        int original = event.getEnchantLevel();
        int rank = CrafterRank.enchantCost(menu.dungeontrain$player());
        int reduced = CrafterPerks.reducedCost(original, rank);
        power[row] = reduced == original ? 0 : original;
        if (reduced != original) event.setEnchantLevel(reduced);
    }
}
