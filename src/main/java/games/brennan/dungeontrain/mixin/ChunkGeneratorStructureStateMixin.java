package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.structure.ModStructureTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import games.brennan.dungeontrain.worldgen.structure.BandNetherStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the band's own structure sets enabled on the overworld generator — the End band's End cities, and
 * the Nether band's fortresses, bastions, fossils and ruined portals.
 *
 * <p>A generator only considers structure sets whose structures' biomes intersect its biome source's
 * {@code possibleBiomes()}. The band's structures live on End or Nether biome columns that DT forces onto
 * overworld chunks at generation time, so the overworld's biome source never lists them and the sets would
 * be dropped before a single placement is tried.</p>
 *
 * <p>Widening {@code possibleBiomes()} instead is not an option: it feeds
 * {@code FeatureSorter.buildFeaturesPerStep}, so adding biomes there renumbers every overworld decoration
 * feature and changes generation in existing worlds. This hook is the narrow alternative — it forces a
 * {@code true} for exactly one structure set and touches nothing else.</p>
 *
 * <p>Enabled on the <b>overworld's</b> generator only. That gate is load-bearing now that the Nether band's
 * sets point at the vanilla Nether structures: those structures' biomes <em>are</em> listed by the real
 * Nether's biome source, so without it DT's sets would pass the filter there too and quietly add a second
 * helping of fortresses to the Nether at DT's spacing. The filter has no dimension to key off, so the test
 * is the biome source itself — a generator that lists any Nether or End biome is not the overworld. If that
 * cannot be determined the sets are simply dropped, so the failure direction is "no band structures" rather
 * than "altered Nether".</p>
 */
@Mixin(ChunkGeneratorStructureState.class)
public abstract class ChunkGeneratorStructureStateMixin {

    @Inject(method = "hasBiomesForStructureSet", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$keepBandStructureSet(StructureSet structureSet, BiomeSource biomeSource,
                                                          CallbackInfoReturnable<Boolean> cir) {
        if (!dungeontrain$isBandSet(structureSet)) return;
        cir.setReturnValue(dungeontrain$isOverworldBiomeSource(biomeSource));
    }

    /**
     * A DT-owned structure set: one whose entries are either a DT structure type (the End city) or one of
     * the vanilla Nether structures the band re-sites. Vanilla's own {@code nether_complexes} and
     * {@code nether_fossils} sets contain the same structures, but they are never seen here on the
     * overworld generator — the overworld's biome source doesn't list Nether biomes, so vanilla's filter
     * has already dropped them, and in the Nether this hook declines and leaves them exactly as they were.
     */
    @Unique
    private static boolean dungeontrain$isBandSet(StructureSet structureSet) {
        for (StructureSet.StructureSelectionEntry entry : structureSet.structures()) {
            Structure structure = entry.structure().value();
            if (ModStructureTypes.isBandStructure(structure.type())
                    || BandNetherStructures.isBandEligible(structure)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for the overworld's biome source, identified by what it cannot contain: the overworld lists no
     * Nether or End biome, while both of those dimensions' sources do. Any error answers {@code false},
     * which costs the band its structures and touches nothing else.
     */
    @Unique
    private static boolean dungeontrain$isOverworldBiomeSource(BiomeSource biomeSource) {
        try {
            return biomeSource.possibleBiomes().stream()
                    .noneMatch(biome -> biome.is(BiomeTags.IS_NETHER) || biome.is(BiomeTags.IS_END));
        } catch (Throwable t) {
            return false;
        }
    }
}
