package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.LegacyUnderground;
import games.brennan.dungeontrain.worldgen.LostCityFootprint;
import games.brennan.dungeontrain.worldgen.LostCityStructures;
import games.brennan.dungeontrain.worldgen.LostCityTemplateDemand;
import games.brennan.dungeontrain.worldgen.UpsideDownSpawnerStructures;
import games.brennan.dungeontrain.worldgen.WorldFloor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
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
 * Drops a structure whose every piece would sit below the bedrock layer.
 *
 * <p>{@link WorldGenRegionFloorMixin} already refuses the blocks, but on its own that leaves the
 * <i>start</i> registered: {@code /locate} would point at empty basement, the chunk would carry
 * structure references forever, and structure-bounded mob spawning would keep firing over nothing.
 * Declining the start is also the cheaper answer — none of the pieces get placed at all.</p>
 *
 * <p>Only a structure entirely under the floor is dropped. One that straddles it keeps its start and
 * is clipped write-by-write, so a tall structure rooted just under bedrock still shows the part of
 * itself that reaches real terrain.</p>
 *
 * <p>In a world without a basement {@code bedrockY} is the build floor, nothing generates below it,
 * and this never fires.</p>
 *
 * <p>Big Lost City's era veto ({@link LostCityStructures#allowedAt}) — and the veto of Lost City Terrain Fit's
 * all-biome copies — runs at {@code HEAD}, not with the
 * others at {@code RETURN}: it needs nothing from the built start, and a jigsaw start loads (and
 * datafixes — the mod ships 1.20.1 NBT) its templates and assembles every piece before returning. Vanilla's
 * structure-set loop also retries the set's other entries after a rejection, so a veto at {@code RETURN}
 * built and discarded up to the whole set's worth of cities in every refused chunk — most of new-world
 * spawn generation. The verdict needs nothing from the built start, so moving it changes no output. Its
 * chunk part doesn't depend on the structure either, so the retries answer from a per-chunk memo
 * ({@link games.brennan.dungeontrain.worldgen.LostCityChunkVeto}); only the WWOO stretch's building pick
 * reads the id.</p>
 */
@Mixin(Structure.class)
public abstract class StructureBasementMixin {

    @Inject(method = "generate", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$vetoLostCityEarly(RegistryAccess registryAccess, ChunkGenerator chunkGenerator,
                                                BiomeSource biomeSource, RandomState randomState,
                                                StructureTemplateManager structureTemplateManager, long seed,
                                                ChunkPos chunkPos, int references, LevelHeightAccessor heightAccessor,
                                                Predicate<Holder<Biome>> validBiome,
                                                CallbackInfoReturnable<StructureStart> cir) {
        ResourceLocation id = registryAccess.registryOrThrow(Registries.STRUCTURE).getKey((Structure) (Object) this);
        if (LostCityStructures.isTerrainFitCopy(id)) {
            // Lost City Terrain Fit's all-biome copies of the big buildings: DT places its own
            // (dungeontrain:lost_city/*) under the era rules, so the sibling's are never started here.
            cir.setReturnValue(StructureStart.INVALID_START);
            return;
        }
        if (!LostCityStructures.isLostCityStructure(id)) return;
        if (LostCityStructures.isNewBuildingSlot(id)
                && !games.brennan.dungeontrain.building.BuildingWorldgen.hasNewBuildings()) {
            // No new buildings in this world: the slot has nothing to place, so the set tries its others.
            cir.setReturnValue(StructureStart.INVALID_START);
            return;
        }
        // Big Lost City's cities belong to the Lost City era alone (LostCityStructures) — anywhere
        // else, including a start we can't place in a level (a sampler or foreign generator), is dropped
        // before any template is loaded.
        ServerLevel level = dungeontrain$levelOf(heightAccessor, chunkGenerator);
        if (level == null || !LostCityStructures.allowedAt(level, chunkPos.x, chunkPos.z, id)) {
            cir.setReturnValue(StructureStart.INVALID_START);
            return;
        }
        // The start goes ahead and is about to ask for its templates, on this thread.
        LostCityTemplateDemand.requested(id, chunkPos.x);
    }

    @Inject(method = "generate", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$dropBasementStarts(RegistryAccess registryAccess, ChunkGenerator chunkGenerator,
                                                 BiomeSource biomeSource, RandomState randomState,
                                                 StructureTemplateManager structureTemplateManager, long seed,
                                                 ChunkPos chunkPos, int references, LevelHeightAccessor heightAccessor,
                                                 Predicate<Holder<Biome>> validBiome,
                                                 CallbackInfoReturnable<StructureStart> cir) {
        StructureStart start = cir.getReturnValue();
        if (start == null || !start.isValid()) {
            return;
        }
        int floorY = WorldFloor.bedrockY(heightAccessor, chunkGenerator);
        ResourceLocation id = registryAccess.registryOrThrow(Registries.STRUCTURE).getKey((Structure) (Object) this);
        ServerLevel level = dungeontrain$levelOf(heightAccessor, chunkGenerator);
        // A Lost City start the HEAD veto allowed is already seated on its footprint's floor here: Lost City
        // Terrain Fit's StructureSeatMixin (priority 900) runs its RETURN inject ahead of this one.
        if (LostCityStructures.isLostCityStructure(id)) {
            // A stretched building's box must match what its processors will place (LostCityFootprint).
            LostCityFootprint.resize(start, structureTemplateManager);
        }
        if (level != null) {
            // Legacy bands and the sunk zone never get the underground set (LegacyUnderground).
            if (LegacyUnderground.appliesTo(level, chunkPos.x, chunkPos.z)
                    && LegacyUnderground.excludesStructure(id)) {
                cir.setReturnValue(StructureStart.INVALID_START);
                return;
            }
            // The upside-down band never gets spawner structures (UpsideDownSpawnerStructures).
            if (UpsideDownSpawnerStructures.appliesTo(level, chunkPos.x, chunkPos.z)
                    && UpsideDownSpawnerStructures.excludesStructure(id)) {
                cir.setReturnValue(StructureStart.INVALID_START);
                return;
            }
            // The sunk zone's terrain reaches into the basement, so a deep start there is real.
            floorY = Math.min(floorY, WorldFloor.terrainFloorY(level, chunkPos.x, chunkPos.z));
        }
        if (WorldFloor.entirelyBelowFloor(start.getBoundingBox().maxY(), floorY)) {
            cir.setReturnValue(StructureStart.INVALID_START);
        }
    }

    /** The level {@code heightAccessor} belongs to when {@code chunkGenerator} is its own; else {@code null}. */
    private static ServerLevel dungeontrain$levelOf(LevelHeightAccessor heightAccessor, ChunkGenerator chunkGenerator) {
        return heightAccessor instanceof ChunkAccess chunk
                && ((ChunkAccessAccessor) chunk).dungeontrain$getLevelHeightAccessor() instanceof ServerLevel l
                && l.getChunkSource().getGenerator() == chunkGenerator ? l : null;
    }
}
