package games.brennan.dungeontrain.mixin;

import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches vanilla's (protected) overworld router builder so the Large Biomes and Amplified legacy
 * bands can rebuild the live overworld's router with the preset flags flipped — see
 * {@link games.brennan.dungeontrain.worldgen.legacy.preset.PresetTerrain}.
 */
@Mixin(NoiseRouterData.class)
public interface NoiseRouterDataAccessor {

    @Invoker("overworld")
    static NoiseRouter dungeontrain$overworld(HolderGetter<DensityFunction> densityFunctions,
                                              HolderGetter<NormalNoise.NoiseParameters> noises,
                                              boolean largeBiomes, boolean amplified) {
        throw new AssertionError("mixin invoker");
    }
}
