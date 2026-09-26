package games.brennan.dungeontrain.mixin.betterend;

import games.brennan.dungeontrain.worldgen.PerThreadArrays;
import net.minecraft.core.Direction;
import org.betterx.betterend.world.features.bushes.BushWithOuterFeature;
import org.betterx.betterend.world.features.bushes.TenaneaBushFeature;
import org.betterx.betterend.world.features.trees.LucerniaFeature;
import org.betterx.betterend.world.features.trees.TenaneaFeature;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Every read of the shared {@code static DIRECTIONS} array in BetterEnd's tenanea, lucernia and bush features returns this thread's own copy
 * ({@link PerThreadArrays}), so a feature shuffling it on one thread can't reorder it under another.
 * See {@link BetterEndStaticShuffleMixin}, which makes each shuffle start from {@link Direction} order.
 */
@Mixin(value = { TenaneaFeature.class, LucerniaFeature.class, TenaneaBushFeature.class, BushWithOuterFeature.class }, remap = false)
public abstract class BetterEndDirectionsPerThreadMixin {

    @Shadow(remap = false) @Final private static Direction[] DIRECTIONS;

    @Redirect(method = "*", at = @At(value = "FIELD", target = "DIRECTIONS:[Lnet/minecraft/core/Direction;",
        opcode = org.objectweb.asm.Opcodes.GETSTATIC))
    private static Direction[] dungeontrain$perThread() {
        return PerThreadArrays.of(DIRECTIONS);
    }
}
