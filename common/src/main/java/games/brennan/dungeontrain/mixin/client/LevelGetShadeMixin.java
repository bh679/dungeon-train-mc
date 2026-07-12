package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.ClientUpsideDownBand;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Biases block-face directional shading toward "light from the sides" while the local camera is inside
 * an upside-down band: the four horizontal faces are lifted toward full brightness and the up/down
 * faces are dimmed, so terrain reads as side-lit rather than top-lit — matching the horizon sun the
 * band renders ({@link games.brennan.dungeontrain.client.UpsideDownSkyRenderer}).
 *
 * <p><b>Static, not per-frame.</b> Vanilla bakes {@code getShade} into the chunk mesh, so this only
 * takes effect on sections (re)meshed while in the band — it does not rotate with the sun in real time
 * (that would need constant re-meshing or a custom shader; deliberately out of scope for performance +
 * Sable/Sodium compat). Purely client-side and per-player; scaled by the band intensity so it
 * crossfades with the sky. {@code require = 0} so a target miss degrades to vanilla shading rather than
 * failing to load.</p>
 */
@Mixin(Level.class)
public abstract class LevelGetShadeMixin {

    @Inject(method = "getShade(Lnet/minecraft/core/Direction;Z)F", at = @At("RETURN"), cancellable = true, require = 0)
    private void dungeontrain$sideLightShade(Direction direction, boolean shade, CallbackInfoReturnable<Float> cir) {
        if (!shade) return; // the flat "no directional shading" path (items/entities) — leave it
        float t = dungeontrain$udBandIntensity();
        if (t <= 0.0F) return;
        float original = cir.getReturnValueF();
        float target = dungeontrain$sideBiasShade(direction);
        cir.setReturnValue(original + (target - original) * t);
    }

    /** Side-lit face brightness: horizontal faces brightest, up/down dimmed. */
    @Unique
    private static float dungeontrain$sideBiasShade(Direction dir) {
        return switch (dir) {
            case UP, DOWN -> 0.7F;
            default -> 1.0F; // NORTH / SOUTH / EAST / WEST
        };
    }

    /** Upside-down band intensity at the camera, {@code 0} outside the overworld or any active band. */
    @Unique
    private static float dungeontrain$udBandIntensity() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return 0.0F;
        double camX = mc.gameRenderer.getMainCamera().getPosition().x;
        return (float) ClientUpsideDownBand.upsideDownIntensityAt(camX);
    }
}
