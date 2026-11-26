package games.brennan.dungeontrain.mixin.client.positionreadout.jade;

import games.brennan.dungeontrain.client.CoordinateDisguise;
import net.minecraft.core.Vec3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Makes Jade's optional "Coordinates" tooltip line print the position the debug screen prints, as F3's
 * Targeted Block line does ({@link CoordinateDisguise#targetedBlock}): moved off Sable's plot when the
 * block is on a train carriage, then by the camera's shift.
 *
 * <p>{@code DistanceProvider.append} calls {@code xyz} twice: first for "Relative Coordinates" (a
 * difference, which no shift changes), then for the absolute position. Only the second call
 * ({@code ordinal = 1}) is moved. As of the modpack's Jade build.</p>
 */
@Pseudo
@Mixin(targets = "snownee.jade.addon.core.DistanceProvider", remap = false)
public abstract class JadeCoordinatesMixin {

    @ModifyArg(method = "append", at = @At(value = "INVOKE",
        target = "Lsnownee/jade/addon/core/DistanceProvider;xyz(Lsnownee/jade/api/ITooltip;Lnet/minecraft/core/Vec3i;)V",
        ordinal = 1), index = 1, require = 0, remap = false)
    private Vec3i dungeontrain$disguise(Vec3i pos) {
        try {
            return CoordinateDisguise.targetedBlock(pos);
        } catch (Throwable ignored) {
            return pos;
        }
    }
}
