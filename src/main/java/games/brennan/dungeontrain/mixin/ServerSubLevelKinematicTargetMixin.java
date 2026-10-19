package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import games.brennan.dungeontrain.ship.sable.DtKinematicPoseHolder;
import games.brennan.dungeontrain.ship.sable.KinematicPoseTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Gives every {@code ServerSubLevel} a {@link KinematicPoseTarget}. Field only — the record is
 * written by {@code SableManagedShip.applyTickOutput} and read by
 * {@link SubLevelPhysicsSystemKinematicPoseMixin}. Sable re-creates {@code ServerSubLevel}
 * instances on reload; a fresh one starts with no record, which reads as stale until the next
 * teleport, so nothing carries over wrongly.
 *
 * <p>{@code remap = false}: Sable's own class.</p>
 */
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class ServerSubLevelKinematicTargetMixin implements DtKinematicPoseHolder {

    @Unique private final KinematicPoseTarget dungeonTrain$kinematicTarget = new KinematicPoseTarget();

    @Override
    public KinematicPoseTarget dt$kinematicTarget() {
        return dungeonTrain$kinematicTarget;
    }
}
