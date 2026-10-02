package games.brennan.dungeontrain.mixin.wover;

import games.brennan.dungeontrain.worldgen.OfflineChunkSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import org.betterx.wover.generator.api.biomesource.WoverBiomePicker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers WorldWeaver's {@code WoverBiomePicker.getBiomeAt} from the sample while an offline sample is
 * being decorated.
 *
 * <p>BetterEnd's {@code SpireFeature} (via {@code EndBiome.findUnderMaterial}) asks it for the biome under
 * a spire, and it answers with {@code level.getChunkSource().getChunk(x, z, status, true)} — on a
 * {@code WorldGenRegion} that is the <em>live</em> End's chunk cache, so every spire in a sample loaded and
 * generated a real End chunk 16 000+ blocks out and joined the future. On a dedicated sampler thread that
 * merely cost time (and left stray End chunks on disk); inside a worldgen worker, where the End-band terrain
 * is generated since {@code endBandTerrain = worldgen}, the join waits on the very pool it occupies —
 * found as a 10-minute server stall on 2026-10-01. The sample already holds exactly the biomes the live
 * source would answer ({@link OfflineChunkSampler#biomeInSample}).</p>
 */
@Mixin(value = WoverBiomePicker.class, remap = false)
public abstract class WoverBiomePickerSampleMixin {

    @Inject(method = "getBiomeAt", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$answerFromSample(WorldGenLevel level, BlockPos pos,
                                                      CallbackInfoReturnable<Holder<Biome>> cir) {
        if (!OfflineChunkSampler.isSampling()) return;
        Holder<Biome> biome = OfflineChunkSampler.biomeInSample(level, pos);
        if (biome != null) cir.setReturnValue(biome);
    }
}
