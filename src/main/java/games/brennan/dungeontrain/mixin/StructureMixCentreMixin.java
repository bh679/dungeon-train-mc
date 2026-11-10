package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.LostCityMixStarts;
import games.brennan.dungeontrain.worldgen.LostCityStructures;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Centres a Lost City building on the mix-zone chunk that picked it ({@link LostCityMixStarts#centre}). A jigsaw
 * start grows out from its chunk's corner, so uncentred most of the building would land in the void chunks
 * around it and be cut away. Priority 800 runs this {@code RETURN} ahead of Lost City Terrain Fit's seating
 * (900), which then seats the building where it now stands.
 */
@Mixin(value = Structure.class, priority = 800)
public abstract class StructureMixCentreMixin {

    @Inject(method = "generate", at = @At("RETURN"))
    private void dungeontrain$centreOnMixChunk(RegistryAccess registryAccess, ChunkGenerator chunkGenerator,
                                               BiomeSource biomeSource, RandomState randomState,
                                               StructureTemplateManager structureTemplateManager, long seed,
                                               ChunkPos chunkPos, int references, LevelHeightAccessor heightAccessor,
                                               Predicate<Holder<Biome>> validBiome,
                                               CallbackInfoReturnable<StructureStart> cir) {
        StructureStart start = cir.getReturnValue();
        if (start == null || !start.isValid()) return;
        if (!LostCityStructures.isLostCityStructure(
                registryAccess.registryOrThrow(Registries.STRUCTURE).getKey((Structure) (Object) this))) return;
        if (!LostCityMixStarts.picksLostCity(chunkPos.x, chunkPos.z)) return;
        LostCityMixStarts.centre(start.getPieces(), chunkPos);
        ((StructureStartAccessor) (Object) start).dungeontrain$setCachedBoundingBox(null);
    }
}
