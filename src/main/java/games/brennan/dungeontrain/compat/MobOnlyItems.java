package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

/**
 * Keeps {@link DisabledModContent#MOB_ONLY_ITEMS} out of players' hands. Mobs hold them (DisabledModMobGear
 * leaves them alone), so a death is where one would escape, by two routes. Equipment drops arrive as
 * {@link LivingDropsEvent} items, removed here after every other handler has added its own. Piglin
 * Proliferation's own Buckler drop does not: its LivingDropsEvent handler spawns the brute's offhand Buckler
 * straight into the level, so the dead mob's hands are emptied of mob-only items before it runs. Loot tables,
 * recipes and creative tabs go through {@link DisabledModContent#isDisabledItem}; the pickup refusal is the
 * backstop for one that still lands in the world. Creative players are exempt.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class MobOnlyItems {

    private MobOnlyItems() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDropsEmptyHands(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        for (InteractionHand hand : InteractionHand.values()) {
            if (DisabledModContent.isMobOnlyItem(entity.getItemInHand(hand))) {
                entity.setItemInHand(hand, ItemStack.EMPTY);
            }
        }
    }

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
