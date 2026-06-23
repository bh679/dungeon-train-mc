package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.worldgen.density.NetherBandHooks;
import net.minecraft.core.HolderGetter;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Marks the OVERWORLD's {@code RandomState} construction so the router-wrap in
 * {@code RandomStateMixin} only raises terrain in the overworld (the nether-transition band is
 * overworld-only). {@code ChunkMap}'s constructor assigns {@code this.level} before it calls
 * {@code RandomState.create(...)}, so the dimension is known here; {@code RandomState} itself has
 * none. We scope {@link NetherBandHooks#CONSTRUCTING_OVERWORLD} tightly around that one call —
 * both run synchronously on the same thread during level setup, so the {@link ThreadLocal} cannot
 * leak to another dimension.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Shadow @Final private ServerLevel level;

    @WrapOperation(
        method = "<init>",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/RandomState;create(Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;Lnet/minecraft/core/HolderGetter;J)Lnet/minecraft/world/level/levelgen/RandomState;"))
    private RandomState dungeontrain$scopeOverworldRandomState(
            NoiseGeneratorSettings settings, HolderGetter<NormalNoise.NoiseParameters> noises, long seed,
            Operation<RandomState> original) {
        boolean overworld = this.level.dimension().equals(Level.OVERWORLD);
        NetherBandHooks.CONSTRUCTING_OVERWORLD.set(overworld);
        try {
            return original.call(settings, noises, seed);
        } finally {
            NetherBandHooks.CONSTRUCTING_OVERWORLD.set(Boolean.FALSE);
        }
    }
}
