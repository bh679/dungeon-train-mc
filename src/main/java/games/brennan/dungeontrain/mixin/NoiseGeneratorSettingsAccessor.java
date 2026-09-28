package games.brennan.dungeontrain.mixin;

import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The settings' own {@code surface_rule}, read straight from the record field. The record accessor
 * {@code surfaceRule()} is HEAD-cancelled by TerraBlender and answers its namespaced wrapper instead,
 * so {@link games.brennan.dungeontrain.worldgen.DtSurfaceRules} needs this to recognise DT's rules.
 */
@Mixin(NoiseGeneratorSettings.class)
public interface NoiseGeneratorSettingsAccessor {

    @Accessor("surfaceRule")
    SurfaceRules.RuleSource dungeontrain$rawSurfaceRule();
}
