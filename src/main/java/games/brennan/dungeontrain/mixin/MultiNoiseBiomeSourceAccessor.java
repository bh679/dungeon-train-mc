package games.brennan.dungeontrain.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches the live overworld source's (private) parameter list. With TerraBlender installed that list
 * also carries TerraBlender's region layout — {@code OverworldStretchBiomes} reads its region index to
 * keep Biomes O' Plenty's regions shaped the way TerraBlender lays them out.
 */
@Mixin(MultiNoiseBiomeSource.class)
public interface MultiNoiseBiomeSourceAccessor {

    @Invoker("parameters")
    Climate.ParameterList<Holder<Biome>> dungeontrain$parameters();
}
