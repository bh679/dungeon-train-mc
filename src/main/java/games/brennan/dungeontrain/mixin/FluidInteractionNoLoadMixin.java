package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.event.FluidInteractionDeferralEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops NeoForge's fluid-interaction check from synchronously generating an unloaded neighbour chunk
 * on the server thread — item 2 of #1450.
 *
 * <p>{@code FluidInteractionRegistry.canInteract(level, pos)} runs from {@code LiquidBlock.onPlace}
 * and {@code LiquidBlock.neighborChanged}. It asks each registered interaction predicate about the six
 * face neighbours of {@code pos}; the built-in predicates read them with
 * {@code level.getFluidState(relativePos)} / {@code level.getBlockState(relativePos)}, and on a
 * {@code ServerLevel} that is {@code getChunk(FULL, true)} — a full synchronous generation when the
 * neighbour's chunk is not loaded. A fluid tick inside {@code LevelChunk.postProcessGeneration} at a
 * chunk border did exactly that in player logs (0.902.0 → 0.1001.0), 5–60&nbsp;s per stall and nesting
 * as each generated chunk's post-processing reached into the next:
 * {@code FlowingFluid.spreadTo → Level.setBlock → LiquidBlock.neighborChanged →
 * FluidInteractionRegistry.canInteract:62 → InteractionInformation.lambda$new$2:121 →
 * Level.getFluidState → ServerChunkCache.getChunk (managedBlock)}.</p>
 *
 * <p><b>Defer, don't skip.</b> When {@code pos} sits on a chunk border and a chunk across that border
 * is not loaded ({@code getChunkNow == null}), this answers {@code false} — "no interaction" — and
 * hands the position to {@link FluidInteractionDeferralEvents}, which re-runs {@code canInteract}
 * from the level tick once every missing chunk is loaded. The obsidian / cobblestone / basalt still
 * forms; the server thread just never waits for terrain to appear. Interior blocks (both local
 * coordinates in 1..14) take the fast path with zero lookups. Off the server thread, or on the
 * client, the call is left exactly as it was.</p>
 *
 * <p>Also breaks the re-entrant {@code getChunk} nesting a DT forced generation could otherwise fall
 * into through this path — the same shape {@link SableBlockChangeGuardMixin} guards for the physics
 * hook.</p>
 *
 * <p>{@code remap = false}: {@code FluidInteractionRegistry} and {@code canInteract} are NeoForge's
 * own names. Bytecode-verified against {@code neoforge 21.1.228} ({@code forge-universal}):
 * {@code public static boolean canInteract(Level, BlockPos)} reads only {@code pos} itself (one
 * {@code Level.getFluidState} at offset 2); the neighbour reads live in the predicates
 * ({@code InteractionInformation.lambda$new$2}, {@code FluidInteractionRegistry.lambda$static$2}),
 * which is why the guard sits at the method head rather than around a call. {@code canInteract} is
 * public API and identical on the 21.1.230 builds players run. <b>Re-verify on any NeoForge bump.</b></p>
 */
@Mixin(value = FluidInteractionRegistry.class, remap = false)
public abstract class FluidInteractionNoLoadMixin {

    @Inject(method = "canInteract", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$deferWhenNeighbourUnloaded(final Level level, final BlockPos pos,
                                                                final CallbackInfoReturnable<Boolean> cir) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (FluidInteractionDeferralEvents.deferIfNeighbourUnloaded(serverLevel, pos)) {
            cir.setReturnValue(false);
        }
    }
}
