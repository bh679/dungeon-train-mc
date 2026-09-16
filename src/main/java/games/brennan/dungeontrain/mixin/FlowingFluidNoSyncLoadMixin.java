package games.brennan.dungeontrain.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Defers a fluid tick whose spread targets touch an <b>unloaded chunk</b> instead of letting the
 * server thread synchronously load and generate that chunk. Issue #1450.
 *
 * <p><b>Why:</b> {@link FlowingFluid#tick} reads the block and fluid state of its five spread
 * targets ({@code getNewLiquid}, {@code spread}, {@code spreadTo}) through
 * {@code Level.getFluidState}/{@code getBlockState}, which resolve via {@code Level.getChunk}
 * — on the server a <em>blocking</em> {@code ServerChunkCache.getChunk(x, z, FULL, true)}.
 * When a newly generated chunk is promoted to ticking, {@code LevelChunk.postProcessGeneration}
 * ticks every fluid worldgen left on its border, and a spread into the (loaded) neighbour whose
 * own neighbours two chunks out are still proto-chunks parks the server thread on DT's expensive
 * corridor/plot worldgen; while parked it polls further promotion tasks that nest the same thing.
 * Player logs (2026-09-16) show single stalls of 57 s and 129 s with exactly this stack.</p>
 *
 * <p>Fix: at {@code HEAD}, if any spread target is in a chunk that is not fully loaded
 * ({@link Level#hasChunkAt} — non-blocking, {@code getChunk(x, z, FULL, false) != null}), put the
 * tick back on the schedule with the fluid's normal delay and cancel. Nothing is lost: the pending
 * tick is saved with the chunk and runs once the neighbour has loaded on its own. Vanilla would
 * have done the same flow a few ticks earlier at the cost of freezing the server.</p>
 *
 * <p>Cancel-only at {@code HEAD}, server-side only, so it composes with the {@code canSpreadTo}
 * vetoes ({@link FlowingFluidChuncksMixin}, {@link FlowingFluidUpsideDownMixin},
 * {@link FlowingFluidExternalWaterMixin}) regardless of injection order. Pairs with
 * {@link FluidInteractionNoSyncLoadMixin}, which guards the lava↔water interaction check reached
 * from {@code LiquidBlock.neighborChanged} on the same tick.</p>
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidNoSyncLoadMixin {

    /** Spread targets {@code FlowingFluid.tick} reads: straight down plus the four horizontals. */
    private static final Direction[] DUNGEONTRAIN$SPREAD_TARGETS = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    @Inject(
            method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/FluidState;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void dungeontrain$deferTickNextToUnloadedChunk(final Level level, final BlockPos pos,
                                                          final FluidState state, final CallbackInfo ci) {
        if (!(level instanceof ServerLevel server)) return;
        if (dungeontrain$allSpreadTargetsLoaded(server, pos)) return;
        server.scheduleTick(pos, state.getType(), state.getType().getTickDelay(server));
        ci.cancel();
    }

    private static boolean dungeontrain$allSpreadTargetsLoaded(final ServerLevel level, final BlockPos pos) {
        for (final Direction dir : DUNGEONTRAIN$SPREAD_TARGETS) {
            if (!level.hasChunkAt(pos.relative(dir))) return false;
        }
        return true;
    }
}
