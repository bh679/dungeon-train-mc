package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.ChuncksBand;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
}
