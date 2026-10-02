package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.StartingBookEvents;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.neoforge.api.event.ModifyFrameExtraDataEvent;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.entity.CameraOperator;
import io.github.mortuusars.exposure.world.item.PhotographItem;
import io.github.mortuusars.exposure.world.item.StackedPhotographsItem;
import io.github.mortuusars.exposure.world.level.storage.ExposureIdentifier;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Behaviour of the {@link DisposableCamera}: one shot, the viewfinder closes so the print can be
 * watched, the camera burns once the photo is out, and the photo burns after it has been viewed.
 *
 * <p>Nothing here burns on a drop — an unused camera or an unviewed photo can be dropped, stored or
 * lost on death like any item. The burns are started explicitly through
 * {@link StartingBookEvents#dropAndBurn}, never through the burn-on-drop predicate the books use.</p>
 *
 * <p>A shot runs: release → shutter opens and the frame lands on the camera stack → shutter closes and
 * the item cooldown starts (Polaroid's print animation is that cooldown) → cooldown ends and Polaroid
 * prints the photograph, taking the frame off the camera. This class watches the camera stack through
 * those states from the server player tick.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DisposableCameraEvents {

    /**
     * The viewfinder closes once this much of the post-shot cooldown is left — a short beat after the
     * shutter, so the client's capture (taken through the viewfinder) is done before the view changes.
     */
    static final float CLOSE_VIEWFINDER_AT_COOLDOWN = 0.75f;

    /** Cooldown restarted when the viewfinder closes, so the whole print animation plays in hand. */
    static final int PRINT_TICKS = 40;

    /**
     * Player → the photo their disposable camera is about to print. Only bridges the ticks between
     * the shot and the print; if it is lost (a relog mid-print) the photo just stays where Polaroid
     * put it.
     */
    private static final Map<UUID, ExposureIdentifier> PENDING_PRINTS = new ConcurrentHashMap<>();

    private DisposableCameraEvents() {}

    /** Every photo from a disposable camera is flagged to burn after viewing. Server side. */
    @SubscribeEvent
    public static void onFrameExtraData(ModifyFrameExtraDataEvent event) {
        if (DisposableCamera.is(event.getCamera())) {
            DisposableCamera.flag(event.getData());
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (DisposableCamera.is(stack)) {
                tickCamera(player, inventory, slot, stack);
            }
        }
    }

    private static void tickCamera(ServerPlayer player, Inventory inventory, int slot, ItemStack camera) {
        CameraItem item = (CameraItem) camera.getItem();
        Frame pending = camera.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        if (pending != null) {
            PENDING_PRINTS.put(player.getUUID(), pending.identifier());
            if (!DisposableCamera.isShot(camera)) {
                DisposableCamera.markShot(camera);
            }
            if (item.isActive(camera) && !item.getShutter().isOpen(camera)
                    && player.getCooldowns().isOnCooldown(item)
                    && player.getCooldowns().getCooldownPercent(item, 0f) <= CLOSE_VIEWFINDER_AT_COOLDOWN) {
                closeViewfinder(player, item, camera);
            }
            return;
        }
        if (DisposableCamera.isShot(camera)) {
            // The frame has left the camera: Polaroid printed the photograph this tick. The photo
            // takes the camera's place in the inventory as the camera drops.
            ExposureIdentifier printed = PENDING_PRINTS.remove(player.getUUID());
            ItemStack photograph = printed == null ? ItemStack.EMPTY : takePrintedPhotograph(inventory, printed);
            inventory.setItem(slot, photograph);
            StartingBookEvents.dropAndBurn(player, camera);
            // Polaroid prints on the client too, into the slot the server just emptied again. The
            // server sees no net change there and would never correct it, leaving a second photo.
            player.inventoryMenu.sendAllDataToRemote();
            if (player.containerMenu != player.inventoryMenu) {
                player.containerMenu.sendAllDataToRemote();
            }
        }
    }

    /**
     * Takes the photograph Polaroid just printed back out of the inventory — it lands in the first
     * free slot, or on top of a stack of photographs — so it can be put where the camera was. Empty
     * if it isn't there (the inventory was full and it was dropped).
     */
    private static ItemStack takePrintedPhotograph(Inventory inventory, ExposureIdentifier printed) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isPhotographOf(stack, printed)) {
                inventory.setItem(slot, ItemStack.EMPTY);
                return stack;
            }
            if (stack.getItem() instanceof StackedPhotographsItem stacked
                    && stacked.getPhotographs(stack).size() > 0
                    && isPhotographOf(stacked.getPhotographs(stack).getItemUnsafe(0), printed)) {
                ItemStack photograph = stacked.removeTopPhotograph(stack).getItemStack();
                if (stacked.getPhotographs(stack).size() == 1) {
                    inventory.setItem(slot, stacked.removeTopPhotograph(stack).getItemStack());
                }
                return photograph;
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean isPhotographOf(ItemStack stack, ExposureIdentifier identifier) {
        if (!(stack.getItem() instanceof PhotographItem)) {
            return false;
        }
        Frame frame = stack.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        return frame != null && identifier.equals(frame.identifier());
    }

    private static void closeViewfinder(ServerPlayer player, CameraItem item, ItemStack camera) {
        item.deactivate(player, camera);
        ((CameraOperator) player).removeActiveExposureCamera();
        player.getCooldowns().addCooldown(item, PRINT_TICKS);
    }

    /** A disposable camera's slide can't be taken out or topped up. */
    @SubscribeEvent
    public static void onItemStackedOn(ItemStackedOnOtherEvent event) {
        if (event.getClickAction() == ClickAction.SECONDARY && DisposableCamera.is(event.getStackedOnItem())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(DisposableCamera.create(), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        }
    }

    /**
     * The player closed the photograph view. Burns the disposable-camera photos in the hand they
     * viewed from — the photo itself, or each such photo in a stack of photographs.
     */
    public static void handlePhotographViewClosed(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            if (DisposableCamera.burnsAfterViewing(held)) {
                player.setItemInHand(hand, ItemStack.EMPTY);
                StartingBookEvents.dropAndBurn(player, held);
                return;
            }
            if (held.getItem() instanceof StackedPhotographsItem stacked && DisposableCamera.holdsBurnAfterViewing(held)) {
                burnFlaggedFromStack(player, hand, stacked, held);
                return;
            }
        }
    }

    private static void burnFlaggedFromStack(ServerPlayer player, InteractionHand hand,
                                             StackedPhotographsItem stacked, ItemStack held) {
        for (int index = stacked.getPhotographs(held).size() - 1; index >= 0; index--) {
            if (DisposableCamera.burnsAfterViewing(stacked.getPhotographs(held).getItemUnsafe(index))) {
                StartingBookEvents.dropAndBurn(player, stacked.removePhotograph(held, index).getItemStack());
            }
        }
        int left = stacked.getPhotographs(held).size();
        if (left == 0) {
            player.setItemInHand(hand, ItemStack.EMPTY);
        } else if (left == 1) {
            player.setItemInHand(hand, stacked.removeTopPhotograph(held).getItemStack());
        }
    }
}
