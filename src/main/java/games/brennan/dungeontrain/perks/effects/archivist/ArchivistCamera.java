package games.brennan.dungeontrain.perks.effects.archivist;

import games.brennan.dungeontrain.compat.FineInstantSlide;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import io.github.mortuusars.exposure_polaroid.world.item.camera.InstantCameraAttachment;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * The Extra Cam-Shots perk on a disposable camera: how many reloads it still has, kept on the camera
 * stack as {@value #NBT_EXTRA_SHOTS} so it survives the camera changing hands. Decided once, at the
 * camera's first shot, from the rank its holder has then and how many cameras they have shot with this
 * life ({@code PERK_CAMERAS_USED}); {@code DisposableCameraEvents} reloads the camera instead of burning
 * it while shots remain.
 */
public final class ArchivistCamera {

    static final String NBT_EXTRA_SHOTS = "dt_extra_shots";

    private ArchivistCamera() {}

    /** Decides the camera's extra shots if it has none yet, and counts it as one of this life's cameras. Server side. */
    public static void rollIfNew(ServerPlayer player, ItemStack camera) {
        if (tag(camera).contains(NBT_EXTRA_SHOTS, Tag.TAG_INT)) return;
        int rank = Perks.rank(player, ArchivistPerks.EXTRA_CAM_SHOTS);
        int used = player.getData(ModDataAttachments.PERK_CAMERAS_USED);
        int extra = ExtraCamShots.extraShots(rank, used);
        player.setData(ModDataAttachments.PERK_CAMERAS_USED, used + 1);
        CustomData.update(DataComponents.CUSTOM_DATA, camera, t -> t.putInt(NBT_EXTRA_SHOTS, extra));
    }

    /** Shots this camera may still take after the one printing now. */
    public static int shotsLeft(ItemStack camera) {
        CompoundTag t = tag(camera);
        return t.contains(NBT_EXTRA_SHOTS, Tag.TAG_INT) ? Math.max(0, t.getInt(NBT_EXTRA_SHOTS)) : 0;
    }

    /**
     * Spends one extra shot and loads a fresh slide, clearing the keys the shot and print left behind
     * so the camera reads as unshot again. False when there is no shot left or no slide to load.
     */
    public static boolean reload(ItemStack camera, String... shotKeys) {
        int left = shotsLeft(camera);
        if (left <= 0) return false;
        ItemStack slide = FineInstantSlide.create();
        if (slide.isEmpty()) return false;
        CustomData.update(DataComponents.CUSTOM_DATA, camera, t -> {
            t.putInt(NBT_EXTRA_SHOTS, left - 1);
            for (String key : shotKeys) t.remove(key);
        });
        InstantCameraAttachment.INSTANT_SLIDE.set(camera, slide);
        return true;
    }

    private static CompoundTag tag(ItemStack stack) {
        CustomData data = stack == null || stack.isEmpty() ? null : stack.get(DataComponents.CUSTOM_DATA);
        return data != null ? data.copyTag() : new CompoundTag();
    }
}
