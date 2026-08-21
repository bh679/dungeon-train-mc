package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.editor.EditorPreviewLiquids;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops lava the editor is previewing from setting the author's build on fire.
 *
 * <p>{@link FlowingFluidEditorPreviewMixin} freezes a previewed liquid's <i>flow</i>, but lava
 * starts fires on a separate path: {@link LavaFluid#randomTick} seeds fire blocks in nearby air
 * regardless of whether the lava ever spreads. The editor plot's world blocks are the template
 * until the author saves, so a burnt wall is not a cosmetic glitch — it is authored content
 * destroyed, and it saves that way.</p>
 *
 * <p>Cancel-only at {@code HEAD}, and only for positions the preview ticker is actively stamping
 * ({@link EditorPreviewLiquids}), so ordinary lava — including lava an author places in the plot
 * by hand — is left entirely to vanilla.</p>
 */
@Mixin(LavaFluid.class)
public class LavaFluidEditorPreviewMixin {

    @Inject(
        method = "randomTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/util/RandomSource;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dungeontrain$noFireFromEditorPreviewLava(
        Level level, BlockPos pos, FluidState state, RandomSource random, CallbackInfo ci
    ) {
        if (!(level instanceof ServerLevel server)) return;
        if (EditorPreviewLiquids.isPreviewLiquid(server.dimension(), pos)) {
            ci.cancel();
        }
    }
}
