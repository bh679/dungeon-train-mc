package games.brennan.dungeontrain.mixin.betterend;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import org.betterx.bclib.util.MHelper;
import org.betterx.bclib.util.StructureErode;
import org.betterx.betterend.world.features.bushes.BushWithOuterFeature;
import org.betterx.betterend.world.features.bushes.TenaneaBushFeature;
import org.betterx.betterend.world.features.terrain.GeyserFeature;
import org.betterx.betterend.world.features.trees.LucerniaFeature;
import org.betterx.betterend.world.features.trees.TenaneaFeature;
import org.betterx.betterend.world.structures.piece.SulphuricCavePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Arrays;

/**
 * BetterEnd places trees, bushes, geysers and caves by shuffling a {@code static} {@link Direction} array in place
 * ({@code MHelper.shuffle(DIRECTIONS, random)}) and walking it. The array keeps whatever order the previous
 * placement left it in, so a tree's shape depended on every tree generated before it in that JVM — the same
 * chunk from the same seed came out different each run. Put the array back in {@link Direction} order before
 * every shuffle, so the result depends only on the feature's own seeded random.
 */
@Mixin(value = {
    TenaneaFeature.class,
    LucerniaFeature.class,
    TenaneaBushFeature.class,
    BushWithOuterFeature.class,
    GeyserFeature.class,
    SulphuricCavePiece.class,
    StructureErode.class
}, remap = false)
public abstract class BetterEndStaticShuffleMixin {

    @Redirect(method = "*", at = @At(value = "INVOKE",
        target = "Lorg/betterx/bclib/util/MHelper;shuffle([Ljava/lang/Object;Lnet/minecraft/util/RandomSource;)V"))
    private static void dungeontrain$shuffleFromCanonicalOrder(Object[] array, RandomSource random) {
        if (array instanceof Direction[] directions) Arrays.sort(directions);
        MHelper.shuffle(array, random);
    }
}
