package games.brennan.dungeontrain.mixin.client.positionreadout.betterf3;

import games.brennan.dungeontrain.client.CoordinateDisguise;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes BetterF3's coordinates module print the position the debug screen prints
 * ({@link CoordinateDisguise}). BetterF3 draws its own debug screen, so vanilla's lines, which DT
 * already rewrites, never reach it.
 *
 * <p>{@code CoordsModule.update} only prints what it reads, so the reads themselves are moved: the
 * camera's first {@code getX/getY/getZ} (the XYZ line) and its {@code blockPosition} (the block, chunk
 * and chunk-relative lines). The later reads, which work out the velocity lines, are left real, so
 * velocity never jumps. As of BetterF3 11.0.3.</p>
 */
@Pseudo
@Mixin(targets = "me.cominixo.betterf3.modules.CoordsModule", remap = false)
public abstract class BetterF3CoordsMixin {

    @Redirect(method = "update(Lnet/minecraft/client/Minecraft;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;getX()D", ordinal = 0), require = 0, remap = false)
    private double dungeontrain$x(Entity camera) {
        double real = camera.getX();
        try {
            return real + CoordinateDisguise.shiftFor(camera).x();
        } catch (Throwable ignored) {
            return real;
        }
    }

    @Redirect(method = "update(Lnet/minecraft/client/Minecraft;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;getY()D", ordinal = 0), require = 0, remap = false)
    private double dungeontrain$y(Entity camera) {
        double real = camera.getY();
        try {
            return real + CoordinateDisguise.shiftFor(camera).y();
        } catch (Throwable ignored) {
            return real;
        }
    }

    @Redirect(method = "update(Lnet/minecraft/client/Minecraft;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;getZ()D", ordinal = 0), require = 0, remap = false)
    private double dungeontrain$z(Entity camera) {
        double real = camera.getZ();
        try {
            return real + CoordinateDisguise.shiftFor(camera).z();
        } catch (Throwable ignored) {
            return real;
        }
    }

    @Redirect(method = "update(Lnet/minecraft/client/Minecraft;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;blockPosition()Lnet/minecraft/core/BlockPos;"),
        require = 0, remap = false)
    private BlockPos dungeontrain$block(Entity camera) {
        BlockPos real = camera.blockPosition();
        try {
            return CoordinateDisguise.shifted(real, CoordinateDisguise.shiftFor(camera));
        } catch (Throwable ignored) {
            return real;
        }
    }
}
