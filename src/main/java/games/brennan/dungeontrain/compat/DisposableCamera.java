package games.brennan.dungeontrain.compat;

import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.item.PhotographItem;
import io.github.mortuusars.exposure.world.item.StackedPhotographsItem;
import io.github.mortuusars.exposure_polaroid.ExposurePolaroid;
import io.github.mortuusars.exposure_polaroid.world.item.InstantCameraItem;
import io.github.mortuusars.exposure_polaroid.world.item.camera.InstantCameraAttachment;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * The disposable camera — a one-shot instant camera that needs no slides.
 *
 * <p>Not an item of DT's own. Exposure: Polaroid ties its viewfinder, hand poses and print animation
 * to its {@code instant_camera} item, so this is that item carrying one {@link FineInstantSlide}
 * already loaded, a DT marker and a DT name. {@link DisposableCameraEvents} gives the marked stack
 * its behaviour: the viewfinder closes after the shot, the camera burns once the photo has printed,
 * and the photo burns after it has been viewed.</p>
 *
 * <p>Markers, all booleans:</p>
 * <ul>
 *   <li>{@link #NBT_CAMERA} on the camera stack's custom data — this is a disposable camera.</li>
 *   <li>{@link #NBT_SHOT} on the camera stack's custom data — it has taken its photo, so it burns as
 *       soon as that photo has printed.</li>
 *   <li>{@link #BURN_AFTER_VIEWING} in the photograph frame's extra data — the photo came from a
 *       disposable camera. It rides in the frame, so it survives the photo being stacked.</li>
 * </ul>
 */
public final class DisposableCamera {

    static final String NBT_CAMERA = "dt_disposable_camera";
    static final String NBT_SHOT = "dt_disposable_shot";
    static final String BURN_AFTER_VIEWING = "dt_burn_after_viewing";
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
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains(key, Tag.TAG_BYTE);
    }

    static void mark(ItemStack stack, String key) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(key, true));
    }
}
