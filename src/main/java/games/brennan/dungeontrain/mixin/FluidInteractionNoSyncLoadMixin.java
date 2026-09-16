package games.brennan.dungeontrain.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skips NeoForge's fluid-interaction check (lava + water → obsidian/cobblestone, etc.) when any
 * neighbour it would read sits in an <b>unloaded chunk</b>, instead of letting the server thread
 * synchronously load and generate that chunk. Issue #1450.
 *
 * <p><b>Why:</b> {@link FluidInteractionRegistry#canInteract} runs from
 * {@code LiquidBlock.neighborChanged}/{@code onPlace} and, for each of the five
 * {@code POSSIBLE_FLOW_DIRECTIONS}, tests the interaction predicate — for the built-in
 * lava↔water pair that is {@code level.getFluidState(relativePos)}, a blocking
 * {@code ServerChunkCache.getChunk(…, FULL, true)} when the neighbour is not loaded. A fluid
 * spreading across a chunk border into freshly generated terrain therefore parked the server on
 * DT's corridor/plot worldgen (player logs 2026-09-16: 5–35 s stalls with this exact stack).</p>
 *
 * <p>Returning {@code false} here is safe: the interaction is re-attempted naturally. When the
 * missing neighbour is promoted to ticking, its own {@code postProcessGeneration} ticks its border
 * fluid → {@code spreadTo} → {@code neighborChanged} on our block → {@code canInteract} runs again
 * with everything loaded. Pairs with {@link FlowingFluidNoSyncLoadMixin}.</p>
 *
 * <p>{@code remap = false}: NeoForge class, Mojang-mapped at runtime; {@code canInteract} is its
 * own name. Server-side callers only (fluid interaction is a server-thread block update).</p>
 */
@Mixin(value = FluidInteractionRegistry.class, remap = false)
public abstract class FluidInteractionNoSyncLoadMixin {

    @Inject(method = "canInteract", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$skipInteractionNextToUnloadedChunk(final Level level, final BlockPos pos,
                                                                        final CallbackInfoReturnable<Boolean> cir) {
        for (final Direction dir : LiquidBlock.POSSIBLE_FLOW_DIRECTIONS) {
            if (!level.hasChunkAt(pos.relative(dir.getOpposite()))) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
