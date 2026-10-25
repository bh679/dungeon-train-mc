package games.brennan.dungeontrain.mixin.client.journeymap;

import games.brennan.dungeontrain.client.portal.MapSealFilter;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lowers the heightmap top JourneyMap starts a column's surface scan from to the first row it may show
 * ({@link MapSealFilter}): a carriage's ceiling inside one, or a sealed roof, so the day map draws the
 * room and not the world over it.
 *
 * <p>Both classes: {@code SnapshotChunkMD} overrides {@code getPrecipitationHeight} without calling the
 * base one. The clamp is idempotent, so a path through both is harmless. Paired with
 * {@link JmBlockMDSealMixin}, which turns the rows it skips into air.</p>
 */
@Pseudo
@Mixin(targets = {
    "journeymap.client.model.chunk.ChunkMD",
    "journeymap.client.model.chunk.SnapshotChunkMD"
}, remap = false)
public abstract class JmChunkHeightSealMixin {

    @Inject(method = "getPrecipitationHeight(Lnet/minecraft/core/BlockPos;)I",
        at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void dungeontrain$clampToSeal(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        int height = cir.getReturnValueI();
        int clamped = MapSealFilter.clampSurfaceY(pos.getX(), pos.getZ(), height);
        if (clamped != height) cir.setReturnValue(clamped);
    }
}
