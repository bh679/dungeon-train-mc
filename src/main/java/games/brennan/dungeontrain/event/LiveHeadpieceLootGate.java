package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.item.LiveHeadpiecePendingTag;
import games.brennan.dungeontrain.player.LiveStreamers;
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

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The last chance to resolve a loot-rolled Live Eye: one that reached a player's inventory still
 * {@link LiveHeadpiecePendingTag pending} — taken from a hopper, a shattered vase, a shared carriage, or
 * any other route that skipped {@link LiveEyeContainerEvents} (straight into the Edible Backpack
 * included). On an equip, or the throttled inventory
 * sweep, each pending stack becomes whatever {@link LiveEyeLootResolver} gives this player: a Live Eye,
 * or a TV when they can't have one.
 *
 * <p>It also holds every player to one Live Eye, counting the ender chest, the backpack and anything
 * packed in a shulker box: picking a second one up off the ground swaps it for the one they had (that one
 * becomes a TV), unless they are wearing theirs or are live, when it stays on the ground. Any that arrives
 * another way (out of a chest, say) becomes a TV, and the worn one is the one kept. Creative players are exempt.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveHeadpieceLootGate {

    /** Sweep cadence for headpieces that arrived without passing through a hand. */
    private static final int SWEEP_INTERVAL_TICKS = 20;
    /** Told when an extra Live Eye turns into a TV. */
    static final String KEY_ONE_EYE = "chat.dungeontrain.live.one_eye";
    /** Told when picking up a Live Eye turns the one they had into a TV. */
    static final String KEY_EYE_SWAPPED = "chat.dungeontrain.live.eye_swapped";
    /** {@link Inventory} slot of the worn helmet: armour starts at 36, head is the last of the four. */
    private static final int HEAD_SLOT = Inventory.INVENTORY_SIZE + 3;
    /** Where an allowed swap pickup is about to land, from Pre to Post (server thread only). */
    private static final Map<ServerPlayer, Integer> SWAP_SLOTS = new WeakHashMap<>();

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

    /**
     * A dropped Live Eye swaps for the one a player holds: the pickup goes ahead and {@link #onPickedUp}
     * turns the old one into a TV. Not while they wear it or are live: then it stays on the ground.
     * Pre fires every tick the player touches the item, so nothing changes here; only the slot the eye
     * will land in is remembered ({@link Inventory#getFreeSlot}, where vanilla puts an unstackable item).
     */
    @SubscribeEvent
    public static void onPickup(ItemEntityPickupEvent.Pre event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || player.isCreative()) return;
        if (LiveEyeHoldings.count(event.getItemEntity().getItem()) == 0) return;
        if (!LiveEyeLootResolver.holdsLiveEye(player)) return;
        Inventory inv = player.getInventory();
        if (LiveEyeHoldings.isRealEye(inv.getItem(HEAD_SLOT)) || LiveStreamers.isStreamer(player.server, player.getUUID())) {
            event.setCanPickup(TriState.FALSE);
            return;
        }
        SWAP_SLOTS.put(player, inv.getFreeSlot());
    }

    /** The swap itself, once the eye is really in: every Live Eye but the new one becomes a TV. */
    @SubscribeEvent
    public static void onPickedUp(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        Integer slot = SWAP_SLOTS.remove(player);
        if (slot == null || slot < 0 || LiveEyeHoldings.count(event.getOriginalStack()) == 0) return;
        Inventory inv = player.getInventory();
        // Landed somewhere else (another mod's routing): the sweep's enforceOne still keeps them at one.
        if (!LiveEyeHoldings.isRealEye(inv.getItem(slot))) return;
        int[] allowance = {0};
        int converted = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i != slot) converted += LiveEyeHoldings.cap(inv, i, allowance, LiveEyeLootResolver::tv);
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
            player.sendSystemMessage(Component.translatable(KEY_EYE_SWAPPED).withStyle(ChatFormatting.GRAY));
        }
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
