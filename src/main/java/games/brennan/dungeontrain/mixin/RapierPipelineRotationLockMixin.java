package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import games.brennan.dungeontrain.ship.sable.TrainRotationLock;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carriage rotation lock, impulse side — the companion to
 * {@link SubLevelPhysicsSystemRotationLockMixin}. That one flattens the pose Sable reads back; this
 * one stops the spin being applied in the first place, so a locked carriage never accumulates
 * angular velocity that the flatten would have to keep erasing.
 *
 * <p>For a rotation-locked body:
 * <ul>
 *   <li>{@code applyLinearAndAngularImpulse} — the angular argument is replaced with zero. The
 *       linear push is kept: a locked carriage may still be shoved, it may not be spun.</li>
 *   <li>{@code applyImpulse} — the point-impulse form ({@code impulse}, {@code point}); its whole
 *       effect on rotation is the torque about the centre of mass, and there is no way to keep the
 *       linear half without recomputing it, so it is cancelled outright. DT re-establishes the
 *       carriage's velocity from its driver every tick anyway, so nothing is lost.</li>
 *   <li>{@code teleport} — the rotation argument is replaced with identity, so no caller can park a
 *       locked carriage at an angle.</li>
 * </ul>
 *
 * <p>{@code addLinearAndAngularVelocity} is deliberately <b>not</b> gated:
 * {@code SableManagedShip.applyTickOutput} uses it to negate whatever angular velocity accumulated
 * during the tick, and clamping its argument would strand exactly the spin it exists to cancel.
 * Anything Sable adds through it is neutralised by that negate plus the per-substep pose flatten.
 *
 * <p>String target + {@code remap = false}: {@code RapierPhysicsPipeline} ships in Sable's
 * jar-in-jar and is not on DT's compile classpath — same pattern and the same method signatures as
 * {@link RapierPipelineFreezeMixin}. <b>Re-audit this method set on any {@code sable_version}
 * bump.</b></p>
 */
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class RapierPipelineRotationLockMixin {

    @Unique private static final Vector3d DUNGEON_TRAIN$NO_SPIN = new Vector3d();
    @Unique private static final Quaterniond DUNGEON_TRAIN$UPRIGHT = new Quaterniond();

    /** Angular arg of {@code applyLinearAndAngularImpulse(body, linear, angular, wake)}. */
    @ModifyVariable(method = "applyLinearAndAngularImpulse", at = @At("HEAD"), argsOnly = true, index = 3)
    private Vector3dc dungeonTrain$stripAngularImpulse(Vector3dc value, PhysicsPipelineBody body,
                                                       Vector3dc linear, Vector3dc angular,
                                                       boolean wake) {
        return TrainRotationLock.isLocked(body) ? DUNGEON_TRAIN$NO_SPIN : value;
    }

    @Inject(method = "applyImpulse", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$dropPointImpulse(PhysicsPipelineBody body, Vector3dc impulse,
                                               Vector3dc point, CallbackInfo ci) {
        if (TrainRotationLock.isLocked(body)) ci.cancel();
    }

    /** Rotation arg of {@code teleport(body, position, rotation)}. */
    @ModifyVariable(method = "teleport", at = @At("HEAD"), argsOnly = true, index = 3)
    private Quaterniondc dungeonTrain$uprightTeleport(Quaterniondc value, PhysicsPipelineBody body,
                                                      Vector3dc position, Quaterniondc rotation) {
        return TrainRotationLock.isLocked(body) ? DUNGEON_TRAIN$UPRIGHT : value;
    }
}
