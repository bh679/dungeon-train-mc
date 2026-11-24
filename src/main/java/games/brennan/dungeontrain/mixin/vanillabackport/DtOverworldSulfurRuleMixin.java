package games.brennan.dungeontrain.mixin.vanillabackport;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.worldgen.BackportSurfaceRules;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gives DT's overworld noise settings VanillaBackport's sulfur-cave surface rule.
 *
 * <p>VanillaBackport 1.2+ wraps this same {@code surfaceRule()} read and merges its rule in only for
 * {@code minecraft:overworld}; this wrap does the same merge for {@code dungeontrain:overworld*}
 * ({@link BackportSurfaceRules}). The two wraps chain, and only one of them ever merges for a given
 * generator, so no rule is added twice.</p>
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class DtOverworldSulfurRuleMixin {

    @Shadow
    @Final
    private Holder<NoiseGeneratorSettings> settings;

    @WrapOperation(
            method = "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;"
                    + "Lnet/minecraft/world/level/levelgen/WorldGenerationContext;"
                    + "Lnet/minecraft/world/level/levelgen/RandomState;"
                    + "Lnet/minecraft/world/level/StructureManager;"
                    + "Lnet/minecraft/world/level/biome/BiomeManager;"
                    + "Lnet/minecraft/core/Registry;"
                    + "Lnet/minecraft/world/level/levelgen/blending/Blender;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;surfaceRule()"
                            + "Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;"))
    private SurfaceRules.RuleSource dungeontrain$sulfurRule(NoiseGeneratorSettings noiseSettings,
                                                           Operation<SurfaceRules.RuleSource> original) {
        SurfaceRules.RuleSource rule = original.call(noiseSettings);
        return settings.unwrapKey().filter(BackportSurfaceRules::isDtOverworld).isPresent()
                ? BackportSurfaceRules.withSulfur(rule)
                : rule;
    }
}
