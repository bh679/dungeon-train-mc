package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * A Nether rest stop ({@link RestStopPlan.Kind#ANCHORS}): the bed room's beds become charged respawn
 * anchors, since beds explode in the Nether and anchors work there. The anchor takes the head block
 * and the foot is cleared. Stamped by code, never by a player, so each anchor is a natural nap spot.
 */
public final class RestStopAnchors {

    private RestStopAnchors() {}

    /** Swaps every bed in the carriage interior at {@code carriageOrigin}. Returns how many. */
    public static int swapBeds(ServerLevel level, BlockPos carriageOrigin, CarriageDims dims) {
        BlockPos from = CarriageContentsPlacer.interiorOrigin(carriageOrigin);
        Vec3i size = CarriageContentsPlacer.interiorSize(dims);
        BlockState anchor = Blocks.RESPAWN_ANCHOR.defaultBlockState()
            .setValue(RespawnAnchorBlock.CHARGE, RespawnAnchorBlock.MAX_CHARGES);
        int swapped = 0;
        for (BlockPos pos : BlockPos.betweenClosed(from, from.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART) != BedPart.HEAD) continue;
            BlockPos head = pos.immutable();
            BlockPos foot = head.relative(BedBlock.getConnectedDirection(state));
            // Foot first, so the head never sees a missing half and drops itself as an item.
            level.setBlock(foot, Blocks.AIR.defaultBlockState(), 2 | 16);
            level.setBlock(head, anchor, 2 | 16);
            swapped++;
        }
        return swapped;
    }
}
