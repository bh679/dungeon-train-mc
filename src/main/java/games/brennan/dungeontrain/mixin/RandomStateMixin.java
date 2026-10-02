package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.density.NetherBandFloodednessDensityFunction;
import games.brennan.dungeontrain.worldgen.density.NetherBandHooks;
import games.brennan.dungeontrain.worldgen.density.NetherBandTerrainDensityFunction;
import games.brennan.dungeontrain.worldgen.density.TrackErosionDensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Raises the overworld terrain into the nether-transition band's mountains by wrapping the noise
 * router as it is built. {@code RandomState.<init>} assigns the router exactly once via
 * {@code settings.noiseRouter().mapAll(...)}; we modify that result so the rebuilt router (and the
 * {@code sampler}/{@code surfaceSystem} later derived from {@code this.router}) all carry the
 * raised {@code finalDensity} + {@code preliminarySurfaceLevel} — the single source of truth for
 * fill, {@code getBaseHeight} (structures) and surface painting.
 *
 * <p>Gated to the overworld via {@link NetherBandHooks#CONSTRUCTING_OVERWORLD} (set by
 * {@code ChunkMapMixin}); other dimensions return the router untouched. Any error falls back to the
 * vanilla router — worldgen is never broken by this hook.</p>
 */
@Mixin(RandomState.class)
public abstract class RandomStateMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    @ModifyExpressionValue(
        method = "<init>",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private NoiseRouter dungeontrain$raiseNetherBandTerrain(NoiseRouter router) {
        try {
            if (!Boolean.TRUE.equals(NetherBandHooks.CONSTRUCTING_OVERWORLD.get())) return router;

            // Raise BOTH surface-driving densities the same way: finalDensity (actual terrain +
            // getBaseHeight for structures) and initialDensityWithoutJaggedness (NoiseChunk derives
            // the preliminary surface level the surface-rule gate keys off — must track the new top
            // or the mountain paints as bare rock).
            // Only finalDensity carves the CavernNoise caverns; the preliminary surface stays at the top.
            DensityFunction finalDensity =
                    new NetherBandTerrainDensityFunction(router.finalDensity(), true);
            DensityFunction initialDensityWithoutJaggedness =
                    new NetherBandTerrainDensityFunction(router.initialDensityWithoutJaggedness(), false);
            // Keep the mountains' interior dry so the caverns (and the rails through them) never flood.
            DensityFunction floodedness =
                    new NetherBandFloodednessDensityFunction(router.fluidLevelFloodednessNoise());

            NoiseRouter raised = new NoiseRouter(
                    router.barrierNoise(),
                    floodedness,
                    router.fluidLevelSpreadNoise(),
                    router.lavaNoise(),
                    router.temperature(),
                    router.vegetation(),
                    router.continents(),
                    router.erosion(),
                    router.depth(),
                    router.ridges(),
                    initialDensityWithoutJaggedness,
                    finalDensity,
                    router.veinToggle(),
                    router.veinRidged(),
                    router.veinGap());
            return dungeontrain$flattenTrackErosion(raised);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] nether-band terrain raise wrap failed; using vanilla router", t);
            return router;
        }
    }

    /**
     * Wraps every copy of the router's erosion noise node in {@link TrackErosionDensityFunction} — the
     * upside-down band's keep-mountains-off-the-track weighting. The node is the router's climate
     * {@code erosion} with its holder and cache-marker layers peeled off (vanilla:
     * {@code flat_cache(shifted_noise(erosion))}); the terrain splines (offset / factor / jaggedness —
     * the terrain's height) reach a structurally-equal copy through different wrappers, so mapping the
     * whole router wraps both, and terrain and biome pick stay coherent. The visitor canonicalises
     * structurally-equal nodes (density functions are records) the way vanilla's wiring pass does, so
     * shared subtrees stay shared and their caches aren't duplicated. On failure the unflattened router
     * is kept.
     */
    private static NoiseRouter dungeontrain$flattenTrackErosion(NoiseRouter router) {
        try {
            DensityFunction erosion = router.erosion();
            // Peel the holder + cache-marker layers down to the noise node itself: the terrain splines
            // reach the same node through different wrappers than the router's climate field does.
            while (true) {
                if (erosion instanceof DensityFunctions.MarkerOrMarked marked) erosion = marked.wrapped();
                else if (erosion instanceof DensityFunctions.HolderHolder holder) erosion = holder.function().value();
                else break;
            }
            if (erosion.minValue() == erosion.maxValue()) {   // constant erosion (flat/debug presets) — nothing to weight
                LOGGER.info("[DungeonTrain] Overworld router has a constant erosion; upside-down track flattening is off");
                return router;
            }
            final DensityFunction target = erosion;
            java.util.Map<DensityFunction, DensityFunction> canonical = new java.util.HashMap<>();
            boolean[] found = {false};
            NoiseRouter out = router.mapAll(f -> canonical.computeIfAbsent(f, node -> {
                if (node.equals(target)) {
                    found[0] = true;
                    return new TrackErosionDensityFunction(node);
                }
                return node;
            }));
            if (!found[0]) {
                LOGGER.info("[DungeonTrain] Overworld erosion node not found in the router; upside-down track flattening is off");
            }
            return out;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] upside-down track erosion wrap failed; terrain left unflattened", t);
            return router;
        }
    }
}
