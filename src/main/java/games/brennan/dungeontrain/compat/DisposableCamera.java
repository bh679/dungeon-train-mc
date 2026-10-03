package games.brennan.dungeontrain.compat;

import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.item.PhotographItem;
import io.github.mortuusars.exposure.world.item.StackedPhotographsItem;
import io.github.mortuusars.exposure_polaroid.ExposurePolaroid;
import io.github.mortuusars.exposure_polaroid.world.item.InstantCameraItem;
import io.github.mortuusars.exposure_polaroid.world.item.camera.InstantCameraAttachment;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;

/**
 * The disposable camera — a one-shot instant camera that needs no slides.
 *
 * <p>Not an item of DT's own. Exposure: Polaroid ties its viewfinder, hand poses and print animation
 * to its {@code instant_camera} item, so this is that item carrying one {@link FineInstantSlide}
 * already loaded, a DT marker and a DT name. {@link DisposableCameraEvents} gives the marked stack
 * its behaviour: the viewfinder closes after the shot, the camera burns once the photo has printed,
 * and the photo burns after it has been viewed, or when it is dropped.</p>
 *
 * <p>Markers, all booleans:</p>
 * <ul>
 *   <li>{@link #NBT_CAMERA} on the camera stack's custom data — this is a disposable camera.</li>
 *   <li>{@link #NBT_SHOT} on the camera stack's custom data — it has taken its photo, so it burns as
 *       soon as that photo has printed.</li>
 *   <li>{@link #BURN_AFTER_VIEWING} in the photograph frame's extra data — the photo came from a
 *       disposable camera. It rides in the frame, so it survives the photo being stacked.</li>
 * </ul>
 *
 * <p>The print runs on a timer of the camera's own, also in its custom data:</p>
 * <ul>
 *   <li>{@link #NBT_PENDING_FRAME} — the shot's frame, moved off Exposure's
 *       {@code photograph_frame} component. Polaroid prints whatever frame sits on that component
 *       when the item cooldown ends — on the client too — so DT keeps it where Polaroid can't see it.</li>
 *   <li>{@link #NBT_SHOT_TICK} — game time the frame was taken off.</li>
 *   <li>{@link #NBT_PRINT_START} — game time the print animation started. Item cooldowns are per
 *       item, not per stack, so this — not the cooldown — drives the {@code printing} model
 *       predicate; only the camera that shot animates.</li>
 * </ul>
 */
public final class DisposableCamera {

    static final String NBT_CAMERA = "dt_disposable_camera";
    static final String NBT_SHOT = "dt_disposable_shot";
    static final String BURN_AFTER_VIEWING = "dt_burn_after_viewing";
    static final String NBT_PENDING_FRAME = "dt_pending_frame";
    static final String NBT_SHOT_TICK = "dt_shot_tick";
    static final String NBT_PRINT_START = "dt_print_start";
    static final String NAME_KEY = "item.dungeontrain.disposable_camera";

    private DisposableCamera() {}

    /** A fresh disposable camera: one fine slide inside, unshot. */
    public static ItemStack create() {
        ItemStack camera = new ItemStack(ExposurePolaroid.Items.INSTANT_CAMERA.get());
        InstantCameraAttachment.INSTANT_SLIDE.set(camera, FineInstantSlide.create());
        mark(camera, NBT_CAMERA);
        camera.set(DataComponents.ITEM_NAME, Component.translatable(NAME_KEY));
        return camera;
    }

    public static boolean is(ItemStack stack) {
        return stack != null && stack.getItem() instanceof InstantCameraItem && hasMarker(stack, NBT_CAMERA);
    }

    static boolean isShot(ItemStack camera) {
        return hasMarker(camera, NBT_SHOT);
    }

    static void markShot(ItemStack camera) {
        mark(camera, NBT_SHOT);
    }

