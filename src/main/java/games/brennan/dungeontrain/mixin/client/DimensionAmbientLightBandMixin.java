package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.ClientNetherBand;
import games.brennan.dungeontrain.client.ClientVoidBand;
import games.brennan.dungeontrain.client.ShaderCompat;
import games.brennan.dungeontrain.client.shader.ShaderBisect;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives a Nether or End band the flat ambient lighting those dimensions use, so the train stops
 * being lit from a direction nothing else in the band is lit from.
 *
 * <h2>Why only the train was ever wrong</h2>
 * <p>{@code LevelRenderer} picks the world's directional lighting from this one flag, immediately
 * before the entity pass:</p>
 * <pre>
 *   if (level.effects().constantAmbientLight()) Lighting.setupNetherLevel();  // flat
 *   else                                        Lighting.setupLevel();        // two directional lights
 * </pre>
 * <p>Those lights apply to entities and to anything drawn in that part of the frame — which is
 * where Sable draws its carriages — and <em>not</em> to terrain, whose shading is baked into the
 * chunk mesh. A band is the overworld, so it took the directional branch, and the train was lit and
 * shadowed from an angle the Nether around it plainly was not. That is the whole "it only ever
 * affects the train" signature, and no amount of adjusting the sun could have reached it, because
 * this light is not the sun.</p>
 *
 * <p>The real Nether and End both set this flag. Following the band's own ramp rather than the
 * pipeline swap keeps it in step with every other band effect, and confining it to shader packs
 * leaves the vanilla look exactly as it has always been.</p>
 */
@Mixin(DimensionSpecialEffects.class)
public abstract class DimensionAmbientLightBandMixin {

    /** Band strength past which the flat lighting is used. A hard switch: the flag is a boolean. */
    private static final double DUNGEONTRAIN_BAND_THRESHOLD = 0.5;

    @Inject(method = "constantAmbientLight", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$flatLightInBand(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) return;
        if (!ShaderCompat.active() || !ShaderBisect.spoofEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) return;
        if (!mc.level.dimension().equals(Level.OVERWORLD)) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return;

        double camX = camera.getPosition().x;
        double band = Math.max(ClientNetherBand.netherIntensityAt(camX),
            ClientVoidBand.endSkyIntensityAt(camX));
        if (band > DUNGEONTRAIN_BAND_THRESHOLD) cir.setReturnValue(true);
    }
}
