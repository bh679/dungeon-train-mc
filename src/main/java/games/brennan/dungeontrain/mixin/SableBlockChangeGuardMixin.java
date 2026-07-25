package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.SableCommonEvents;
import games.brennan.dungeontrain.ship.sable.WorldgenForceGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels Sable's block-change physics hook while a Dungeon Train <b>synchronous forced chunk
 * generation</b> is in progress on this thread, breaking the re-entrant {@code getChunk} deadlock
 * that otherwise freezes the server at spawn (see {@link WorldgenForceGuard} for the full chain
 * and the safety rationale).
 *
 * <p>{@code SableCommonEvents.handleBlockChange} is the entry point Sable's own
 * {@code plot.LevelChunkMixin} (on {@code LevelChunk.setBlockState}) calls for every block
 * change. During a DT forced {@code getChunk(FULL, true)}, a fluid tick in
 * {@code postProcessGeneration} sets a block → this hook → {@code SubLevelPhysicsSystem} →
 * {@code LevelAccelerator.getBlockState} → a second {@code getChunk} nested inside the outer
 * managedBlock → permanent deadlock. Skipping the hook for the duration of the forced gen avoids
 * the re-entry; the suppressed block changes are terrain being generated, whose physics is
 * recomputed when the sub-level is assembled/loaded.</p>
 *
 * <p>Mirrors the gating pattern of {@link LevelAcceleratorNoSyncLoadMixin} /
 * {@link SubLevelEntityCollisionNoLoadMixin} (Sable's own {@code LevelAccelerator} sync-loading
 * on the server thread, fixed per-instance). {@code remap = false}: {@code SableCommonEvents} and
 * {@code handleBlockChange} are Sable's own names. Bytecode-verified against
 * {@code sable-2.0.2+mc1.21.1} — {@code public static void handleBlockChange(ServerLevel,
 * LevelChunk, int, int, int, BlockState, BlockState)}. <b>Re-verify on any {@code sable_version}
 * bump.</b></p>
 */
@Mixin(value = SableCommonEvents.class, remap = false)
public abstract class SableBlockChangeGuardMixin {

    @Inject(method = "handleBlockChange", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$skipDuringWorldgenForce(
            final ServerLevel level, final LevelChunk chunk, final int x, final int y, final int z,
            final BlockState oldState, final BlockState newState, final CallbackInfo ci) {
        if (WorldgenForceGuard.isActive()) {
            ci.cancel();
        }
    }
}
