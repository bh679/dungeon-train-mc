package games.brennan.dungeontrain.mixin.betterend;

import games.brennan.dungeontrain.worldgen.PerThreadArrays;
import net.minecraft.core.Direction;
import org.betterx.bclib.util.StructureErode;
import org.betterx.betterend.world.features.WallScatterFeature;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Every read of the shared {@code static DIR} array in BCLib's structure erosion and BetterEnd's wall plants returns this thread's own copy
 * ({@link PerThreadArrays}), so a feature shuffling it on one thread can't reorder it under another.
 * See {@link BetterEndStaticShuffleMixin}, which makes each shuffle start from {@link Direction} order.
 */
@Mixin(value = { StructureErode.class, WallScatterFeature.class }, remap = false)
public abstract class BetterEndDirPerThreadMixin {

    @Shadow(remap = false) @Final private static Direction[] DIR;

    @Redirect(method = "*", at = @At(value = "FIELD", target = "DIR:[Lnet/minecraft/core/Direction;",
        opcode = org.objectweb.asm.Opcodes.GETSTATIC))
    private static Direction[] dungeontrain$perThread() {
        return PerThreadArrays.of(DIR);
    }
}
