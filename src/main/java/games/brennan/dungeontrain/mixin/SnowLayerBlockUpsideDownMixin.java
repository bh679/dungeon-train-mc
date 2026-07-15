package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps snow layers ({@link SnowLayerBlock}, i.e. {@code Blocks.SNOW}) from breaking inside the
 * upside-down band when nothing supports them from below.
 *
 * <p><b>Why.</b> The band mirrors overworld terrain vertically ({@code WorldUpsideDownEvents}): the
 * grassy surface that had open sky above it becomes a <em>ceiling</em>, so the top snow layer that
 * snowy biomes generate (the {@code FREEZE_TOP_LAYER} worldgen step) ends up hanging from that ceiling
 * with <em>air below it</em>. Vanilla {@code SnowLayerBlock.updateShape} replaces the layer with air the
 * moment {@code !state.canSurvive(...)} — and {@code canSurvive} demands a solid face directly below —
 * so any neighbour update reaching a mirrored snow layer silently pops it out of existence. Returning
 * the current {@code state} at HEAD keeps the layer exactly as mirrored, ignoring the missing support.</p>
 *
 * <p>Only snow <em>layers</em> are affected; full {@code SNOW_BLOCK} (used by {@code MountainPalette}) is
 * a solid cube that never needs support. This does not touch {@code randomTick} light-melting — brightly
 * lit mirrored snow can still melt, as in vanilla.</p>
 *
 * <p>Server-side ({@code updateShape}'s support-destruction runs server-side; the client follows the
 * authoritative block the server keeps). Overworld + band gated exactly like
 * {@link SpreadingSnowyDirtBlockUpsideDownMixin}, so the real Nether/End and every out-of-band overworld
 * column keep vanilla snow behaviour untouched. Backed by the memoised {@code WorldGenCycle.fromConfig()}
 * so the per-update band test is cheap.</p>
 */
@Mixin(SnowLayerBlock.class)
public abstract class SnowLayerBlockUpsideDownMixin {

    @Inject(method = "updateShape", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$keepSnowInUpsideDownBand(
            BlockState state, Direction facing, BlockState facingState, LevelAccessor level,
            BlockPos currentPos, BlockPos facingPos, CallbackInfoReturnable<BlockState> cir) {
        if (!(level instanceof ServerLevel server)) return;
        if (!server.dimension().equals(Level.OVERWORLD)) return;
        if (UpsideDownBand.isInBandOrEntryLead(server, currentPos.getX())) {
            cir.setReturnValue(state); // in-band or lead-in: keep the layer, ignore missing support
        }
    }
}
