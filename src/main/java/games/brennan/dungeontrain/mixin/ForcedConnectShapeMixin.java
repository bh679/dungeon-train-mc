package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.editor.VariantConnect;
import games.brennan.dungeontrain.train.ForcedConnectCells;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the arms of a carriage fence / pane / iron bars / wall that was placed with the
 * {@link VariantConnect.Mode#LOCK} connect mode (the Z menu's connect pill and its N / E / S / W
 * toggles) — for the carriage's whole life.
 *
 * <p><b>Why.</b> Vanilla re-derives these blocks' arms in {@code updateShape} whenever a neighbour
 * changes, and the Sable lift's notify pass ({@code SubLevelAssemblyHelper.moveBlocks} →
 * {@code markAndNotifyBlock(..., 3, 512)}) does exactly that to every lifted cell — so a fence
 * locked with no arms beside another fence would re-join, and a locked arm facing something it
 * can't join would retract. {@link ForcedConnectCells} records which cells are locked and to which
 * arms; this hook re-applies them to whatever vanilla computed.</p>
 *
 * <p><b>Why at RETURN.</b> Each target's {@code updateShape} first schedules the water tick of a
 * waterlogged block; keeping vanilla's body and only overriding the arms of its result keeps that,
 * and keeps the {@code waterlogged} value. The full descriptor is spelled out because
 * {@code WallBlock} also has a private eight-argument {@code updateShape}.</p>
 *
 * <p><b>Cost and sides.</b> {@link ForcedConnectCells#hasAny} short-circuits every call in a world
 * with no forced cells; after that the lookup is a shipyard-coordinate compare before any map read.
 * Server only: the client has no forced-cell data, and its predicted shape is reconciled to the
 * server's by vanilla's block-change acknowledgement.</p>
 */
@Mixin({FenceBlock.class, IronBarsBlock.class, WallBlock.class})
public abstract class ForcedConnectShapeMixin {

    @Inject(
        method = "updateShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;"
            + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)"
            + "Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("RETURN"),
        cancellable = true)
    private void dungeontrain$holdForcedArms(BlockState state, Direction direction, BlockState neighborState,
                                             LevelAccessor level, BlockPos pos, BlockPos neighborPos,
                                             CallbackInfoReturnable<BlockState> cir) {
        if (!ForcedConnectCells.hasAny() || !(level instanceof ServerLevel serverLevel)) return;
        int arms = ForcedConnectCells.lookup(serverLevel, pos);
        if (arms < 0) return;
        BlockState computed = cir.getReturnValue();
        if (computed == null || computed.getBlock() != state.getBlock()) return;
        cir.setReturnValue(VariantConnect.force(computed, arms));
    }
}
