package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.feature.ModFeatures;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the vanilla decoration + structure pass on disintegration-band chunks that the post-process
 * erosion deletes in full. {@link NoiseBasedChunkGeneratorMixin} already short-circuits the noise
 * terrain for these fully-eroded chunks; this drops the remaining generate-then-erase waste — every
 * vanilla biome feature (trees/ores/lakes/…) and structure piece that would be placed (some at
 * {@code minY} on the now-empty terrain) and then wiped by {@code WorldDisintegrationEvents}.
 *
 * <p>The DT features {@code track_bed} + {@code disintegration} run in the <b>same</b> monolithic
 * {@link ChunkGenerator#applyBiomeDecoration} (both at {@code top_layer_modification}), so there is
 * no datapack seam to keep only them — instead we redirect the per-feature / per-structure placement
 * calls and, in a fully-eroded overworld chunk, run only the two DT features and no structures. The
 * floating track + End islands + chorus survive; nothing else generates.</p>
 *
 * <p>Determinism is preserved: {@code applyBiomeDecoration} reseeds the {@code WorldgenRandom}
 * before every feature / structure placement, so skipping a call does not desync later ones. Any
 * resolution error falls back to running vanilla decoration — worldgen is never broken.</p>
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorDecorationMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Per-decoration-call flag: true while decorating a fully-eroded band chunk. Set at the head of
     * {@code applyBiomeDecoration} and read by the placement redirects below. {@code applyBiomeDecoration}
     * runs to completion synchronously on a single worldgen worker thread, and the head re-sets the
     * value at the start of every chunk, so a {@link ThreadLocal} is both thread-safe and always
     * fresh (no clearing needed — the next chunk's head overwrites it).
     */
    @Unique
    private static final ThreadLocal<Boolean> dungeontrain$skipDecoration = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
    private void dungeontrain$computeSkip(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager, CallbackInfo ci) {
        dungeontrain$skipDecoration.set(dungeontrain$isFullyErodedBandChunk(level, chunk));
    }

    @Redirect(
        method = "applyBiomeDecoration",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;placeWithBiomeCheck(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean dungeontrain$filterFeature(PlacedFeature feature, WorldGenLevel level, ChunkGenerator generator,
                                               RandomSource random, BlockPos origin) {
        if (dungeontrain$skipDecoration.get() && !dungeontrain$isDtFeature(feature)) {
            return false; // fully-eroded core: skip the vanilla feature (it would be erased anyway)
        }
        return feature.placeWithBiomeCheck(level, generator, random, origin);
    }

    /**
     * Skip ALL structure piece placement in the eroded core. The {@code start.placeInChunk(...)}
     * call sits inside a {@code forEach} lambda (not bindable from this method by name), so instead
     * we redirect the {@code shouldGenerateStructures()} guard the structure block is gated on —
     * returning false in a fully-eroded chunk skips the whole structure loop, every step.
     */
    @Redirect(
        method = "applyBiomeDecoration",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/StructureManager;shouldGenerateStructures()Z"))
    private boolean dungeontrain$skipStructures(StructureManager structureManager) {
        if (dungeontrain$skipDecoration.get()) {
            return false; // fully-eroded core: no structures (pointless in the void / floating End)
        }
        return structureManager.shouldGenerateStructures();
    }

    /** True iff this is an OVERWORLD chunk fully inside the band's eroded core (mirrors the fill mixin's gate). */
    @Unique
    private static boolean dungeontrain$isFullyErodedBandChunk(WorldGenLevel level, ChunkAccess chunk) {
        try {
            ServerLevel serverLevel = level.getLevel();
            if (!serverLevel.dimension().equals(Level.OVERWORLD)) return false;
            long startX = DisintegrationBand.startX(serverLevel);
            if (startX == DisintegrationBand.OFF) return false;
            int chunkMinX = chunk.getPos().getMinBlockX();
            if (chunkMinX + 15 < startX) return false;
            return DisintegrationBand.isChunkFullyEroded(serverLevel, chunkMinX);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] decoration-skip resolve failed at {}; running vanilla decoration",
                    chunk.getPos(), t);
            return false;
        }
    }

    /** The track bed + End-island features are the only ones kept in the eroded core. */
    @Unique
    private static boolean dungeontrain$isDtFeature(PlacedFeature feature) {
        try {
            Feature<?> f = feature.feature().value().feature();
            return f == ModFeatures.TRACK_BED.get() || f == ModFeatures.DISINTEGRATION.get();
        } catch (Throwable t) {
            return true; // unclassifiable → keep it (never drop a feature we can't identify)
        }
    }
}
