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

    /** True iff {@code body} is a sub-level DT has marked rotation-locked. Null-safe. */
    public static boolean isLocked(Object body) {
        return body instanceof ServerSubLevel subLevel
            && subLevel instanceof DtRotationLockable lockable
            && lockable.dt$isRotationLocked();
    }
}
