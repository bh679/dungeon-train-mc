package games.brennan.dungeontrain.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.EndGatewayConfiguration;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The far end of an End gateway trip along the ride ({@code event/EndGatewayBandJump}): a gateway already standing
 * near the exit is used as it is, so gateways close together don't build over each other's partner; otherwise
 * vanilla's own gateway is built on the ground there. Its exit is never read: the trip back goes through the pair.
 */
public final class EndGatewayExit {

    /** How far around the exit a gateway already there is reused. */
    private static final int REUSE_RADIUS = 16;

    /** Vanilla's gateway has bedrock 2 below its centre. */
    private static final int CENTRE_ABOVE_GROUND = 2;

    /** Clear of the gateway's bedrock, which reaches 1 block sideways. */
    private static final int LANDING_OFFSET = 3;

    /** Ground further than this from the gateway's foot isn't the ground it stands on: a landing pad goes there. */
    private static final int GROUND_REACH = 8;

    private EndGatewayExit() {}

    /** The gateway to arrive at for column {@code x, z}: one already near it, or a new one on the ground. */
    public static BlockPos findOrBuild(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        BlockPos near = gatewayNear(level, x, z);
        if (near != null) return near;
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (ground <= level.getMinBuildHeight() + 1) ground = level.getSeaLevel();     // nothing below: don't build in the void's floor
        int centre = Math.min(ground + CENTRE_ABOVE_GROUND, level.getMaxBuildHeight() - 3);
        BlockPos gateway = new BlockPos(x, centre, z);
        Feature.END_GATEWAY.place(EndGatewayConfiguration.delayedExitSearch(), level,
                level.getChunkSource().getGenerator(), level.getRandom(), gateway);
        return gateway;
    }

    /** Where to stand on arriving at {@code gateway}: beside it, on the ground or a small pad, head clear. */
    public static Vec3 landingBeside(ServerLevel level, BlockPos gateway) {
        int x = gateway.getX() + LANDING_OFFSET;
        int z = gateway.getZ();
        level.getChunk(x >> 4, z >> 4);
        int foot = gateway.getY() - CENTRE_ABOVE_GROUND;
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int y = Math.abs(ground - foot) <= GROUND_REACH ? ground : foot;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y - 1, z);
        if (!level.getBlockState(p).isSolid()) placePad(level, x, y - 1, z);
        level.setBlock(p.set(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(p.set(x, y + 1, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        return new Vec3(x + 0.5, y, z + 0.5);
    }

    private static void placePad(ServerLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(p.set(x + dx, y, z + dz), Blocks.END_STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    @Nullable
    private static BlockPos gatewayNear(ServerLevel level, int x, int z) {
        BlockPos best = null;
        double bestDistance = (double) REUSE_RADIUS * REUSE_RADIUS;
        for (int cx = (x - REUSE_RADIUS) >> 4; cx <= (x + REUSE_RADIUS) >> 4; cx++) {
            for (int cz = (z - REUSE_RADIUS) >> 4; cz <= (z + REUSE_RADIUS) >> 4; cz++) {
                for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (!(be instanceof TheEndGatewayBlockEntity)) continue;
                    double dx = be.getBlockPos().getX() - x;
                    double dz = be.getBlockPos().getZ() - z;
                    double distance = dx * dx + dz * dz;
                    if (distance <= bestDistance) {
                        bestDistance = distance;
                        best = be.getBlockPos().immutable();
                    }
                }
            }
        }
        return best;
    }
}
