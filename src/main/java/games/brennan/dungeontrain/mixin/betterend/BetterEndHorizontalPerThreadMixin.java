package games.brennan.dungeontrain.mixin.betterend;

import games.brennan.dungeontrain.worldgen.PerThreadArrays;
import net.minecraft.core.Direction;
import org.betterx.betterend.world.features.terrain.GeyserFeature;
import org.betterx.betterend.world.structures.piece.SulphuricCavePiece;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Every read of the shared {@code static HORIZONTAL} array in BetterEnd's geysers and sulphuric caves returns this thread's own copy
 * ({@link PerThreadArrays}), so a feature shuffling it on one thread can't reorder it under another.
 * See {@link BetterEndStaticShuffleMixin}, which makes each shuffle start from {@link Direction} order.
 */
@Mixin(value = { GeyserFeature.class, SulphuricCavePiece.class }, remap = false)
public abstract class BetterEndHorizontalPerThreadMixin {

    @Shadow(remap = false) @Final private static Direction[] HORIZONTAL;

    @Redirect(method = "*", at = @At(value = "FIELD", target = "HORIZONTAL:[Lnet/minecraft/core/Direction;",
        opcode = org.objectweb.asm.Opcodes.GETSTATIC))
    private static Direction[] dungeontrain$perThread() {
        return PerThreadArrays.of(HORIZONTAL);
    }
}
