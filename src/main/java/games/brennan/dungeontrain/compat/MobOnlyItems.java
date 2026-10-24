package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

/**
 * Keeps {@link DisabledModContent#MOB_ONLY_ITEMS} out of players' hands. Mobs hold them (DisabledModMobGear
 * leaves them alone), so a death is where one would escape: equipment drops and Piglin Proliferation's own
 * Buckler drop both arrive as {@link LivingDropsEvent} items, removed here after every other handler has
 * added its own. Loot tables, recipes and creative tabs go through {@link DisabledModContent#isDisabledItem};
 * the pickup refusal is the backstop for one that still lands in the world. Creative players are exempt.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class MobOnlyItems {

    private MobOnlyItems() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDrops(LivingDropsEvent event) {
        event.getDrops().removeIf(drop -> DisabledModContent.isMobOnlyItem(drop.getItem()));
    }

    @SubscribeEvent
    public static void onPickup(ItemEntityPickupEvent.Pre event) {
        if (event.getPlayer().isCreative()) return;
        if (DisabledModContent.isMobOnlyItem(event.getItemEntity().getItem())) {
            event.setCanPickup(TriState.FALSE);
        }
    }
}
