package games.brennan.dungeontrain.mixin.client.journeymap;

import games.brennan.dungeontrain.client.portal.MapSealFilter;
import journeymap.client.model.block.BlockMD;
import journeymap.client.model.chunk.ChunkMD;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * JourneyMap sees air wherever {@link MapSealFilter} says the player cannot see: everything outside the
 * dimensional carriage they stand in (drawn black), or the sealed side of the seal from anywhere else.
 *
 * <p>{@code BlockMD.getBlockMD(ChunkMD, BlockPos)} is the one lookup every JourneyMap renderer goes
 * through (surface, cave, Nether, End, directly or via {@code getBlockMDFromChunkLocal}), and its scans
 * already step over {@link BlockMD#AIRBLOCK}. See {@link MapSealFilter}. {@code require = 0}: a
 * JourneyMap update that moves the method leaves its map as shipped, and {@code JourneyMapMixinPlugin}
 * logs when this applies.</p>
 */
@Pseudo
@Mixin(value = BlockMD.class, remap = false)
public abstract class JmBlockMDSealMixin {

    @Inject(method = "getBlockMD(Ljourneymap/client/model/chunk/ChunkMD;Lnet/minecraft/core/BlockPos;)Ljourneymap/client/model/block/BlockMD;",
        at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void dungeontrain$sealedIsAir(ChunkMD chunkMd, BlockPos pos, CallbackInfoReturnable<BlockMD> cir) {
        if (MapSealFilter.hidesForMap(pos.getX(), pos.getY(), pos.getZ())) cir.setReturnValue(BlockMD.AIRBLOCK);
    }
}
