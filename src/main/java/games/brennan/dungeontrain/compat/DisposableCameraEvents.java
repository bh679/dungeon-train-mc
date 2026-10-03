package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.photo.SharedPhotos;
import games.brennan.dungeontrain.event.StartingBookEvents;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.neoforge.api.event.ModifyFrameExtraDataEvent;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.entity.CameraOperator;
import io.github.mortuusars.exposure.world.item.StackedPhotographsItem;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import org.slf4j.Logger;

import java.util.Optional;

/**
 * Behaviour of the {@link DisposableCamera}: one shot, the viewfinder closes so the print can be
 * watched, the camera burns once the photo is out, and the photo burns after it has been viewed or
 * whenever it is dropped.
 *
 * <p>An unused camera does not burn on a drop — it can be dropped, stored or lost on death like any
 * item; a spent one is burned explicitly through {@link StartingBookEvents#dropAndBurn}. A photo,
 * like a DT book, burns on any drop: {@link StartingBookEvents#onEntityJoinLevel} ignites it.</p>
 *
 * <p>A shot runs: release → shutter opens and the frame lands on the camera stack → this class moves
 * the frame into the camera's own custom data → shutter closes (Polaroid starts the item cooldown,
 * which blocks a second release while the viewfinder is still up) → this class closes the
 * viewfinder, clears that cooldown and starts the camera's own print timer → when it runs out, this
 * class prints the photograph into the camera's slot and burns the camera. It watches each camera
 * stack through those states from the server player tick.</p>
 *
 * <p>Why not Polaroid's own print: item cooldowns belong to the item, not the stack, so every
 * disposable camera in the inventory would play the print animation and be locked out; and Polaroid
 * prints on the client as well, so whenever the server fell behind the client the player saw two
 * photos. Off the {@code photograph_frame} component, the frame is invisible to Polaroid on both
 * sides.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DisposableCameraEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Ticks after the shot before the viewfinder closes — a short beat, so the client's capture
     * (taken through the viewfinder) is done before the view changes.
     */
    static final int VIEWFINDER_HOLD_TICKS = 10;

    /** Length of the print animation, from the viewfinder closing to the photo coming out. */
    static final int PRINT_TICKS = 40;

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

    /** A spent camera can't be raised again — its viewfinder stays shut until it burns. Both sides. */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        ItemStack stack = event.getItemStack();
        if (DisposableCamera.is(stack) && DisposableCamera.isShot(stack)) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    private static void tickCamera(ServerPlayer player, Inventory inventory, int slot, ItemStack camera) {
        long now = player.level().getGameTime();
        Frame onStack = camera.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        if (onStack != null) {
            takeFrame(player, camera, onStack, now);
            return;
        }
        if (!DisposableCamera.hasPendingFrame(camera)) {
            if (DisposableCamera.isShot(camera)) {
                // Polaroid printed it (only if the frame would not encode); the spent camera still burns.
                inventory.setItem(slot, ItemStack.EMPTY);
                StartingBookEvents.dropAndBurn(player, camera);
            }
            return;
        }
        CameraItem item = (CameraItem) camera.getItem();
        if (item.getShutter().isOpen(camera)) {
            return;
        }
        long printStart = DisposableCamera.tick(camera, DisposableCamera.NBT_PRINT_START);
        if (printStart < 0) {
            long shotTick = DisposableCamera.tick(camera, DisposableCamera.NBT_SHOT_TICK);
            if (shotTick < 0 || now - shotTick >= VIEWFINDER_HOLD_TICKS) {
                startPrint(player, item, camera, now);
            }
            return;
        }
        if (now - printStart >= PRINT_TICKS) {
            printAndBurn(player, inventory, slot, camera);
        }
    }

    /**
     * Moves the shot's frame off the camera stack, where Polaroid would print it, into DT's own data.
     * Also takes a frame left on a camera mid-print by an older DT.
     */
    private static void takeFrame(ServerPlayer player, ItemStack camera, Frame frame, long now) {
        DisposableCamera.markShot(camera);
        if (!DisposableCamera.setPendingFrame(camera, frame, player.registryAccess())) {
            LOGGER.warn("[DisposableCamera] Could not store frame {}; leaving it to Polaroid to print",
                frame.identifier());
            return;
        }
        camera.remove(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        DisposableCamera.setTick(camera, DisposableCamera.NBT_SHOT_TICK, now);
        // The death screen's photo page shows this run's shots, even one still printing at death.
        player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).recordCameraFrame(frame);
    }

    /**
     * Closes the viewfinder and lifts Polaroid's item cooldown — it would lock out every other
     * disposable camera — then starts this camera's own print timer.
     */
    private static void startPrint(ServerPlayer player, CameraItem item, ItemStack camera, long now) {
        if (item.isActive(camera)) {
            item.deactivate(player, camera);
            ((CameraOperator) player).removeActiveExposureCamera();
        }
        player.getCooldowns().removeCooldown(item);
        DisposableCamera.setTick(camera, DisposableCamera.NBT_PRINT_START, now);
    }

    /** The print animation is done: in one tick the photograph takes the camera's slot and the camera drops and burns. */
    private static void printAndBurn(ServerPlayer player, Inventory inventory, int slot, ItemStack camera) {
        Optional<Frame> pending = DisposableCamera.pendingFrame(camera, player.registryAccess());
        DisposableCamera.clearPendingFrame(camera);
        inventory.setItem(slot, ItemStack.EMPTY);
        StartingBookEvents.dropAndBurn(player, camera);
        if (pending.isEmpty()) {
            LOGGER.warn("[DisposableCamera] Stored frame did not decode; the camera burned without a photo");
            return;
        }
        Frame frame = pending.get();
        ItemStack photograph = new ItemStack(Exposure.Items.PHOTOGRAPH.get());
        photograph.set(Exposure.DataComponents.PHOTOGRAPH_FRAME, frame);
        photograph.set(Exposure.DataComponents.PHOTOGRAPH_TYPE, frame.type());
        photograph.setPopTime(Inventory.POP_TIME_DURATION);
        inventory.setItem(slot, photograph);
        SharedPhotos.queueUpload(player, photograph);
        player.level().playSound(null, player, Exposure.SoundEvents.PHOTOGRAPH_RUSTLE.get(), SoundSource.PLAYERS,
            0.6f, player.level().getRandom().nextFloat() * 0.2f + 1.0f);
        Exposure.CriteriaTriggers.FRAME_PRINTED.get().trigger(player, player.blockPosition(), frame, photograph);
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
        // A found photo counts the view and then burns as well.
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
