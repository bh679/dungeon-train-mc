package games.brennan.dungeontrain.ship;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.joml.primitives.AABBdc;

/**
 * Decides whether external (world-space) fluid flow into {@code toPos} should
 * be vetoed because that position sits inside a live train carriage.
 *
 * <p>Train carriages are Sable sub-levels: their voxels live in a far-away
 * plot/storage region and are only rendered + collided at the train's world
 * position, so the overworld chunks the train occupies hold air. Vanilla fluid
 * physics consults only real world block state and therefore floods that air.
 * {@code FlowingFluidExternalWaterMixin} vetoes the spread at
 * {@code FlowingFluid#canSpreadTo} by delegating here.</p>
 *
 * <p>This logic lives in a plain (non-mixin) class on purpose: it references
 * JOML ({@link AABBdc}) and Sable types, which are not visible to the mixin
 * transformer's bootstrap classloader. Keeping them out of the mixin body
 * avoids a {@code ClassNotFoundException} at mixin-apply time — the mixin only
 * references Minecraft and Dungeon Train classes.</p>
 */
public final class TrainFluidBarrier {

    private TrainFluidBarrier() {}

    /**
     * @return {@code true} iff {@code toPos} lies within a resident carriage's
     *         world AABB and is <em>not</em> a sub-level-internal position (the
     *         latter is left to Sable's own fluid mixin). Callers veto the
     *         fluid spread when this returns {@code true}.
     */
    public static boolean blocksExternalFlowInto(ServerLevel level, BlockPos toPos) {
        // Cheap early-out: no carriages loaded means nothing to dam.
        List<ManagedShip> ships = Shipyards.of(level).findAll();
        if (ships.isEmpty()) {
            return false;
        }

        double cx = toPos.getX() + 0.5;
        double cy = toPos.getY() + 0.5;
        double cz = toPos.getZ() + 0.5;

        for (ManagedShip ship : ships) {
            // Skip culled/removed carriages: a stale wrapper reports a frozen
            // last-known AABB that would dam fluid where the train no longer is
            // (see project_stale_ghost_aabb_collision).
            if (!ship.isResident()) {
                continue;
            }
            AABBdc box = ship.worldAABB();
            if (box.containsPoint(cx, cy, cz)) {
                // Belt-and-suspenders: a destination that is genuinely a
                // sub-level-internal spread (target inside a Sable plot, not
                // just the rendered footprint) is left to Sable's own
                // FlowingFluidMixin. Only reached for the rare position already
                // inside a carriage AABB, so this lookup stays off the hot path.
                SubLevel containing = Sable.HELPER.getContaining(level, toPos);
                return containing == null;
            }
        }
        return false;
    }
}
