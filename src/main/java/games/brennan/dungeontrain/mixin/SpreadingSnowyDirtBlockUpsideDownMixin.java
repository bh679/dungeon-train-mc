package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SpreadingSnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freezes grass and mycelium ({@link SpreadingSnowyDirtBlock} and its subclasses) inside the
 * upside-down band so they don't decay to dirt.
 *
 * <p><b>Why.</b> The band mirrors overworld terrain vertically ({@code WorldUpsideDownEvents}): the
 * grassy surface that had open sky above it becomes a <em>ceiling</em> with solid terrain directly
 * above each block. Vanilla {@code SpreadingSnowyDirtBlock.randomTick} reads that as "grass has been
 * covered" ({@code !canBeGrass}) and converts the block to {@link net.minecraft.world.level.block.Blocks#DIRT},
 * so the flipped landscape rots into bare dirt over the first in-band minutes. Cancelling the random
 * tick at HEAD leaves the block exactly as generated — no decay, and no spreading either (irrelevant on
 * static mirrored terrain, and desirable so grass doesn't creep onto the mirrored dirt below).</p>
 *
 * <p>Server-side (random ticks run on {@link ServerLevel}); overworld + band gated exactly like
 * {@code BandMobSpawnEvents}, so the real Nether/End and every out-of-band overworld column keep vanilla
 * grass behaviour untouched. Backed by the memoised {@code WorldGenCycle.fromConfig()} so the per-tick
 * band test is cheap.</p>
 */
@Mixin(SpreadingSnowyDirtBlock.class)
public abstract class SpreadingSnowyDirtBlockUpsideDownMixin {

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$freezeGrassInUpsideDownBand(
            BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (UpsideDownBand.isInBand(level, pos.getX())) {
            ci.cancel(); // in-band: keep grass/mycelium as-is (no decay to dirt, no spread)
        }
    }
}
