package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.CinematicCameraController;
import games.brennan.dungeontrain.client.death.DeathCinematic;
import games.brennan.dungeontrain.client.snapshot.RideSnapshotCapture;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Overrides the render camera while the spawn intro cinematic is active.
 *
 * <p>{@code Camera.setup(...)} positions/rotates the camera each frame just
 * before {@code GameRenderer} reads it for the view + projection matrices. We
 * inject at TAIL and, when {@link CinematicCameraController#isActive()},
 * replace the camera position and rotation with the cinematic pose for the
 * frame's {@code partialTick}. Cleanly reverts the frame the controller goes
 * inactive (the inject early-returns). No access transformer needed —
 * {@code setPosition}/{@code setRotation} are {@code protected} and shadowable.</p>
 */
@Mixin(Camera.class)
public abstract class CameraCinematicMixin {

    @Shadow protected abstract void setPosition(Vec3 pos);

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Inject(
        method = "setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
        at = @At("TAIL")
    )
    private void dungeontrain$applyCinematicCamera(
        BlockGetter level, Entity entity, boolean detached, boolean thirdPersonReverse, float partialTick,
        CallbackInfo ci
    ) {
        // Off-screen ride-snapshot pass: apply the snapshot's third-person pose to
        // our own Camera instance. Armed only around RideSnapshotCapture's extra
        // off-screen renderLevel call, so it never touches the player's on-screen camera.
        if (RideSnapshotCapture.isCapturing()) {
            CinematicCameraController.Pose shot = RideSnapshotCapture.capturePose();
            if (shot != null) {
                this.setPosition(shot.pos());
                this.setRotation(shot.yaw(), shot.pitch());
            }
            return;
        }
        // Loading screen holding at 100%: park the camera at the shot's opening pose so the
        // world behind the dim is the composed shot, waiting for Space.
        CinematicCameraController.Pose preview = CinematicCameraController.previewPose();
        if (preview != null) {
            this.setPosition(preview.pos());
            this.setRotation(preview.yaw(), preview.pitch());
            return;
        }
        // The run's last shots: the body, then the train running on without it. Ahead of the
        // intro cinematic's check only because the two can never both be active — a dead player
        // isn't spawning in.
        if (DeathCinematic.isActive()) {
            CinematicCameraController.Pose death = DeathCinematic.computePose(partialTick);
            this.setPosition(death.pos());
            this.setRotation(death.yaw(), death.pitch());
            return;
        }
        if (!CinematicCameraController.isActive()) return;
        CinematicCameraController.Pose pose = CinematicCameraController.computePose(partialTick);
        this.setPosition(pose.pos());
        this.setRotation(pose.yaw(), pose.pitch());
    }
}
