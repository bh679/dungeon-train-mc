package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision.CollisionInfo;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision.FirstCollisionInfo;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;

/**
 * Whether this tick's {@code horizontalCollision} means "pushing into the ladder".
 *
 * <p>Vanilla climbs a ladder when {@code horizontalCollision && onClimbable()} — the collision
 * flag is its proxy for the player walking into the ladder. Sable ORs its own
 * {@code subLevelHorizontalCollision} into that flag, and that one is set whenever a sub-level
 * block's SAT push resolves a horizontal overlap — including when the moving carriage's wall
 * simply catches up with an entity that is not standing on the deck. A rider on a ladder is
 * off the ground, so Sable isn't carrying them positionally; the ladder plate advances into them
 * every tick, the flag is true every tick, and vanilla hauls them up the ladder with no input.</p>
 *
 * <p>Sable keeps the pieces apart in {@link CollisionInfo}: {@code horizontalCollision} is the
 * entity's own motion clipped by <i>world</i> blocks, {@code subLevelHorizontalCollision} the
 * push, {@code preDeltaMovement} the entity's own motion before the collide, and
 * {@code firstCollisions} the push direction per sub-level (normalised MTV, block → entity).
 * So a ship push counts as climb input only when the entity's own motion was into the block
 * that pushed it — which is exactly the vanilla condition.</p>
 */
public final class SableClimbPush {

    /** Own horizontal motion into the wall below this is "standing still" (vanilla's small-motion scale). */
    static final double INTO_WALL_EPSILON = 0.003;

    private SableClimbPush() {}

    /**
     * @param horizontalCollision the entity's {@code horizontalCollision} field as vanilla reads it
     * @param info                Sable's collision info for this move, or {@code null}
     * @return what the ladder check should see instead
     */
    public static boolean countsAsPush(boolean horizontalCollision, CollisionInfo info) {
        if (!horizontalCollision) return false;
        if (info == null || !info.subLevelHorizontalCollision || info.horizontalCollision) return true;
        return ownMotionIntoAnyWall(info);
    }

    private static boolean ownMotionIntoAnyWall(CollisionInfo info) {
        Vec3 own = info.preDeltaMovement;
        if (own == null || info.firstCollisions == null) return false;
        for (FirstCollisionInfo first : info.firstCollisions.values()) {
            if (first.horizontal() && movesInto(own, first.globalDirection())) return true;
        }
        return false;
    }

    /** {@code direction} points from the block toward the entity; motion into the block is against it. */
    static boolean movesInto(Vec3 own, Vector3dc direction) {
        return own.x * direction.x() + own.z * direction.z() < -INTO_WALL_EPSILON;
    }
}
