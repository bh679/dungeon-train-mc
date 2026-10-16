package games.brennan.dungeontrain.mixin.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.client.ClientPortalSeal;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The portal seal's block trim for <b>fluids</b> on the vanilla meshing pipeline — the counterpart of
 * the block-model skip in {@link ModelBlockRendererUpsideDownMixin}, which fluids never pass through.
 *
 * <p>Without it, lava or water reflected just under the band's lid would still hang in view from an
 * attic twin once the bedrock over it is skipped. Sodium meshes fluids itself; its half is
 * {@code mixin.client.sodium.DefaultFluidRendererSealMixin}.</p>
 */
@Mixin(BlockRenderDispatcher.class)
public abstract class BlockRenderDispatcherLiquidSealMixin {

    @Inject(method = "renderLiquid", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$skipSealedLiquid(BlockPos pos, BlockAndTintGetter level, VertexConsumer consumer,
                                               BlockState blockState, FluidState fluidState, CallbackInfo ci) {
        if (ClientPortalSeal.hidesBlock(pos.getX(), pos.getY(), pos.getZ())) ci.cancel();
    }
}
