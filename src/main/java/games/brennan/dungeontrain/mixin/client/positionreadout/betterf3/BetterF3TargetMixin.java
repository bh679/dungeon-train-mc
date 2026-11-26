package games.brennan.dungeontrain.mixin.client.positionreadout.betterf3;

import games.brennan.dungeontrain.client.CoordinateDisguise;
import games.brennan.dungeontrain.client.MapReadoutText;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes BetterF3's targeted block and fluid lines print the position the debug screen prints, by the
 * camera's shift, as F3's own Targeted Block line does ({@link CoordinateDisguise}).
 *
 * <p>Only the printed {@code getX/getY/getZ} are moved; the block and fluid state are looked up from
 * the real {@link BlockPos}, which this never touches. As of BetterF3 11.0.3.</p>
 */
@Pseudo
@Mixin(targets = "me.cominixo.betterf3.modules.TargetModule", remap = false)
public abstract class BetterF3TargetMixin {

    @Redirect(method = "update(Lnet/minecraft/client/Minecraft;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/core/BlockPos;getX()I"), require = 0, remap = false)
    private int dungeontrain$x(BlockPos pos) {
        int real = pos.getX();
        try {
            return MapReadoutText.add(real, CoordinateDisguise.cameraShift().x());
        } catch (Throwable ignored) {
            return real;
        }
    }

    @Redirect(method = "update(Lnet/minecraft/client/Minecraft;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/core/BlockPos;getY()I"), require = 0, remap = false)
    private int dungeontrain$y(BlockPos pos) {
        int real = pos.getY();
        try {
            return MapReadoutText.add(real, CoordinateDisguise.cameraShift().y());
        } catch (Throwable ignored) {
            return real;
        }
    }

    @Redirect(method = "update(Lnet/minecraft/client/Minecraft;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/core/BlockPos;getZ()I"), require = 0, remap = false)
    private int dungeontrain$z(BlockPos pos) {
        int real = pos.getZ();
        try {
            return MapReadoutText.add(real, CoordinateDisguise.cameraShift().z());
        } catch (Throwable ignored) {
            return real;
        }
    }
}
