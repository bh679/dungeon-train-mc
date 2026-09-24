package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.ChuncksBand;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.SpheresBand;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.worldgen.StacksBand;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
import games.brennan.dungeontrain.worldgen.legacy.LegacyChunkWriter;
import games.brennan.dungeontrain.worldgen.legacy.preset.PresetTerrain;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Skips the overworld noise fill AND the surface pass for chunks that are meant to be void, across
 * the two void-producing bands:
 *
 * <ul>
 *   <li><b>Disintegration/End band</b> — the void holds, End core, and void↔End transitions all hold
 *       {@code middleRamp == 1}, so the generated overworld terrain is 100% removed by
 *       {@code WorldDisintegrationEvents} anyway. Gated on {@link DisintegrationBand#isChunkFullyEroded}.</li>
 *   <li><b>Chuncks band</b> — mostly void, sprinkled with occasional real chunks; the void chunks are
 *       classified by {@link ChuncksBand#isVoidChunk}.</li>
 *   <li><b>Spheres band</b> — open void between floating spheres; chunks no sphere touches are
 *       classified by {@link SpheresBand#isVoidChunk}.</li>
 * </ul>
 *
 * <p>Generating a chunk's terrain (≈74k density samples + surface + carver passes) only to erase it is
 * pure waste, so this injects at the head of {@link NoiseBasedChunkGenerator#fillFromNoise} and returns
 * an empty (all-air) chunk directly instead of running {@code doFill}. The two bands are evaluated
 * independently, so either works with the other disabled.</p>
 *
 * <p><b>The surface pass is skipped for the same chunks</b>, and that is a correctness fix, not just a
 * saving: DT keeps the <em>original</em> overworld biome below sea level inside the band (see
 * {@code BandBiomeDecision}), and over a frozen ocean the vanilla surface rules stamp iceberg pillars
 * ({@code packed_ice}/{@code blue_ice} with {@code snow_block} caps) <em>above</em> the preliminary
 * surface — i.e. straight into the void of an otherwise-empty chunk. Being a surface rule rather than a
 * feature, it slips past {@code ChunkGeneratorDecorationMixin}'s decoration skip, and the corridor
 * preserve window in {@code WorldDisintegrationEvents} (which keeps the track/tunnel footprint intact in
 * the fully-eroded core) then leaves those pillars standing in exactly the Z-span the train rides
 * through — ice towers in the middle of the End band.</p>
 *
 * <p><b>Legacy bands</b> reuse the same seam the other way round: a chunk owned by an old generator
 * ({@link LegacyBands#kindOfChunk}) gets that generator's terrain written here instead of vanilla's
 * noise, and its vanilla surface and carver passes are skipped — the old generator already laid its own
 * surface and carved its own caves.
 * A <b>modern-preset</b> band (Large Biomes, Amplified — {@link LegacyBandKind#isPreset}) instead hands the
 * chunk to its own vanilla generator ({@link PresetTerrain}): first at {@code createBiomes}, which is where the
 * chunk's {@code NoiseChunk} is created (so the NOISE, SURFACE and CARVER steps all read the preset router),
 * then at {@code fillFromNoise}; vanilla's surface, carvers and decoration then run unchanged.</p>
 *
 * <p>Scope: only the overworld dimension (both bands' home). Fade-zone / straddle / kept chunks fall
 * through to vanilla so their terrain is byte-identical to before. The floating track bed + rails are
 * still painted by {@code TrackBedFeature} (pillars are skipped over void by a probe sentinel); the End
 * islands + chorus are still stamped by {@code DisintegrationFeature} in the FEATURES stage. Any failure
 * falls through to vanilla generation — worldgen is never broken by this hook.</p>
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Preset chunk: bake biomes through the preset generator so the chunk's {@code NoiseChunk} is created
     * with the preset router and the biomes come from its climate sampler (wide biomes for Large Biomes).
     */
    @Inject(method = "createBiomes", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$presetBiomes(RandomState randomState, Blender blender, StructureManager structureManager,
                                           ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        try {
            LevelHeightAccessor lha = ((ChunkAccessAccessor) chunk).dungeontrain$getLevelHeightAccessor();
            if (!(lha instanceof ServerLevel level)) return;
            PresetTerrain.Preset preset = dungeontrain$preset(level, chunk);
            if (preset == null) return;
            cir.setReturnValue(preset.generator().createBiomes(preset.randomState(), blender, structureManager, chunk));
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] preset biome bake failed at {}; using vanilla gen", chunk.getPos(), t);
        }
    }

    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$skipFullyErodedBandFill(
            Blender blender, RandomState randomState, StructureManager structureManager,
            ChunkAccess centerChunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        try {
            // During fresh generation the chunk's raw levelHeightAccessor IS the owning ServerLevel.
            LevelHeightAccessor lha = ((ChunkAccessAccessor) centerChunk).dungeontrain$getLevelHeightAccessor();
            if (!(lha instanceof ServerLevel level)) return;
            PresetTerrain.Preset preset = dungeontrain$preset(level, centerChunk);
            if (preset != null) {
                // Vanilla's own fill, on the preset generator: same executor hand-off as vanilla's.
                cir.setReturnValue(preset.generator().fillFromNoise(blender, preset.randomState(), structureManager, centerChunk));
                return;
            }
            if (dungeontrain$fillLegacy(level, centerChunk, cir)) return;
            if (!dungeontrain$isVoidChunk(level, centerChunk)) {
                return; // any real-terrain column → keep it, let vanilla run
            }

            // Void chunk: hand back an empty chunk. Sections are already air (pre-allocated by the
            // ProtoChunk ctor); we only prime the two worldgen heightmaps that doFill would have
            // created (empty, anchored at minY) so the downstream surface/heightmap reads are
            // satisfied. Run on the wgen worker — mirroring vanilla's own fillFromNoise hand-off — so
            // the chained generation steps keep their thread affinity.
            cir.setReturnValue(CompletableFuture.supplyAsync(() -> {
                centerChunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
                centerChunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
                return centerChunk;
            }, Util.backgroundExecutor()));
        } catch (Throwable t) {
            // Never break worldgen — on any error, fall through to vanilla generation.
            LOGGER.error("[DungeonTrain] void-band fill short-circuit failed at {}; using vanilla gen",
                    centerChunk.getPos(), t);
        }
    }

    /**
     * Skip the surface pass on the same void chunks. The chunk is all air here, so the surface rules can
     * only <em>add</em> blocks to the void — and over a frozen ocean they add iceberg pillars that then
     * survive inside the corridor preserve window (see the class javadoc).
     */
    @Inject(method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
            at = @At("HEAD"), cancellable = true)
    private void dungeontrain$skipFullyErodedBandSurface(
            WorldGenRegion region, StructureManager structureManager, RandomState randomState,
            ChunkAccess chunk, CallbackInfo ci) {
        try {
            ServerLevel level = region.getLevel();
            if (dungeontrain$isVoidChunk(level, chunk) || dungeontrain$oldGeneratorKind(level, chunk) != null) {
                ci.cancel(); // void: nothing to surface; legacy: the old generator laid its own surface
            }
        } catch (Throwable t) {
            // Never break worldgen — on any error, fall through to the vanilla surface pass.
            LOGGER.error("[DungeonTrain] void-band surface short-circuit failed at {}; using vanilla gen",
                    chunk.getPos(), t);
        }
    }

    /**
     * True iff this chunk generates as pure void in either band — the shared gate behind both skips, so
     * the fill and the surface pass can never disagree about a chunk. Evaluates the two bands
     * independently: chuncks must void even when the disintegration/End band is disabled, and vice-versa.
     */
    @Unique
    private static boolean dungeontrain$isVoidChunk(ServerLevel level, ChunkAccess chunk) {
        // Both bands live only in the overworld dimension.
        if (!level.dimension().equals(Level.OVERWORLD)) return false;

        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();

        long disStartX = DisintegrationBand.startX(level);
        if (disStartX != DisintegrationBand.OFF && chunkMinX + 15 >= disStartX
                && DisintegrationBand.isChunkFullyEroded(level, chunkMinX)) {
            return true; // End void/core: post-erosion would delete 100% of the terrain anyway
        }
        if (ChuncksBand.isVoidChunk(level, chunkMinX, chunkMinZ)) return true; // chuncks band: a mostly-void gap
        if (SpheresBand.isVoidChunk(level, chunkMinX, chunkMinZ)) return true;  // spheres band: open void between spheres
        // Stacks band: VOID chunks are empty; STACK chunks are also generated empty, then StacksFeature
        // stamps the tower into the air at decoration time.
        return StacksBand.isVoidOrStackChunk(level, chunkMinX, chunkMinZ);
    }

    /**
     * Legacy band chunk: generate the old terrain synchronously (pure — on failure vanilla runs instead)
     * and hand the write + chunk back on the worldgen executor, mirroring the void path's hand-off.
     * Returns true when it took the chunk.
     */
    @Unique
    private boolean dungeontrain$fillLegacy(ServerLevel level, ChunkAccess chunk,
                                            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        LegacyBandKind kind = dungeontrain$oldGeneratorKind(level, chunk);
        if (kind == null) return false;
        long seed = DungeonTrainWorldData.get(level).getGenerationSeed();
        int floorY = ((NoiseBasedChunkGenerator) (Object) this).getMinY();
        int yOffset = LegacyBands.yOffset(kind, level);
        cir.setReturnValue(CompletableFuture.supplyAsync(() -> {
            long genT0 = GenProfiler.t0();
            try {
                LegacyChunkWriter.fill(kind, seed, chunk, floorY, yOffset);
            } finally {
                GenProfiler.add(GenProfiler.Bucket.LEGACY, genT0);
            }
            return chunk;
        }, Util.backgroundExecutor()));
        return true;
    }

    /** Skip vanilla caves and ravines in legacy chunks — the old generator carved its own. */
    @Inject(method = "applyCarvers", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$skipLegacyCarvers(WorldGenRegion region, long seed, RandomState random,
                                                BiomeManager biomeManager, StructureManager structureManager,
                                                ChunkAccess chunk, GenerationStep.Carving step, CallbackInfo ci) {
        try {
            if (dungeontrain$oldGeneratorKind(region.getLevel(), chunk) != null) ci.cancel();
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] legacy carver skip failed at {}; running vanilla carvers", chunk.getPos(), t);
        }
    }

    /** The legacy kind whose OLD generator owns this chunk — null for modern chunks and for preset chunks. */
    @Unique
    private static LegacyBandKind dungeontrain$oldGeneratorKind(ServerLevel level, ChunkAccess chunk) {
        LegacyBandKind kind = LegacyBands.kindOfChunk(level, chunk.getPos().x, chunk.getPos().z);
        return kind == null || kind.isPreset() ? null : kind;
    }

    /** The published preset generator for this chunk, or null (modern chunk, old-generator chunk, or no presets). */
    @Unique
    private static PresetTerrain.Preset dungeontrain$preset(ServerLevel level, ChunkAccess chunk) {
        LegacyBandKind kind = LegacyBands.kindOfChunk(level, chunk.getPos().x, chunk.getPos().z);
        return kind == null || !kind.isPreset() ? null : PresetTerrain.of(kind);
    }
}
