package games.brennan.dungeontrain.perks.effects.crafter;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent;

/**
 * Re-enchant: stamps the result of a table enchant that started on an already enchanted item. The
 * menu mixin marks the click (it sees the item before vanilla enchants it); this handler, on
 * NeoForge's after-enchant event, takes the mark and counts the re-enchant on the item.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ReenchantStamp {

    private ReenchantStamp() {}

    @SubscribeEvent
    public static void onEnchant(PlayerEnchantItemEvent event) {
        if (!(event.getEntity().containerMenu instanceof CrafterEnchantMenu menu)) return;
        if (menu.dungeontrain$takeReenchantClick()) Reenchant.stamp(event.getEnchantedItem());
    }
}
