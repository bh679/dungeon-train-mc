package games.brennan.dungeontrain.event;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * The block that killed a player, when a block did: fire, a lit campfire, magma, lava, a cactus,
 * a berry bush, powder snow, water, dripstone, or the wall they suffocated in. Found by scanning
 * the blocks around the body, in the world and, on a carriage, in the carriage's own plot (plot
 * blocks are real chunks of the same level at the shipyard coordinates). Returned in world space
 * at the moment of death. Server only.
 */
public final class DeathHazard {

    private static final int SCAN = 2;

    private DeathHazard() {}

    /** World position of the block centre that caused this death, or null when no block did. */
    public static Vec3 find(ServerPlayer player, DamageSource source) {
        if (!isBlockCause(source)) return null;
        ServerLevel level = player.serverLevel();
        BlockPos body = player.blockPosition();
        BlockPos hit = nearestMatch(level, body, source);
        if (hit != null) return Vec3.atCenterOf(hit);
        SubLevel carriage = Sable.HELPER.getTrackingSubLevel(player);
        if (carriage != null && !carriage.isRemoved()) {
            Vector3d local = carriage.logicalPose().transformPositionInverse(
                new Vector3d(player.getX(), player.getY(), player.getZ()));
            BlockPos localHit = nearestMatch(level, BlockPos.containing(local.x, local.y, local.z), source);
            if (localHit != null) {
                Vector3d world = carriage.logicalPose().transformPosition(
                    new Vector3d(localHit.getX() + 0.5, localHit.getY() + 0.5, localHit.getZ() + 0.5));
                return new Vec3(world.x, world.y, world.z);
            }
        }
        // A block cause with no block found (burning out in the open, say): the body's own feet.
        return Vec3.atCenterOf(body);
    }

    /** Pure: a cause that comes from a block or the ground, not a creature. */
    public static boolean isBlockCause(DamageSource source) {
        return source.is(DamageTypes.IN_FIRE) || source.is(DamageTypes.ON_FIRE) || source.is(DamageTypes.HOT_FLOOR)
            || source.is(DamageTypes.LAVA) || source.is(DamageTypes.CACTUS) || source.is(DamageTypes.SWEET_BERRY_BUSH)
            || source.is(DamageTypes.FREEZE) || source.is(DamageTypes.DROWN) || source.is(DamageTypes.STALAGMITE)
            || source.is(DamageTypes.FALLING_STALACTITE) || source.is(DamageTypes.IN_WALL);
    }

    private static BlockPos nearestMatch(ServerLevel level, BlockPos centre, DamageSource source) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-SCAN, -SCAN, -SCAN), centre.offset(SCAN, SCAN, SCAN))) {
            if (!matches(source, level.getBlockState(pos))) continue;
            double d = pos.distSqr(centre);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** Pure: does this block state fit the cause? */
    static boolean matches(DamageSource source, BlockState state) {
        if (state.isAir()) return false;
        if (source.is(DamageTypes.IN_FIRE) || source.is(DamageTypes.ON_FIRE)) {
            return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.LAVA) || (state.getBlock() instanceof CampfireBlock && CampfireBlock.isLitCampfire(state));
        }
        if (source.is(DamageTypes.HOT_FLOOR)) return state.is(Blocks.MAGMA_BLOCK);
        if (source.is(DamageTypes.LAVA)) return state.is(Blocks.LAVA);
        if (source.is(DamageTypes.CACTUS)) return state.is(Blocks.CACTUS);
        if (source.is(DamageTypes.SWEET_BERRY_BUSH)) return state.is(Blocks.SWEET_BERRY_BUSH);
        if (source.is(DamageTypes.FREEZE)) return state.is(Blocks.POWDER_SNOW);
        if (source.is(DamageTypes.DROWN)) return state.is(Blocks.WATER);
        if (source.is(DamageTypes.STALAGMITE) || source.is(DamageTypes.FALLING_STALACTITE)) return state.is(Blocks.POINTED_DRIPSTONE);
        if (source.is(DamageTypes.IN_WALL)) return state.isSolid();
        return false;
    }
}
