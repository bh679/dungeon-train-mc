package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.DungeonTrain;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.ChuncksBand;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.SpheresBand;
import games.brennan.dungeontrain.worldgen.StacksBand;
import games.brennan.dungeontrain.worldgen.OfflineChunkSampler;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
import games.brennan.dungeontrain.worldgen.WwooDecorationPass;
import games.brennan.dungeontrain.worldgen.feature.DeferredStructurePlacement;
import games.brennan.dungeontrain.worldgen.feature.ModFeatures;
import games.brennan.dungeontrain.worldgen.structure.ModStructureTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;

import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the vanilla decoration + structure pass on chunks that generate as pure void — the
 * disintegration band's fully-eroded core AND the chuncks band's void chunks.
 * {@link NoiseBasedChunkGeneratorMixin} already short-circuits the noise terrain for these chunks; this
 * drops the remaining generate-then-erase waste — every vanilla biome feature (trees/ores/lakes/…) and
 * structure piece that would be placed at {@code minY} on the now-empty terrain (and, for lakes/springs,
 * would even seed fresh water/lava sources into the void).
 *
 * <p>The DT features {@code track_bed} + {@code disintegration} run in the <b>same</b> monolithic
 * {@link ChunkGenerator#applyBiomeDecoration} (both at {@code top_layer_modification}), so there is
 * no datapack seam to keep only them — instead we redirect the per-feature / per-structure placement
 * calls and, in a fully-eroded overworld chunk, run only the DT features and the DT End-city structure.
 * The floating track + End islands + chorus + End cities survive; nothing else generates.</p>
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

    /**
     * Per-decoration-call flag: true while decorating a chunk whose structure pieces must wait for the
     * Nether core fill (see {@link DeferredStructurePlacement}). Same lifecycle and thread-safety argument
     * as the flag above — set at the head of every {@code applyBiomeDecoration}, read by the redirect below.
     */
    @Unique
    private static final ThreadLocal<Boolean> dungeontrain$deferStructures = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
    private void dungeontrain$computeSkip(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager, CallbackInfo ci) {
        boolean skip = dungeontrain$isFullyErodedBandChunk(level, chunk);
        dungeontrain$skipDecoration.set(skip);
        dungeontrain$deferStructures.set(DeferredStructurePlacement.isDeferred(level, chunk.getPos()));
        WwooDecorationPass.begin(level, chunk, skip);
    }

    /**
     * Each decoration step starts by asking whether structures generate — the one per-step call in
     * {@code applyBiomeDecoration}. Just before it, the vanilla features WWOO removed from the previous
     * step are placed (outside the WWOO stretch; see {@link WwooDecorationPass}).
     */
    @Inject(method = "applyBiomeDecoration",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/StructureManager;shouldGenerateStructures()Z"))
    private void dungeontrain$vanillaFeaturesBeforeStep(WorldGenLevel level, ChunkAccess chunk,
                                                        StructureManager structureManager, CallbackInfo ci) {
        WwooDecorationPass.beforeStep(level, (ChunkGenerator) (Object) this);
    }

    @Inject(method = "applyBiomeDecoration", at = @At("TAIL"))
    private void dungeontrain$vanillaFeaturesLastStep(WorldGenLevel level, ChunkAccess chunk,
                                                      StructureManager structureManager, CallbackInfo ci) {
        WwooDecorationPass.finish(level, (ChunkGenerator) (Object) this);
    }

    @Redirect(
        method = "applyBiomeDecoration",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;placeWithBiomeCheck(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean dungeontrain$filterFeature(PlacedFeature feature, WorldGenLevel level, ChunkGenerator generator,
                                               RandomSource random, BlockPos origin) {
        if (OfflineChunkSampler.isSampling() && dungeontrain$isDtNamespaceFeature(feature)) {
            return false; // offline sample: DT's corridor and band features belong to the display world only
        }
        if (dungeontrain$skipDecoration.get() && !dungeontrain$isDtFeature(feature)) {
            return false; // fully-eroded core: skip the vanilla feature (it would be erased anyway)
        }
        if (WwooDecorationPass.vetoes(feature)) {
            return false; // outside the WWOO stretch: WWOO-only or overridden (vanilla version places later)
        }
        return feature.placeWithBiomeCheck(level, generator, random, origin);
    }

    /**
     * Skip vanilla structure piece placement in the eroded core — pointless in the void, and it would be
     * erased by the erosion pass anyway. The band's own structures are the exception: the End cities are
     * the point of the floating End islands, so their pieces are let through. (The Nether band's
     * structures never meet this path — its core chunks carry real terrain and are not eroded — but they
     * are whitelisted with the cities so the rule stays "DT's own structures survive".)
     *
     * <p>Redirecting the per-structure {@code startsForStructure} lookup (rather than the
     * {@code shouldGenerateStructures()} guard around the whole loop) is what makes that distinction
     * possible: an empty start list places nothing, exactly as before, for every other structure.</p>
     *
     * <p>The same seam also <b>defers</b> placement on Nether-core chunks —
     * {@link DeferredStructurePlacement} — so those pieces are written by
     * {@code NetherStructuresFeature} once the core terrain exists rather than against the overworld
     * mountain the fill replaces.</p>
     */
    @Redirect(
        method = "applyBiomeDecoration",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/StructureManager;startsForStructure(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Ljava/util/List;"))
    private List<StructureStart> dungeontrain$filterStructure(StructureManager structureManager,
                                                              SectionPos sectionPos, Structure structure) {
        // Nether core: every structure here waits for NetherStructuresFeature, which runs after the core
        // terrain is stamped. Placing now would build a fortress against the overworld mountain that the
        // fill is about to replace — which is how they came out with no supports under them.
        if (dungeontrain$deferStructures.get()) {
            return List.of();
        }
        if (dungeontrain$skipDecoration.get() && !dungeontrain$isDtStructure(structure)) {
            return List.of();
        }
        return structureManager.startsForStructure(sectionPos, structure);
    }

    /** The band's own structures are the only ones kept in the eroded core. */
    @Unique
    private static boolean dungeontrain$isDtStructure(Structure structure) {
        try {
            return ModStructureTypes.isBandStructure(structure.type());
        } catch (Throwable t) {
            return false; // unclassifiable → treat as vanilla (the pre-existing behaviour: skip it)
        }
    }

    /**
     * True iff this is an OVERWORLD chunk that generates as pure void — either the disintegration band's
     * eroded core (mirrors the fill mixin's gate) or a chuncks-band <b>void</b> chunk. Vanilla decoration
     * on such a chunk is pure waste: features place at {@code minY} on the empty terrain and spring/lake
     * features would even seed fresh water/lava sources into the void (feeding the flow problem). Only the
     * DT features are kept, so the floating track bed still generates.
     */
    @Unique
    private static boolean dungeontrain$isFullyErodedBandChunk(WorldGenLevel level, ChunkAccess chunk) {
        try {
            ServerLevel serverLevel = level.getLevel();
            if (!serverLevel.dimension().equals(Level.OVERWORLD)) return false;
            int chunkMinX = chunk.getPos().getMinBlockX();
            long startX = DisintegrationBand.startX(serverLevel);
            if (startX != DisintegrationBand.OFF && chunkMinX + 15 >= startX
                    && DisintegrationBand.isChunkFullyEroded(serverLevel, chunkMinX)) {
                return true;
            }
            int chunkMinZ = chunk.getPos().getMinBlockZ();
            return ChuncksBand.isVoidChunk(serverLevel, chunkMinX, chunkMinZ)
                    || SpheresBand.isVoidChunk(serverLevel, chunkMinX, chunkMinZ)
                    || StacksBand.isVoidOrStackChunk(serverLevel, chunkMinX, chunkMinZ)
                    // Legacy band: not void, but its old generator decorates it (LegacyDecorateFeature) —
                    // vanilla features and structure pieces would be modern things on old terrain.
                    || dungeontrain$isOldGeneratorChunk(serverLevel, chunk);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] decoration-skip resolve failed at {}; running vanilla decoration",
                    chunk.getPos(), t);
            return false;
        }
    }

    /** Any feature registered under DT's namespace — vetoed inside offline samples. Unreadable → not vetoed. */
    @Unique
    private static boolean dungeontrain$isDtNamespaceFeature(PlacedFeature feature) {
        try {
            ResourceLocation key = BuiltInRegistries.FEATURE.getKey(feature.feature().value().feature());
            return key != null && DungeonTrain.MOD_ID.equals(key.getNamespace());
        } catch (Throwable t) {
            return false;
        }
    }

    /** A legacy chunk owned by an OLD generator — a modern-preset band's chunks keep vanilla decoration. */
    @Unique
    private static boolean dungeontrain$isOldGeneratorChunk(ServerLevel serverLevel, ChunkAccess chunk) {
        games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind kind =
                LegacyBands.kindOfChunk(serverLevel, chunk.getPos().x, chunk.getPos().z);
        return kind != null && !kind.isPreset();
    }

    /** The track bed + End-island features are the only ones kept in the eroded core. */
    @Unique
    private static boolean dungeontrain$isDtFeature(PlacedFeature feature) {
        try {
            Feature<?> f = feature.feature().value().feature();
            return f == ModFeatures.TRACK_BED.get() || f == ModFeatures.DISINTEGRATION.get()
                    || f == ModFeatures.STACKS.get() || f == ModFeatures.LEGACY_DECORATE.get();
        } catch (Throwable t) {
            return true; // unclassifiable → keep it (never drop a feature we can't identify)
        }
    }
}
