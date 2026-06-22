package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.worldgen.Disintegration;
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
 * Skips the overworld noise fill for disintegration-band chunks that the post-process erosion would
 * delete in full. Across the void holds, the End core, and the void↔End transitions every column is
 * held at {@code middleRamp == 1}, so the generated overworld terrain is 100% removed by
 * {@code WorldDisintegrationEvents} anyway — generating it (≈74k density samples + surface + carver
 * passes) and then erasing it block-by-block is pure waste. This injects at the head of
 * {@link NoiseBasedChunkGenerator#fillFromNoise} and, for a fully-eroded chunk, returns an empty
 * (all-air) chunk directly instead of running {@code doFill}.
 *
 * <p>Scope: only the overworld dimension (the band's home — same gate as
 * {@code WorldDisintegrationEvents}), only when the band is enabled and the chunk lies entirely in a
 * fully-eroded stretch ({@link Disintegration#isChunkFullyEroded}). Fade-zone / straddle chunks fall
 * through to vanilla so the erosion gradient is byte-identical to before. The floating track bed +
 * rails are still painted by {@code TrackBedFeature} (pillars are skipped there for these chunks);
 * the End islands + chorus are still stamped by {@code DisintegrationFeature} in the FEATURES stage.
 * Any failure falls through to vanilla generation — worldgen is never broken by this hook.</p>
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
            // The band lives only in the overworld dimension.
            if (!level.dimension().equals(Level.OVERWORLD)) return;

            long startX = DisintegrationBand.startX(level);
            if (startX == DisintegrationBand.OFF) return; // disabled / no train

            int chunkMinX = centerChunk.getPos().getMinBlockX();
            if (chunkMinX + 15 < startX) return; // entirely before the first band

            int phaseShift = DungeonTrainCommonConfig.getDisintegrationPhaseShiftBlocks();
            int fade = DungeonTrainCommonConfig.getDisintegrationFadeBlocks();
            int voidHold = DungeonTrainCommonConfig.getDisintegrationVoidHoldBlocks();
            int endHold = DungeonTrainCommonConfig.getDisintegrationEndHoldBlocks();
            int owHold = DungeonTrainCommonConfig.getDisintegrationOverworldHoldBlocks();
            if (!Disintegration.isChunkFullyEroded(chunkMinX, startX, phaseShift, fade, voidHold, endHold, owHold)) {
                return; // any fade/overworld column → keep real terrain, let vanilla run
            }

            // Fully eroded: hand back an empty chunk. Sections are already air (pre-allocated by the
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
