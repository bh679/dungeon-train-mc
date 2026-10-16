package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.train.PlayerPlacedTrainBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A bamboo shoot's first growth on a carriage: the new stalk above it is marked as a passenger's
 * block ({@link PlayerPlacedTrainBlocks}), like every later stalk ({@link BambooStalkGrowthMixin}).
 *
 * <p>The shoot itself keeps its claim. Writing the stalk above notifies the shoot, whose
 * {@code updateShape} swaps it for {@code minecraft:bamboo} — a change of block type, which clears a
 * player's mark ({@code SableBlockChangeGuardMixin}). So whether the shoot was marked is read before
 * growth and restored after it. Server thread only, and {@code growBamboo} does not re-enter itself,
 * so one field holds the answer across the two injects.</p>
 */
@Mixin(BambooSaplingBlock.class)
public abstract class BambooSaplingGrowthMixin {

    @Unique
    private static boolean dungeontrain$shootWasMarked;

    @Inject(method = "growBamboo", at = @At("HEAD"))
    private void dungeontrain$readShootMark(Level level, BlockPos pos, CallbackInfo ci) {
        dungeontrain$shootWasMarked = level instanceof ServerLevel server
            && TrackGenerator.isShipyardChunk(pos.getX() >> 4, pos.getZ() >> 4)
            && PlayerPlacedTrainBlocks.isPlayerPlaced(server, pos);
    }

    @Inject(method = "growBamboo", at = @At("TAIL"))
    private void dungeontrain$markGrownStalk(Level level, BlockPos pos, CallbackInfo ci) {
        boolean shootWasMarked = dungeontrain$shootWasMarked;
        dungeontrain$shootWasMarked = false;
        if (!(level instanceof ServerLevel server)) return;
        if (!TrackGenerator.isShipyardChunk(pos.getX() >> 4, pos.getZ() >> 4)) return;
        BlockPos grown = pos.above();
        if (server.getBlockState(grown).is(Blocks.BAMBOO)) PlayerPlacedTrainBlocks.markIfCarriage(server, grown);
        if (shootWasMarked && !server.getBlockState(pos).isAir()) PlayerPlacedTrainBlocks.markIfCarriage(server, pos);
    }
}
