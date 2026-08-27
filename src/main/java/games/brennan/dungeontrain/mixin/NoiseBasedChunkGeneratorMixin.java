package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.ChuncksBand;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
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
            if (dungeontrain$isVoidChunk(level, chunk)) {
                ci.cancel();
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
        return ChuncksBand.isVoidChunk(level, chunkMinX, chunkMinZ); // chuncks band: a mostly-void gap
    }
}
