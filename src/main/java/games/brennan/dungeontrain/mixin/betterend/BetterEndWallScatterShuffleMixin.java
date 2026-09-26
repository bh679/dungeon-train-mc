package games.brennan.dungeontrain.mixin.betterend;

import games.brennan.dungeontrain.worldgen.PerThreadArrays;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import org.betterx.betterend.world.features.WallScatterFeature;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Same bug as {@link BetterEndStaticShuffleMixin}, in BetterEnd's wall plants (twisted moss, purple polypore…):
 * {@code WallScatterFeature.shuffle} swaps entries of its {@code static DIR} array in place, so which wall a
 * plant attaches to depended on every wall plant placed before it. Reset {@code DIR} to {@link Direction}
 * order at the start of each shuffle ({@link BetterEndDirPerThreadMixin} makes that array this thread's copy).
 */
@Mixin(value = WallScatterFeature.class, remap = false)
public abstract class BetterEndWallScatterShuffleMixin {

    @Shadow @Final private static Direction[] DIR;

    @Inject(method = "shuffle", at = @At("HEAD"))
    private void dungeontrain$canonicalOrder(RandomSource random, CallbackInfo ci) {
        Arrays.sort(PerThreadArrays.of(DIR));
    }
}
