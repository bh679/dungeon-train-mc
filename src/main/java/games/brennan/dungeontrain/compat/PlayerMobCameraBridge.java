package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.compat.PlayerMobPickupHooks;
import games.brennan.playermob.entity.PlayerMobEntity;
import io.github.mortuusars.exposure_polaroid.world.item.InstantCameraItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

/**
 * PlayerMobs and cameras. Two halves, both riding PlayerMob seams (0.106.0+):
 *
 * <ul>
 *   <li><b>Pickup</b> — {@link #install()} gives PlayerMob's {@link PlayerMobPickupHooks} a
 *       floor-gift want (0.106.1+): a mob takes an instant camera (DT's disposable camera is one) off
 *       the floor only when a player threw it and the mob would photograph that player right now
 *       ({@link #wouldPhotograph}). Any other camera — in a chest, dropped by a mob, thrown by someone
 *       it won't photograph — is left where it is.</li>
 *   <li><b>Gift</b> — {@link #onPlayerGift}, reached from {@link PlayerMobSocialBridge}'s observer,
 *       asks the mob to photograph the giver when {@link PhotoRequestPolicy} allows (camera, not in
 *       combat, neutral-or-better feeling). The request is a {@link PlayerMobPhotoSubject} on the mob;
 *       {@link PlayerMobPhotoGoal} carries it out.</li>
 * </ul>
 *
 * <p>Hard references to PlayerMob classes stay inside these methods; the caller guards on
 * {@code ModList.isLoaded} and catches {@link Throwable}, so an older PlayerMob degrades to
 * "mobs ignore cameras".</p>
 */
public final class PlayerMobCameraBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PlayerMobCameraBridge() {}

    /** True for any instant camera — DT's disposable camera, or a plain Polaroid one in creative. */
    public static boolean isCamera(ItemStack stack) {
        return stack != null && stack.getItem() instanceof InstantCameraItem;
    }

    /** Subscribe the camera floor-gift want. */
    public static void install() {
        PlayerMobPickupHooks.installFloorGift(PlayerMobCameraBridge::wouldPhotograph);
    }

    /**
     * True if {@code mob} would photograph {@code giver} for the gift {@code stack} right now: it is a
     * camera, the mob has no photo already queued, and {@link PhotoRequestPolicy} allows it. The same
     * test gates pickup and the photo request, so a mob never takes a camera it won't use.
     */
    public static boolean wouldPhotograph(PlayerMobEntity mob, ServerPlayer giver, ItemStack stack) {
        return mob instanceof PlayerMobPhotoSubject subject
            && subject.dungeontrain$photoSubject() == null
            && PhotoRequestPolicy.shouldPhotograph(isCamera(stack), mob.isInCombat(), mob.feelingToward(giver));
    }

    /** {@code giver} gave {@code gift} to {@code mob}: queue a photo of them if the mob is willing. */
    public static void onPlayerGift(ServerPlayer giver, PlayerMobEntity mob, ItemStack gift) {
        if (!wouldPhotograph(mob, giver, gift)) {
            return;
        }
        ((PlayerMobPhotoSubject) mob).dungeontrain$setPhotoSubject(giver.getUUID());
        // Straight into the main hand: the mob is holding its new camera from the moment it has it.
        mob.equipWeapon(gift.getItem());
        LOGGER.info("[PlayerMobCamera] {} will photograph {}", mob.getName().getString(), giver.getGameProfile().getName());
    }
}
