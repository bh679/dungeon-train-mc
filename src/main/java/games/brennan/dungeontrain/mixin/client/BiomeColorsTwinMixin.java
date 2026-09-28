package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.worldgen.VanillaBiomeTwins;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Foliage and water tint follow the <b>block's</b> X, not the camera's. {@link Biome#getFoliageColor()}
 * and {@link Biome#getWaterColor()} take no position, but the {@link BiomeColors} resolvers that call
 * them are handed the block's {@code (biome, x, z)} — so the vanilla twin ({@link VanillaBiomeTwins})
 * is swapped in here, per block. Gating on the camera instead tinted every visible chunk by which side
 * of the WWOO stretch the player stood on, and {@code ClientLevel}'s per-chunk tint cache kept those
 * wrong colours after crossing over. Grass needs nothing: {@code getGrassColor(x, z)} already carries
 * the position ({@code BiomeMixin}). {@code method = "*"} matches the resolver lambdas without naming them.
 */
@Mixin(BiomeColors.class)
public abstract class BiomeColorsTwinMixin {

    @WrapOperation(method = "*",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/Biome;getFoliageColor()I"))
    private static int dungeontrain$foliageAtBlock(Biome biome, Operation<Integer> original,
                                                   @Local(argsOnly = true, ordinal = 0) double x) {
        return original.call(dungeontrain$atBlock(biome, x));
    }

    @WrapOperation(method = "*",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/Biome;getWaterColor()I"))
    private static int dungeontrain$waterAtBlock(Biome biome, Operation<Integer> original,
                                                 @Local(argsOnly = true, ordinal = 0) double x) {
        return original.call(dungeontrain$atBlock(biome, x));
    }

    private static Biome dungeontrain$atBlock(Biome biome, double x) {
        Biome twin = VanillaBiomeTwins.twinFor(biome, x);
        return twin != null ? twin : biome;
    }
}
