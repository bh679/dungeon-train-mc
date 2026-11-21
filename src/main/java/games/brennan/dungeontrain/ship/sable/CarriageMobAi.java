package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Vanilla mob AI that looks at "the block beside me", answered by the carriage a mob is riding.
 *
 * <p>Both callers are mixins on vanilla probes that read world blocks next to a mob. On a moving
 * carriage the world there is the open air above the track (the deck lives in the plot), so:</p>
 * <ul>
 *   <li>{@code MoveControl.isWalkable} never says walkable, and a strafing mob (skeletons backing
 *       off, crossbow piglins) walks straight at its target instead. A skeleton ends up 0.5 blocks
 *       from the player and fires every arrow after the first into the deck.</li>
 *   <li>The evoker's fang spell finds no floor, so no fangs appear on a deck.</li>
 * </ul>
 * <p>Every plot block read is checked with {@code getChunkNow} first, so nothing here can load a
 * chunk on the server thread.</p>
 */
public final class CarriageMobAi {

    private CarriageMobAi() {}

    /**
     * Whether the strafe point ({@code relX}, {@code relZ}) from {@code mob} is walkable on the
     * carriage carrying it. False when nothing carries the mob or the plot chunk is not loaded,
     * which leaves vanilla's own answer standing.
     */
    public static boolean walkableOnCarriage(Mob mob, NodeEvaluator evaluator, float relX, float relZ) {
        if (evaluator == null || !(mob.level() instanceof ServerLevel level)) return false;
        SubLevel sub = SableEntityCarry.carrier(mob);
        if (sub == null || sub.isRemoved()) return false;
        BlockPos local = CarriageFrame.localBlockAt(mob.getX() + relX, mob.getY(), mob.getZ() + relZ,
                sub.logicalPose()::transformPositionInverse);
        if (!loaded(level, local) || !loaded(level, local.below())) return false;
        return evaluator.getPathType(mob, local) == PathType.WALKABLE;
    }

    /**
     * Vanilla's fang floor search, run over the carriage's plot blocks instead of the world.
     * Spawns the fang on the deck and returns true; returns false (vanilla runs) when the evoker is
     * not on a carriage or no carriage has a floor in the search window.
     *
     * <p>Each carriage is its own sub-level, and a fang line easily crosses into the next carriage
     * (where the player usually is), so the evoker's own carriage is tried first and then every
     * other sub-level under the fang's column.</p>
     */
    public static boolean spawnFangOnCarriage(Evoker evoker, double x, double z, double minY, double maxY,
                                              float yRot, int warmupDelay) {
        if (!(evoker.level() instanceof ServerLevel level)) return false;
        SubLevel own = SableEntityCarry.carrier(evoker);
        if (own == null || own.isRemoved()) return false;
        if (fangOn(own, level, evoker, x, z, minY, maxY, yRot, warmupDelay)) return true;
        BoundingBox3d column = new BoundingBox3d(x - 0.5, minY - 1.0, z - 0.5, x + 0.5, maxY, z + 0.5);
        for (SubLevel sub : Sable.HELPER.getAllIntersecting(level, column)) {
            if (sub != own && !sub.isRemoved() && fangOn(sub, level, evoker, x, z, minY, maxY, yRot, warmupDelay)) {
                return true;
            }
        }
        return false;
    }

    private static boolean fangOn(SubLevel sub, ServerLevel level, Evoker evoker, double x, double z,
                                  double minY, double maxY, float yRot, int warmupDelay) {
        var pose = sub.logicalPose();
        Vec3 column = pose.transformPositionInverse(new Vec3(x, maxY + CarriageFrame.FEET_EPSILON, z));
        int lowest = Mth.floor(pose.transformPositionInverse(new Vec3(x, minY, z)).y) - 1;
        BlockPos pos = BlockPos.containing(column);
        do {
            BlockPos below = pos.below();
            if (!loaded(level, pos) || !loaded(level, below)) return false;
            if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                Vec3 at = CarriageFrame.worldPointOf(column.x, pos.getY() + shapeTop(level, pos), column.z,
                        pose::transformPosition);
                level.addFreshEntity(new EvokerFangs(level, at.x, at.y, at.z, yRot, warmupDelay, evoker));
                level.gameEvent(GameEvent.ENTITY_PLACE, at, GameEvent.Context.of(evoker));
                return true;
            }
            pos = below;
        } while (pos.getY() >= lowest);
        return false;
    }

    /** Height of the block's collision top inside its own cell (a slab's 0.5), 0 for air. */
    private static double shapeTop(ServerLevel level, BlockPos pos) {
        if (level.isEmptyBlock(pos)) return 0.0;
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        return shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y);
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return OnPosFallback.chunkLoadedFor(pos, key ->
                level.getChunkSource().getChunkNow(net.minecraft.world.level.ChunkPos.getX(key),
                        net.minecraft.world.level.ChunkPos.getZ(key)) != null);
    }
}
