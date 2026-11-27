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
 *
 * <p>Re-enchant rides the same seam the other way: a row for an already enchanted item first costs
 * {@link CrafterPerks#reenchantCost} (more levels, lapis unchanged), then Enchant Cost takes its
 * discount. The untouched vanilla price still goes to the client, struck through beside the real one.
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
        var player = menu.dungeontrain$player();
        int price = Reenchant.isReenchant(event.getItem())
            ? CrafterPerks.reenchantCost(original, CrafterRank.reenchant(player))
            : original;
        int shown = CrafterPerks.reducedCost(price, CrafterRank.enchantCost(player));
        power[row] = shown == original ? 0 : original;
        if (shown != original) event.setEnchantLevel(shown);
    }
}
