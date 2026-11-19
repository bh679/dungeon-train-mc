package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.DiskTestMemo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every write forgets the {@link DiskTestMemo} answers that read the written block. At HEAD, before
 * {@link WorldGenRegionFloorMixin} may cancel the write: a cancelled write only forgets answers that still hold.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionDiskMemoMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void dungeontrain$forgetDiskTests(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                              CallbackInfoReturnable<Boolean> cir) {
        DiskTestMemo.onWrite((WorldGenRegion) (Object) this, pos);
    }
}
