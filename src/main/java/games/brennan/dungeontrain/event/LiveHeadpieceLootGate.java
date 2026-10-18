package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.item.LiveHeadpiecePendingTag;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * The last chance to resolve a loot-rolled Live Eye: one that reached a player's inventory still
 * {@link LiveHeadpiecePendingTag pending} — taken from a hopper, a shattered vase, a shared carriage, or
 * any other route that skipped {@link LiveEyeContainerEvents} (straight into the Edible Backpack
 * included). On an equip, or the throttled inventory
 * sweep, each pending stack becomes whatever {@link LiveEyeLootResolver} gives this player: a Live Eye,
 * or a TV when they can't have one.
 *
 * <p>It also holds every player to one Live Eye, counting the ender chest, the backpack and anything
 * packed in a shulker box: a second one can't be picked up off the ground, and any that arrives another way (out
 * of a chest, say) becomes a TV. The worn one is always the one kept. Creative players are exempt.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveHeadpieceLootGate {

    /** Sweep cadence for headpieces that arrived without passing through a hand. */
    private static final int SWEEP_INTERVAL_TICKS = 20;
    /** Told when an extra Live Eye turns into a TV. */
    static final String KEY_ONE_EYE = "chat.dungeontrain.live.one_eye";
    /** {@link Inventory} slot of the worn helmet: armour starts at 36, head is the last of the four. */
    private static final int HEAD_SLOT = Inventory.INVENTORY_SIZE + 3;

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

    /** One at a time: someone already holding a Live Eye leaves a dropped one on the ground for others. */
    @SubscribeEvent
    public static void onPickup(ItemEntityPickupEvent.Pre event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || player.isCreative()) return;
        if (LiveEyeHoldings.count(event.getItemEntity().getItem()) == 0) return;
        if (LiveEyeLootResolver.holdsLiveEye(player)) event.setCanPickup(TriState.FALSE);
    }

    /** Walk the inventory once; pending headpieces are rare, and the tag check is the only cost. */
    private static void resolveEverywhere(ServerPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (LiveHeadpiecePendingTag.isUnclaimed(stack)) {
                ItemStack claimed = stack.copy();
                LiveHeadpiecePendingTag.clearUnclaimed(claimed);
                inv.setItem(i, claimed);
            }
            if (!LiveHeadpiecePendingTag.isPending(stack)) continue;
            inv.setItem(i, LiveEyeLootResolver.resolve(player));
        }
        ItemStackHandler backpack = LiveEyeLootResolver.backpack(player);
        for (int i = 0; i < backpack.getSlots(); i++) {
            ItemStack stack = backpack.getStackInSlot(i);
            if (LiveHeadpiecePendingTag.isUnclaimed(stack)) {
                ItemStack claimed = stack.copy();
                LiveHeadpiecePendingTag.clearUnclaimed(claimed);
                backpack.setStackInSlot(i, claimed);
            }
            if (!LiveHeadpiecePendingTag.isPending(stack)) continue;
            backpack.setStackInSlot(i, LiveEyeLootResolver.resolve(player));
        }
        enforceOne(player);
    }

    /** More than one Live Eye held: keep the worn one (else the first found), every other becomes a TV. */
    private static void enforceOne(ServerPlayer player) {
        if (player.isCreative()) return;
        int extras = LiveEyeLootResolver.extrasOver(LiveEyeLootResolver.heldLiveEyes(player));
        if (extras == 0) return;
        Inventory inv = player.getInventory();
        int[] allowance = {1};
        int converted = LiveEyeHoldings.cap(inv, HEAD_SLOT, allowance, LiveEyeLootResolver::tv);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i != HEAD_SLOT) converted += LiveEyeHoldings.cap(inv, i, allowance, LiveEyeLootResolver::tv);
        }
        Container ender = player.getEnderChestInventory();
        for (int i = 0; i < ender.getContainerSize(); i++) {
            converted += LiveEyeHoldings.cap(ender, i, allowance, LiveEyeLootResolver::tv);
        }
        ItemStackHandler backpack = LiveEyeLootResolver.backpack(player);
        for (int i = 0; i < backpack.getSlots(); i++) {
            converted += LiveEyeHoldings.cap(backpack, i, allowance, LiveEyeLootResolver::tv);
        }
        if (converted > 0) {
            player.sendSystemMessage(Component.translatable(KEY_ONE_EYE).withStyle(ChatFormatting.GRAY));
        }
    }
}
