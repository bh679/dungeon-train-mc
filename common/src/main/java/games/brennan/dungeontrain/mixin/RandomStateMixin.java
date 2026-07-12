package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.density.NetherBandHooks;
import games.brennan.dungeontrain.worldgen.density.NetherBandTerrainDensityFunction;
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
            DensityFunction finalDensity =
                    new NetherBandTerrainDensityFunction(router.finalDensity());
            DensityFunction initialDensityWithoutJaggedness =
                    new NetherBandTerrainDensityFunction(router.initialDensityWithoutJaggedness());

            return new NoiseRouter(
                    router.barrierNoise(),
                    router.fluidLevelFloodednessNoise(),
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
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] nether-band terrain raise wrap failed; using vanilla router", t);
            return router;
        }
    }
}
