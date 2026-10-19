package games.brennan.dungeontrain.ship.sable;

/**
 * Duck-type interface mixed into Sable's {@code ServerSubLevel} (by
 * {@link games.brennan.dungeontrain.mixin.ServerSubLevelKinematicTargetMixin}) so each carriage
 * carries its own {@link KinematicPoseTarget} — read every physics substep, so a field, not a map,
 * and it dies with the sub-level.
 */
public interface DtKinematicPoseHolder {

    KinematicPoseTarget dt$kinematicTarget();
}
