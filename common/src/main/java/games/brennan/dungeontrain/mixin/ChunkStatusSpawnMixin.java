package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.worldgen.MirrorPlanCache;
import games.brennan.dungeontrain.worldgen.UpsideDownMirror;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Precomputes the upside-down band's vertical mirror on the worldgen worker thread, at the {@code SPAWN}
 * generation step, so the expensive snapshot + reflection runs off the server main thread. The finished
 * writes are stashed in {@link MirrorPlanCache} and applied later by {@code WorldUpsideDownEvents} at
 * {@code ChunkEvent.Load} (main thread) — see {@link UpsideDownMirror} for the compute/apply split.
 *
 * <p>{@code SPAWN} is the earliest generation step that is simultaneously on the worldgen worker, past
 * {@code LIGHT}, and terrain-final: its accumulated FEATURES dependency radius is 1, so every neighbour
 * has finished decoration and all cross-border tree/feature spillover into this chunk is present (an
 * earlier step would mirror before neighbours' trees land). We only <b>read</b> the {@code ProtoChunk}
 * here (read/read-safe against the light engine); the section writes stay on the main thread at load,
 * where the palette write is proven safe. This runs at {@code TAIL}, after {@code spawnOriginalMobs},
 * preserving the same mirror-after-spawn ordering the load-time handler always had.
 *
 * <p>Best-effort: any failure is logged and swallowed — the load-time handler recomputes the mirror
 * inline when the cache misses, so worldgen is never broken and terrain is always correct.
 */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusSpawnMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(method = "generateSpawn", at = @At("TAIL"))
    private static void dungeontrain$precomputeUpsideDownMirror(
            WorldGenContext worldGenContext, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        try {
            if (!DungeonTrainCommonConfig.isUpsideDownMirrorPrecompute()) return;
            ServerLevel level = worldGenContext.level();
            if (!level.dimension().equals(Level.OVERWORLD)) return; // band is overworld-only; excludes Sable sub-levels

            UpsideDownMirror.MirrorPlan plan = UpsideDownMirror.compute(level, chunk);
            if (plan != null) {
                MirrorPlanCache.put(chunk.getPos().toLong(), plan);
            }
        } catch (Throwable t) {
            // Never break worldgen — the load-time handler's inline fallback still produces correct terrain.
            LOGGER.error("[DungeonTrain] upside-down mirror precompute failed at {}; using load-time fallback",
                    chunk.getPos(), t);
        }
    }
}
