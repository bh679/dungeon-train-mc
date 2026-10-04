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
 *   <li><b>Pickup</b> — {@link #install()} tells PlayerMob's {@link PlayerMobPickupHooks} that an
 *       instant camera (DT's disposable camera is one) is worth picking up off the floor; it is
 *       hoarded in the backpack like a valuable.</li>
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

    /** Subscribe the camera pickup predicate. */
    public static void install() {
        PlayerMobPickupHooks.install(PlayerMobCameraBridge::isCamera);
    }

    /** {@code giver} gave {@code gift} to {@code mob}: queue a photo of them if the mob is willing. */
    public static void onPlayerGift(ServerPlayer giver, PlayerMobEntity mob, ItemStack gift) {
        if (!(mob instanceof PlayerMobPhotoSubject subject)) {
            return;
        }
        boolean willing = PhotoRequestPolicy.shouldPhotograph(isCamera(gift), mob.isInCombat(), mob.feelingToward(giver));
        if (!willing || subject.dungeontrain$photoSubject() != null) {
            return;
        }
        subject.dungeontrain$setPhotoSubject(giver.getUUID());
        LOGGER.debug("[PlayerMobCamera] {} will photograph {}", mob.getName().getString(), giver.getGameProfile().getName());
    }
}
