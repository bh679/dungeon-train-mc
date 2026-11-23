package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.editor.EditorFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The hook for the editor's Fluid flow setting ({@link EditorFluids}): fluid in an editor plot does
 * not spread while the setting is Off.
 *
 * <p>{@code spread}, not {@code tick}. {@code FlowingFluid.tick} first re-evaluates a non-source
 * cell against its neighbours, and only then spreads. Cancelling the spread stops every new flow —
 * from a placed source, and from a waterlogged block — while flow already standing with no source
 * behind it still drains away; cancelling the whole tick would freeze it in place for good. Like the
 * other {@code FlowingFluid} mixins this one only cancels, so they never need ordering.</p>
 */
@Mixin(FlowingFluid.class)
public class FlowingFluidEditorStillMixin {
    @Inject(
        method = "spread(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/FluidState;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dungeontrain$holdStillInEditorPlots(Level level, BlockPos pos, FluidState state, CallbackInfo ci) {
        if (EditorFluids.isStill(level, pos)) ci.cancel();
    }
}
