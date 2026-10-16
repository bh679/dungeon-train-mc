package games.brennan.dungeontrain.mixin.client.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.client.ClientPortalSeal;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The portal seal's block trim for <b>fluids</b> on Sodium's meshing pipeline — the counterpart of
 * the vanilla {@code BlockRenderDispatcherLiquidSealMixin}, and of the block-model skip in
 * {@link BlockRendererUpsideDownMixin}.
 *
 * <p>Targeted by class name so Sodium is not a compile dependency. {@code render}'s other parameters
 * are Sodium types, so the world position — the first {@link BlockPos}; the second is the
 * section-local offset — is taken with {@code @Local(argsOnly)} rather than spelled out. Under
 * {@code defaultRequire: 0}, a Sodium refactor of this method costs only the fluid half of the trim.</p>
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer", remap = false)
public abstract class DefaultFluidRendererSealMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void dungeontrain$skipSealedFluid(CallbackInfo ci, @Local(argsOnly = true, ordinal = 0) BlockPos pos) {
        if (ClientPortalSeal.hidesBlock(pos.getX(), pos.getY(), pos.getZ())) ci.cancel();
    }
}
