package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import games.brennan.dungeontrain.ship.KinematicDriver;
import games.brennan.dungeontrain.train.TrainTransformProvider;

/**
 * Read side of the carriage rotation lock. See {@link DtRotationLockable} for what the lock is and
 * why it exists.
 *
 * <p>{@link #isLocked} takes {@link Object} because the physics-pipeline mixin sees bodies typed as
 * {@code PhysicsPipelineBody} while the pose mixin sees {@code ServerSubLevel}; both are the same
 * instance for a sub-level body, and the {@code instanceof} keeps a non-sub-level body (a box, a
 * rope) out.</p>
 */
public final class TrainRotationLock {

    private TrainRotationLock() {}

    /**
     * The driver → lock rule, pure so it unit-tests without a Minecraft/Sable bootstrap (same trick
     * as {@code TrainTransformProvider.shouldReanchor}). Only DT train carriages lock; a sub-level
     * with no driver, or one driven by something else, keeps Sable's normal physics.
     */
    public static boolean locksFor(KinematicDriver driver) {
        return driver instanceof TrainTransformProvider;
    }

    /**
     * True iff {@code body} is a sub-level DT has marked rotation-locked. Null-safe.
     *
     * <p>The flag lives on the {@code ServerSubLevel} <em>instance</em>, but Sable re-creates
     * instances (world load, a cull→reload) while DT's driver survives in
     * {@link SableManagedShip#driverFor}'s UUID-keyed map without {@code setKinematicDriver} being
     * called again. So a clear flag is not proof: fall back to the driver lookup once, and cache a
     * positive answer on the instance so the hot path stays a field read. A negative is never
     * cached — a carriage may gain its driver a tick later.</p>
     */
    public static boolean isLocked(Object body) {
        if (!(body instanceof ServerSubLevel subLevel) || !(subLevel instanceof DtRotationLockable lockable)) {
            return false;
        }
        if (lockable.dt$isRotationLocked()) return true;
        if (!locksFor(SableManagedShip.driverFor(subLevel))) return false;
        lockable.dt$setRotationLocked(true);
        return true;
    }
}
