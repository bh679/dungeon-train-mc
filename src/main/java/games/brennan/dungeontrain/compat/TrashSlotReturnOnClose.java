package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Hands back whatever is still sitting in the bundled <b>TrashSlot</b>'s slot when the player closes a
 * screen — into the inventory, or dropped at their feet when it is full.
 *
 * <p>TrashSlot does not delete an item dropped on its slot: it parks it there, still retrievable,
 * until another item replaces it or a delete key is pressed (and DT ships those keys unbound —
 * {@link games.brennan.dungeontrain.client.TrashSlotKeybindDefault}). Left alone, the parked item
 * silently vanishes on the next login or respawn, when TrashSlot resets the slot. Returning it on
 * close makes the slot an explicit "replace to delete" bin instead of a hidden one.</p>
 *
 * <p>Server-side, on {@link PlayerContainerEvent.Close} (every menu, including the survival
 * inventory) and on logout, which fires before the player is saved. TrashSlot is reached by class
 * <i>name</i> — DT has no compile-time or {@code neoforge.mods.toml} dependency on it — through
 * {@code TrashHelper.getTrashItem/setTrashItem} (its Balm persistent-data store) and its
 * {@code MessageTrashSlotContent} payload, which re-syncs the client's copy of the slot. Without
 * TrashSlot, or with a build whose seams have moved, it does nothing and the item stays where
 * TrashSlot put it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TrashSlotReturnOnClose {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String HELPER_CLASS = "net.blay09.mods.trashslot.TrashHelper";
    static final String CONTENT_MESSAGE_CLASS = "net.blay09.mods.trashslot.network.MessageTrashSlotContent";

    /** TrashSlot's seams, linked together so a partial match never runs. */
    record Hooks(Method getTrashItem, Method setTrashItem, Constructor<?> contentMessage) {}

    /** Resolved once; empty when TrashSlot is absent or its seams no longer link. */
    private static volatile Optional<Hooks> hooks;
    private static volatile boolean warned;

    private TrashSlotReturnOnClose() {}

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            returnTrashItem(player, true);
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            returnTrashItem(player, false);
        }
    }

    /**
     * Move the parked trash item back into {@code player}'s inventory (or drop it when full). The slot
     * is cleared first, so a failure part-way can lose nothing but can never duplicate.
     */
    static void returnTrashItem(ServerPlayer player, boolean syncClient) {
        if (player.isSpectator() || player.isRemoved() || !player.isAlive()) return;
        Optional<Hooks> found = resolveHooks();
        if (found.isEmpty()) return;
        Hooks h = found.get();
        ItemStack parked;
        try {
            parked = (ItemStack) h.getTrashItem().invoke(null, player);
            if (parked == null || parked.isEmpty()) return;
            h.setTrashItem().invoke(null, player, ItemStack.EMPTY);
        } catch (Throwable t) {
            warnOnce("could not read or clear TrashSlot's slot", t);
            return;
        }
        player.getInventory().placeItemBackInInventory(parked);
        LOGGER.debug("[DungeonTrain] returned {} x{} from the trash slot to {}",
                parked.getItem(), parked.getCount(), player.getGameProfile().getName());
        if (syncClient) {
            syncEmptySlot(player, h);
        }
    }

    private static void syncEmptySlot(ServerPlayer player, Hooks h) {
        try {
            PacketDistributor.sendToPlayer(player, (CustomPacketPayload) h.contentMessage().newInstance(ItemStack.EMPTY));
        } catch (Throwable t) {
            // The item is already back; the client just shows it in the slot until the next menu open re-syncs.
            warnOnce("could not re-sync TrashSlot's slot to the client", t);
        }
    }

    static Optional<Hooks> resolveHooks() {
        Optional<Hooks> cached = hooks;
        if (cached != null) return cached;
        Optional<Hooks> resolved;
        try {
            ClassLoader loader = TrashSlotReturnOnClose.class.getClassLoader();
            resolved = link(Class.forName(HELPER_CLASS, false, loader), Class.forName(CONTENT_MESSAGE_CLASS, false, loader));
        } catch (ClassNotFoundException absent) {
            resolved = Optional.empty();
        } catch (Throwable t) {
            warnOnce("could not link TrashSlot", t);
            resolved = Optional.empty();
        }
        hooks = resolved;
        return resolved;
    }

    /** Pure: TrashSlot's static get/set pair on {@code helper} plus the payload's {@code (ItemStack)} constructor. */
    static Optional<Hooks> link(Class<?> helper, Class<?> contentMessage) {
        if (!CustomPacketPayload.class.isAssignableFrom(contentMessage)) return Optional.empty();
        try {
            Method get = helper.getMethod("getTrashItem", Player.class);
            Method set = helper.getMethod("setTrashItem", Player.class, ItemStack.class);
            if (get.getReturnType() != ItemStack.class) return Optional.empty();
            return Optional.of(new Hooks(get, set, contentMessage.getConstructor(ItemStack.class)));
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        }
    }

    private static void warnOnce(String what, Throwable t) {
        if (warned) return;
        warned = true;
        LOGGER.warn("[DungeonTrain] {} — trashed items stay in the slot on close: {}", what, t.toString());
    }
}
