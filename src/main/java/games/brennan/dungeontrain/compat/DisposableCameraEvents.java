package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.photo.SharedPhotos;
import games.brennan.dungeontrain.event.StartingBookEvents;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.neoforge.api.event.ModifyFrameExtraDataEvent;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.entity.CameraOperator;
import io.github.mortuusars.exposure.world.item.StackedPhotographsItem;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Behaviour of the {@link DisposableCamera}: one shot, the viewfinder closes so the print can be
 * watched, the camera burns once the photo is out, and the photo burns after it has been viewed.
 *
 * <p>Nothing here burns on a drop — an unused camera or an unviewed photo can be dropped, stored or
 * lost on death like any item. The burns are started explicitly through
 * {@link StartingBookEvents#dropAndBurn}, never through the burn-on-drop predicate the books use.</p>
 *
 * <p>A shot runs: release → shutter opens and the frame lands on the camera stack → shutter closes and
 * the item cooldown starts (Polaroid's print animation is that cooldown) → this class closes the
 * viewfinder and restarts the cooldown → as it runs out, this class prints the photograph into the
 * camera's slot and burns the camera. It watches the camera stack through those states from the
 * server player tick.</p>
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
     * DT prints the photo when this much of that cooldown is left — the last couple of ticks, so the
     * server acts before Polaroid's own print (which fires at zero, on the client too) can.
     */
    static final float PRINT_AT_COOLDOWN = 0.075f;

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
        if (pending == null) {
            if (DisposableCamera.isShot(camera)) {
                // Polaroid got to the print first (it shouldn't); the spent camera still burns.
                inventory.setItem(slot, ItemStack.EMPTY);
                StartingBookEvents.dropAndBurn(player, camera);
            }
            return;
        }
        if (!DisposableCamera.isShot(camera)) {
            DisposableCamera.markShot(camera);
        }
        if (item.getShutter().isOpen(camera)) {
            return;
        }
        boolean coolingDown = player.getCooldowns().isOnCooldown(item);
        float cooldownLeft = coolingDown ? player.getCooldowns().getCooldownPercent(item, 0f) : 0f;
        if (item.isActive(camera)) {
            if (cooldownLeft <= CLOSE_VIEWFINDER_AT_COOLDOWN) {
                closeViewfinder(player, item, camera);
            }
            return;
        }
        if (cooldownLeft <= PRINT_AT_COOLDOWN) {
            printAndBurn(player, inventory, slot, camera, pending);
        }
    }

    /**
     * The print animation is done: in one tick the photograph takes the camera's inventory slot and
     * the camera drops and burns. DT prints it rather than leaving it to Polaroid, which would put
     * the photo in the first free slot — on the client as well as the server — a moment later.
     */
    private static void printAndBurn(ServerPlayer player, Inventory inventory, int slot, ItemStack camera, Frame frame) {
        camera.remove(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        ItemStack photograph = new ItemStack(Exposure.Items.PHOTOGRAPH.get());
        photograph.set(Exposure.DataComponents.PHOTOGRAPH_FRAME, frame);
        photograph.set(Exposure.DataComponents.PHOTOGRAPH_TYPE, frame.type());
        photograph.setPopTime(Inventory.POP_TIME_DURATION);
        // Camera out first, then the photo into the slot it left — same tick.
        inventory.setItem(slot, ItemStack.EMPTY);
        StartingBookEvents.dropAndBurn(player, camera);
        inventory.setItem(slot, photograph);
        SharedPhotos.queueUpload(player, photograph);
        player.level().playSound(null, player, Exposure.SoundEvents.PHOTOGRAPH_RUSTLE.get(), SoundSource.PLAYERS,
            0.6f, player.level().getRandom().nextFloat() * 0.2f + 1.0f);
        Exposure.CriteriaTriggers.FRAME_PRINTED.get().trigger(player, player.blockPosition(), frame, photograph);
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

    /**
     * The player closed the photograph view. Burns the disposable-camera photos in the hand they
     * viewed from — the photo itself, or each such photo in a stack of photographs.
     */
    public static void handlePhotographViewClosed(ServerPlayer player) {
        // A found photo does not burn; closing it only counts as one of its views.
        if (SharedPhotos.reportView(player)) return;
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
