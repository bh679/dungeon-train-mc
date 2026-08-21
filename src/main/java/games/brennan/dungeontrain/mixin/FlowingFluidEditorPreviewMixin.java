package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.editor.EditorPreviewLiquids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Freezes liquids the editor is previewing — they render in their cell, they do not flow.
 *
 * <p>A variant cell can hold a water or lava candidate, and
 * {@code VariantEditorPreviewTicker} stamps whichever candidate the cycle lands on into the plot so
 * the author can see it. A fluid source stamped that way behaves like any other: it schedules a
 * tick and spreads. In an editor plot that is destructive rather than merely untidy — <b>the plot's
 * world blocks are the template until the author saves</b>, so spread water gets baked into the
 * saved {@code .nbt} as authored water, and lava burns the build on its way out.</p>
 *
 * <p>Direct sibling of {@link FlowingFluidExternalWaterMixin} /
 * {@link FlowingFluidDisintegrationMixin} / {@link FlowingFluidChuncksMixin} /
 * {@link FlowingFluidUpsideDownMixin}: same {@code HEAD}-cancellable hook on
 * {@link FlowingFluid#canSpreadTo}, cancel-only so injection order is irrelevant and they compose.
 * Both endpoints are vetoed — a preview cell may not push into its neighbours, and neighbouring
 * fluid may not push into a preview cell and overwrite it.</p>
 *
 * <p>Keyed on exact positions ({@link EditorPreviewLiquids}) rather than an editor-region box, so
 * real-world fluid at editor altitude is never caught by accident, and the whole check is one
 * volatile read whenever nothing is being previewed. Lava's fire spread is a separate path —
 * see {@link LavaFluidEditorPreviewMixin}.</p>
 */
@Mixin(FlowingFluid.class)
public class FlowingFluidEditorPreviewMixin {

    @Inject(
        method = "canSpreadTo(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/Fluid;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void dungeontrain$freezeEditorPreviewLiquid(
        BlockGetter level,
        BlockPos fromPos,
        BlockState fromBlockState,
        Direction direction,
        BlockPos toPos,
        BlockState toBlockState,
        FluidState toFluidState,
        Fluid fluid,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!(level instanceof ServerLevel server)) return;
        if (EditorPreviewLiquids.isPreviewLiquid(server.dimension(), fromPos)
            || EditorPreviewLiquids.isPreviewLiquid(server.dimension(), toPos)) {
            cir.setReturnValue(false);
        }
    }
}
