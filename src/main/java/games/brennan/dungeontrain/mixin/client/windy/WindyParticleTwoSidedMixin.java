package games.brennan.dungeontrain.mixin.client.windy;

import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.client.wind.WindyDrive;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.TextureSheetParticle;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws each Windy streak from both sides. Windy turns its streak quad to face along the wind instead of
 * billboarding it, and particles render with back-face culling on, so from half the directions (looking back
 * along the train, for one) every streak was culled and the wind looked absent. While DT owns the wind the quad
 * is drawn a second time turned half a turn about the vertical, so it shows whichever way you look.
 *
 * <p>Applied only when {@code WindyMixinPlugin} finds {@code render} and {@code livingWindRotation} with these
 * descriptors, so a future Windy that draws streaks differently just skips it.</p>
 */
@Pseudo
@Mixin(targets = "xyz.bonfiremc.windy.particle.WindParticle", remap = false)
public abstract class WindyParticleTwoSidedMixin extends TextureSheetParticle {

    private WindyParticleTwoSidedMixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @Shadow(remap = false)
    private static Quaternionf livingWindRotation() {
        throw new AssertionError();
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V",
            at = @At("RETURN"), remap = false)
    private void dungeontrain$backFace(VertexConsumer buffer, Camera camera, float partialTick, CallbackInfo ci) {
        if (!WindyDrive.owns()) return;
        renderRotatedQuad(buffer, camera, livingWindRotation().rotateY((float) Math.PI), partialTick);
    }
}
