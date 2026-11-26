package games.brennan.dungeontrain.mixin.client.positionreadout.sodiumextra;

import games.brennan.dungeontrain.client.CoordinateDisguise;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes Sodium Extra's optional coordinates overlay print the position the debug screen prints
 * ({@link CoordinateDisguise}). {@code SodiumExtraHud.onStartTick} reads the player's
 * {@code position()} once, for that line only. As of Sodium Extra 0.9.5.
 */
@Pseudo
@Mixin(targets = "me.flashyreese.mods.sodiumextra.client.gui.SodiumExtraHud", remap = false)
public abstract class SodiumExtraCoordsMixin {

    @Redirect(method = "onStartTick(Lnet/minecraft/client/Minecraft;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/LocalPlayer;position()Lnet/minecraft/world/phys/Vec3;"),
        require = 0, remap = false)
    private Vec3 dungeontrain$position(LocalPlayer player) {
        Vec3 real = player.position();
        try {
            return CoordinateDisguise.shifted(real, CoordinateDisguise.shiftFor(player));
        } catch (Throwable ignored) {
            return real;
        }
    }
}
