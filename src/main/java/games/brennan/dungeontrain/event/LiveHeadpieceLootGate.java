package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.item.LiveHeadpiecePendingTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * The last chance to resolve a loot-rolled Live Eye: one that reached a player's inventory still
 * {@link LiveHeadpiecePendingTag pending} — taken from a hopper, a shattered vase, a shared carriage, or
 * any other route that skipped {@link LiveEyeContainerEvents}. On an equip, or the throttled inventory
 * sweep, each pending stack becomes whatever {@link LiveEyeLootResolver} gives this player: a Live Eye,
 * or a TV when they can't have one.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveHeadpieceLootGate {

    /** Sweep cadence for headpieces that arrived without passing through a hand. */
    private static final int SWEEP_INTERVAL_TICKS = 20;

    private LiveHeadpieceLootGate() {}

    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack stack = event.getTo();
        if (!LiveHeadpiecePendingTag.isPending(stack)) return;
        resolveEverywhere(player);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % SWEEP_INTERVAL_TICKS != 0) return;
        resolveEverywhere(player);
    }

    /** Walk the inventory once; pending headpieces are rare, and the tag check is the only cost. */
    private static void resolveEverywhere(ServerPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!LiveHeadpiecePendingTag.isPending(stack)) continue;
            inv.setItem(i, LiveEyeLootResolver.resolve(player));
        }
    }
}
