package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.item.LiveHeadpiecePendingTag;
import games.brennan.dungeontrain.narrative.RandomBookFactory;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Keeps the live headpiece out of a Kid's hands when their Livestreaming switch is off.
 *
 * <p>Loot is baked per carriage with no player in sight, so a rolled headpiece arrives stamped
 * {@link LiveHeadpiecePendingTag}. The first time it reaches a player (an equip, or the throttled
 * inventory sweep for every other way of acquiring it) the finder is checked against the content
 * mirror: a Kid with Livestreaming off ({@link ContentModeMirror#mayLootLiveHeadpiece}) finds the
 * book the slot would otherwise have held in its place; anyone else keeps the headpiece, now
 * unmarked. Nothing here touches loot tables — the headpiece is not in them yet.</p>
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
        boolean mayKeep = ContentModeMirror.mayLootLiveHeadpiece(player);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !stack.is(ModItems.LIVE_HEADPIECE.get())) continue;
            if (!LiveHeadpiecePendingTag.isPending(stack)) continue;
            if (mayKeep) {
                LiveHeadpiecePendingTag.clear(stack);
            } else {
                inv.setItem(i, replacement(player, i));
            }
        }
    }

    /** The book the loot slot would have held: a local random book, seeded so re-rolls agree. */
    private static ItemStack replacement(ServerPlayer player, int slot) {
        long seed = player.getUUID().getLeastSignificantBits() * 31L + slot;
        return RandomBookFactory.rollFromPool(seed).orElse(ItemStack.EMPTY);
    }
}
