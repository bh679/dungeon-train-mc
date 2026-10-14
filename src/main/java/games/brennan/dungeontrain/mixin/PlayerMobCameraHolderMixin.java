package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.compat.PlayerMobPhotoSubject;
import games.brennan.playermob.entity.PlayerMobEntity;
import io.github.mortuusars.exposure.world.camera.Camera;
import io.github.mortuusars.exposure.world.entity.CameraHolder;
import io.github.mortuusars.exposure.world.entity.CameraOperator;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Optional;
import java.util.UUID;

/**
 * Makes every PlayerMob an Exposure {@link CameraHolder} (and {@link CameraOperator}), so it can take a photo with a camera it
 * holds the way a camera stand does: the shot is <em>rendered by a player's client</em> — the
 * "executing player" — from the holder entity's eyes, and credited to the holder.
 *
 * <p>The executing player is the photo's subject (the player who gifted the camera, see
 * {@link games.brennan.dungeontrain.compat.PlayerMobCameraBridge}), kept here as a session-only
 * {@link PlayerMobPhotoSubject}. While no subject is set the holder has no executing player and
 * Exposure refuses to capture, so an idle PlayerMob can never be made to shoot. Applied on both
 * sides: the client's capture template also asks {@code entity instanceof CameraHolder}.</p>
 */
@Mixin(PlayerMobEntity.class)
public abstract class PlayerMobCameraHolderMixin implements CameraHolder, CameraOperator, PlayerMobPhotoSubject {

    /** Beyond this the subject's client is too far to be asked to render the shot. */
    @Unique
    private static final double DUNGEONTRAIN_SUBJECT_RANGE_SQR = 64.0 * 64.0;

    @Unique
    private UUID dungeontrain$photoSubject;

    @Override
    public UUID dungeontrain$photoSubject() {
        return dungeontrain$photoSubject;
    }

    @Override
    public void dungeontrain$setPhotoSubject(UUID player) {
        this.dungeontrain$photoSubject = player;
    }

    @Unique
    private Optional<Player> dungeontrain$subjectPlayer() {
        if (dungeontrain$photoSubject == null) {
            return Optional.empty();
        }
        Entity self = (Entity) (Object) this;
        Player player = self.level().getPlayerByUUID(dungeontrain$photoSubject);
        if (player == null || !player.isAlive() || player.isSpectator()
                || player.distanceToSqr(self) > DUNGEONTRAIN_SUBJECT_RANGE_SQR) {
            return Optional.empty();
        }
        return Optional.of(player);
    }

    @Override
    public Optional<Player> getPlayerExecutingExposure() {
        return dungeontrain$subjectPlayer();
    }

    /**
     * Nobody: the mob took the photo. Exposure hands this player its "frame exposed" stat and
     * advancement, which the subject did not earn by being photographed.
     */
    @Override
    public Optional<Player> getPlayerAwardedForExposure() {
        return Optional.empty();
    }

    @Override
    public Entity getExposureAuthorEntity() {
        return (Entity) (Object) this;
    }

    @Override
    public Entity asHolderEntity() {
        return (Entity) (Object) this;
    }

    // ---- CameraOperator: the mob operates its own camera, so clients pose its arms around the
    // viewfinder (Exposure's HumanoidModel mixin reads the operator's active camera). The active
    // camera is set by the server through Exposure's own sync packets, as for a player.

    @Unique
    private Camera dungeontrain$activeCamera;

    @Override
    public Camera getActiveExposureCamera() {
        return dungeontrain$activeCamera;
    }

    @Override
    public void setActiveExposureCamera(Camera camera) {
        this.dungeontrain$activeCamera = camera;
    }

    @Override
    public void removeActiveExposureCamera() {
        this.dungeontrain$activeCamera = null;
    }

    @Override
    public Optional<CameraOperator> getExposureCameraOperator() {
        return Optional.of(this);
    }
}
