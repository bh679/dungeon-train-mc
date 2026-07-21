package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.ChuncksBand;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.worldgen.OceanBand;
import net.minecraft.Util;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;

/**
 * Skips the overworld noise fill for chunks that are meant to be void, across the two void-producing
 * bands:
 *
 * <ul>
 *   <li><b>Disintegration/End band</b> — the void holds, End core, and void↔End transitions all hold
 *       {@code middleRamp == 1}, so the generated overworld terrain is 100% removed by
 *       {@code WorldDisintegrationEvents} anyway. Gated on {@link DisintegrationBand#isChunkFullyEroded}.</li>
 *   <li><b>Chuncks band</b> — mostly void, sprinkled with occasional real chunks; the void chunks are
 *       classified by {@link ChuncksBand#isVoidChunk}.</li>
 * </ul>
 *
 * <p>Generating a chunk's terrain (≈74k density samples + surface + carver passes) only to erase it is
 * pure waste, so this injects at the head of {@link NoiseBasedChunkGenerator#fillFromNoise} and returns
 * an empty (all-air) chunk directly instead of running {@code doFill}. The two bands are evaluated
 * independently, so either works with the other disabled.</p>
 *
 * <p>The same head hook also builds the <b>ocean band</b>: a chunk whose full X-span is in the band
 * ({@link OceanBand#isInBand}) skips the noise fill and gets a raised open sea written directly into its
 * pre-allocated sections (flat seabed + water to {@code bedY-1}, corridor Z-span left dry) — see
 * {@link #dungeontrain$fillOceanSea}. This runs on the worldgen worker, so raising the sea costs no
 * main-thread time and the water is born settled.</p>
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

    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$skipFullyErodedBandFill(
            Blender blender, RandomState randomState, StructureManager structureManager,
            ChunkAccess centerChunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        try {
            // During fresh generation the chunk's raw levelHeightAccessor IS the owning ServerLevel.
            LevelHeightAccessor lha = ((ChunkAccessAccessor) centerChunk).dungeontrain$getLevelHeightAccessor();
            if (!(lha instanceof ServerLevel level)) return;
            // Both bands live only in the overworld dimension.
            if (!level.dimension().equals(Level.OVERWORLD)) return;

            int chunkMinX = centerChunk.getPos().getMinBlockX();
            int chunkMinZ = centerChunk.getPos().getMinBlockZ();

            // Evaluate the two void-producing bands independently — chuncks must void even when the
            // disintegration/End band is disabled, and vice-versa.
            boolean voidChunk = false;

            long disStartX = DisintegrationBand.startX(level);
            if (disStartX != DisintegrationBand.OFF && chunkMinX + 15 >= disStartX
                    && DisintegrationBand.isChunkFullyEroded(level, chunkMinX)) {
                voidChunk = true; // End void/core: post-erosion would delete 100% of the terrain anyway
            }
            if (!voidChunk && ChuncksBand.isVoidChunk(level, chunkMinX, chunkMinZ)) {
                voidChunk = true; // chuncks band: this chunk is one of the mostly-void gaps
            }
            if (!voidChunk) {
                // Ocean band: a chunk whose full X-span is in the band becomes a raised open sea, built
                // directly here on the worldgen worker (skipping the ≈74k-sample noise fill — cheaper than
                // vanilla terrain) instead of a heavy main-thread post-process. Partial (band-edge) chunks
                // fall through to vanilla → a thin natural coastline the fluid veto still contains.
                if (OceanBand.startX(level) != OceanBand.OFF
                        && OceanBand.isInBand(level, chunkMinX) && OceanBand.isInBand(level, chunkMinX + 15)) {
                    final int seaMinZ = chunkMinZ;
                    cir.setReturnValue(CompletableFuture.supplyAsync(() -> {
                        dungeontrain$fillOceanSea(level, centerChunk, seaMinZ);
                        return centerChunk;
                    }, Util.backgroundExecutor()));
                }
                return; // ocean handled above, or real terrain → let vanilla run
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

    private static final BlockState DUNGEONTRAIN$WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState DUNGEONTRAIN$SAND = Blocks.SAND.defaultBlockState();
    /** Depth (blocks) of the raised sea below the waterline; a flat sealed seabed sits this far down. */
    private static final int DUNGEONTRAIN$OCEAN_DEPTH = 16;
    /** Thickness of the solid seabed seal placed under the water so it can never leak downward. */
    private static final int DUNGEONTRAIN$SEABED_SEAL = 3;

    /**
     * Build a raised open sea into the pre-allocated (all-air) sections of a fully-in-band ocean chunk,
     * on the worldgen worker: a flat solid seabed seal, water up to {@code bedY-1}, air above. The corridor
     * Z-span (tunnel wall to wall) is left air so the floating track / dry channel survives (bed + rails are
     * painted later by {@code TrackBedFeature}). Raw {@link LevelChunkSection#setBlockState} with no relight
     * — the chunk is not ticking yet, so the water is born settled (no fluid cascade). Heightmaps are then
     * primed from the placed blocks so surface/decoration read correct heights.
     */
    private static void dungeontrain$fillOceanSea(ServerLevel level, ChunkAccess chunk, int chunkMinZ) {
        long genT0 = GenProfiler.t0();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        TrackGeometry g = TrackGeometry.from(data.dims(), data.getTrainY());
        int surface = g.bedY();                                     // waterline (top water at surface-1)
        int seabedTop = surface - DUNGEONTRAIN$OCEAN_DEPTH;         // bottom water block
        TunnelGeometry tg = TunnelGeometry.from(g);
        int corridorMinZ = tg.wallMinZ();
        int corridorMaxZ = tg.wallMaxZ();

        for (int sIdx = 0; sIdx < chunk.getSectionsCount(); sIdx++) {
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
            if (baseY > surface - 1) continue;                      // at/above the waterline → already air
            if (baseY + 15 < seabedTop - DUNGEONTRAIN$SEABED_SEAL) continue; // below the seal → leave air
            LevelChunkSection section = chunk.getSection(sIdx);
            for (int ly = 0; ly < 16; ly++) {
                BlockState s = dungeontrain$seaState(baseY + ly, surface, seabedTop);
                if (s == null) continue;
                for (int dz = 0; dz < 16; dz++) {
                    int worldZ = chunkMinZ + dz;
                    if (worldZ >= corridorMinZ && worldZ <= corridorMaxZ) continue; // dry corridor channel
                    for (int dx = 0; dx < 16; dx++) {
                        section.setBlockState(dx, ly, dz, s, false);
                    }
                }
            }
        }
        Heightmap.primeHeightmaps(chunk,
                EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
        GenProfiler.add(GenProfiler.Bucket.OCEAN_FILL, genT0);
    }

    /** The block a sea column takes at world-Y, or {@code null} to leave the section's air. */
    private static BlockState dungeontrain$seaState(int y, int surface, int seabedTop) {
        if (y >= surface) return null;                              // air above the waterline
        if (y >= seabedTop) return DUNGEONTRAIN$WATER;              // raised sea
        if (y >= seabedTop - DUNGEONTRAIN$SEABED_SEAL) return DUNGEONTRAIN$SAND; // sealed flat seabed
        return null;                                               // below the seal → leave air
    }
}
