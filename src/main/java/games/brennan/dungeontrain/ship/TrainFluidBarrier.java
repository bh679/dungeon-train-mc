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
 * <p>This is a hot path: vanilla ticks every fluid block of a chunk it promotes, and each
 * tick asks {@code canSpreadTo} up to five times, so an ocean chunk means thousands of calls
 * in one server tick. The test is therefore two-stage. First {@link ResidentCarriageBoxes}'
 * per-tick snapshot of grown carriage boxes rejects the position with one primitive bounds
 * test and no allocation, which is the answer for every fluid tick that is not right beside
 * the train. Only a candidate from that snapshot is settled against the carriage's live
 * {@link ManagedShip#worldAABB()}, so the final answer is exactly what the live walk gave
 * before the snapshot existed.</p>
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
        double cx = toPos.getX() + 0.5;
        double cy = toPos.getY() + 0.5;
        double cz = toPos.getZ() + 0.5;

        // The per-tick snapshot is unsynchronised; anything off the server thread walks the
        // shipyard itself.
        if (!level.getServer().isSameThread()) {
            return blocksExternalFlowIntoUncached(level, toPos, cx, cy, cz);
        }

        CarriageBoxIndex<ManagedShip> index = ResidentCarriageBoxes.forTick(level);
        if (index.size() == 0 || !index.mayContain(cx, cy, cz)) {
            return false;
        }
        for (int i = index.nextCandidate(cx, cy, cz, 0); i >= 0; i = index.nextCandidate(cx, cy, cz, i + 1)) {
            ManagedShip ship = index.handle(i);
            // The snapshot's box is grown and may be a tick old; the live box decides. A ship
            // culled since the snapshot reports a frozen last-known box, so residency is re-read too.
            if (ship.isResident() && ship.worldAABB().containsPoint(cx, cy, cz)) {
                return !isSubLevelInternal(level, toPos);
            }
        }
        return false;
    }

    /** The pre-snapshot test: walk every ship, read every live box. */
    static boolean blocksExternalFlowIntoUncached(ServerLevel level, BlockPos toPos,
                                                  double cx, double cy, double cz) {
        // Cheap early-out: no carriages loaded means nothing to dam.
        List<ManagedShip> ships = Shipyards.of(level).findAll();
        if (ships.isEmpty()) {
            return false;
        }
        for (ManagedShip ship : ships) {
            // Skip culled/removed carriages: a stale wrapper reports a frozen
            // last-known AABB that would dam fluid where the train no longer is
            // (see project_stale_ghost_aabb_collision).
            if (!ship.isResident()) {
                continue;
            }
            if (ship.worldAABB().containsPoint(cx, cy, cz)) {
                return !isSubLevelInternal(level, toPos);
            }
        }
        return false;
    }

    /**
     * Belt-and-suspenders: a destination that is genuinely a sub-level-internal spread (target
     * inside a Sable plot, not just the rendered footprint) is left to Sable's own
     * FlowingFluidMixin. Only reached for the rare position already inside a carriage box, so
     * this lookup stays off the hot path.
     */
    private static boolean isSubLevelInternal(ServerLevel level, BlockPos toPos) {
        SubLevel containing = Sable.HELPER.getContaining(level, toPos);
        return containing != null;
    }
}
