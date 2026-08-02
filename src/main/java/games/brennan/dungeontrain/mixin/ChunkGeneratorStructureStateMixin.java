package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.structure.ModStructureTypes;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the band's End-city structure set enabled on the overworld generator.
 *
 * <p>A generator only considers structure sets whose structures' biomes intersect its biome source's
 * {@code possibleBiomes()}. The band's End cities live on {@code end_highlands} / {@code end_midlands}
 * columns that DT forces onto overworld chunks at generation time, so the overworld's biome source never
 * lists them and the set would be dropped before a single placement is tried.</p>
 *
 * <p>Widening {@code possibleBiomes()} instead is not an option: it feeds
 * {@code FeatureSorter.buildFeaturesPerStep}, so adding biomes there renumbers every overworld decoration
 * feature and changes generation in existing worlds. This hook is the narrow alternative — it forces a
 * {@code true} for exactly one structure set and touches nothing else.</p>
 *
 * <p>The set stays enabled on every dimension's generator (the filter has no dimension to key off), which
 * costs only the spread check: {@code BandEndCityStructure.findGenerationPoint} declines anywhere but the
 * overworld's End band.</p>
 */
@Mixin(ChunkGeneratorStructureState.class)
public abstract class ChunkGeneratorStructureStateMixin {

    @Inject(method = "hasBiomesForStructureSet", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$keepBandStructureSet(StructureSet structureSet, BiomeSource biomeSource,
                                                          CallbackInfoReturnable<Boolean> cir) {
        for (StructureSet.StructureSelectionEntry entry : structureSet.structures()) {
            if (entry.structure().value().type() == ModStructureTypes.END_CITY.get()) {
                cir.setReturnValue(true);
                return;
            }
        }
    }
}
