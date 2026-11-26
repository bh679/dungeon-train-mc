package games.brennan.dungeontrain.mixin.client.positionreadout.betterf3;

import games.brennan.dungeontrain.client.CoordinateDisguise;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes BetterF3's dimension line print the band name the debug screen prints
 * ({@link CoordinateDisguise#shownDimension}). {@code LocationModule.update} reads
 * {@code ResourceKey.location()} once, for that line only; the level it looks the server up by is
 * fetched by key, untouched. As of BetterF3 11.0.3.
 */
@Pseudo
@Mixin(targets = "me.cominixo.betterf3.modules.LocationModule", remap = false)
public abstract class BetterF3LocationMixin {

    @Redirect(method = "update(Lnet/minecraft/client/Minecraft;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/resources/ResourceKey;location()Lnet/minecraft/resources/ResourceLocation;"),
        require = 0, remap = false)
    private ResourceLocation dungeontrain$dimension(ResourceKey<?> key) {
        ResourceLocation real = key.location();
        try {
            return CoordinateDisguise.shownDimension(real);
        } catch (Throwable ignored) {
            return real;
        }
    }
}
