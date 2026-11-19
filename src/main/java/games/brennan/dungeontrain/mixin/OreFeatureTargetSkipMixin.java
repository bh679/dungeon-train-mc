package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.feature.NetherCoreDecoration;
import games.brennan.dungeontrain.worldgen.feature.OreTargetPrecheck;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Inside DT's core feature pass, an ore blob with nothing under it to replace returns at once instead of
 * scanning its whole volume. Exact: same blocks, same random draws — see {@link OreTargetPrecheck}. Outside
 * the pass (every real dimension, the overworld's own ores) this does nothing.
 */
@Mixin(OreFeature.class)
public abstract class OreFeatureTargetSkipMixin {

    @Inject(method = "doPlace", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$skipBlobWithNoTarget(WorldGenLevel level, RandomSource random, OreConfiguration config,
                                                   double minX, double maxX, double minZ, double maxZ,
                                                   double minY, double maxY, int x, int y, int z,
                                                   int width, int height, CallbackInfoReturnable<Boolean> cir) {
        if (!NetherCoreDecoration.isActive()) return;
        if (OreTargetPrecheck.trySkip(level, random, config, x, y, z, width, height)) {
            cir.setReturnValue(false);
        }
    }
}