    /** The shot's frame, held by DT until the photo prints. Empty if none, or if it won't decode. */
    static Optional<Frame> pendingFrame(ItemStack camera, HolderLookup.Provider registries) {
        CompoundTag tag = customTag(camera);
        if (!tag.contains(NBT_PENDING_FRAME, Tag.TAG_COMPOUND)) {
            return Optional.empty();
        }
        return Frame.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag.get(NBT_PENDING_FRAME))
            .result();
    }

    static boolean hasPendingFrame(ItemStack camera) {
        return customTag(camera).contains(NBT_PENDING_FRAME, Tag.TAG_COMPOUND);
    }

    /** Stores the frame; false if it would not encode (the caller then leaves it where it was). */
    static boolean setPendingFrame(ItemStack camera, Frame frame, HolderLookup.Provider registries) {
        Optional<Tag> encoded = Frame.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), frame)
            .result();
        encoded.ifPresent(frameTag ->
            CustomData.update(DataComponents.CUSTOM_DATA, camera, tag -> tag.put(NBT_PENDING_FRAME, frameTag)));
        return encoded.isPresent();
    }

    static void clearPendingFrame(ItemStack camera) {
        CustomData.update(DataComponents.CUSTOM_DATA, camera, tag -> tag.remove(NBT_PENDING_FRAME));
    }

    /** A game-time stamp, or -1 if it has not been set. */
    static long tick(ItemStack stack, String key) {
        CompoundTag tag = customTag(stack);
        return tag.contains(key, Tag.TAG_LONG) ? tag.getLong(key) : -1L;
    }

    static void setTick(ItemStack stack, String key, long gameTime) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putLong(key, gameTime));
    }

    /**
     * How far through its print animation this camera is, 0..1, at {@code gameTime} (fractional on
     * the client, for smooth frames). 0 for a camera that is not printing.
     */
    public static float printProgress(ItemStack camera, double gameTime) {
        long start = tick(camera, NBT_PRINT_START);
        if (start < 0) {
            return 0f;
        }
        double progress = (gameTime - start) / DisposableCameraEvents.PRINT_TICKS;
        return (float) Math.max(0.0, Math.min(1.0, progress));
    }

    /**
     * How much of this camera's "reload" sweep is left, 0..1 — the per-stack stand-in for vanilla's
     * item-cooldown overlay. Full from the shot until the print starts, then shrinking with the print;
     * 0 for a camera that has not shot.
     */
    public static float reloadRemaining(ItemStack camera, double gameTime) {
        if (!isShot(camera)) {
            return 0f;
        }
        if (tick(camera, NBT_PRINT_START) < 0) {
            return 1f;
        }
        return 1f - printProgress(camera, gameTime);
    }

    /** True for a single photograph taken with a disposable camera. */
    public static boolean burnsAfterViewing(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof PhotographItem)) {
            return false;
        }
        Frame frame = stack.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        return frame != null && isFlagged(frame.extraData());
    }

    /** True for a disposable-camera photograph, or a stack of photographs holding at least one. */
    public static boolean holdsBurnAfterViewing(ItemStack stack) {
        if (burnsAfterViewing(stack)) {
            return true;
        }
        if (stack == null || !(stack.getItem() instanceof StackedPhotographsItem stacked)) {
            return false;
        }
        return stacked.getPhotographs(stack).photographs().stream().anyMatch(DisposableCamera::burnsAfterViewing);
    }

    static void flag(CompoundTag frameExtraData) {
        frameExtraData.putBoolean(BURN_AFTER_VIEWING, true);
    }

    static boolean isFlagged(CompoundTag frameExtraData) {
        return frameExtraData != null && frameExtraData.getBoolean(BURN_AFTER_VIEWING);
    }

    static boolean hasMarker(ItemStack stack, String key) {
        return customTag(stack).contains(key, Tag.TAG_BYTE);
    }

    private static CompoundTag customTag(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new CompoundTag();
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null ? data.copyTag() : new CompoundTag();
    }

    static void mark(ItemStack stack, String key) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(key, true));
    }
}
