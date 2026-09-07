package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.ClientUpsideDownBand;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sinks the cloud plane in the upside-down band at its source.
 *
 * <p>{@code getCloudHeight()} is the one number every cloud renderer starts from — vanilla's
 * {@code renderClouds}, and Iris' {@code cloudHeight} uniform, which packs that keep vanilla-style
 * clouds position theirs by. Lowering it here, rather than only at the one read inside vanilla's
 * cloud pass, means the sunk plane reaches whichever of those is drawing this frame. Packs that
 * switch vanilla clouds off and draw their own volumetrics in composite read neither, and their
 * clouds stay where the pack puts them — nothing here can reach those.</p>
 *
 * <p>Keyed on the render camera's world-X, the same ramp the band's sky and lightmap use; the
 * overworld only, since the band is a region of it.</p>
 */
@Mixin(DimensionSpecialEffects.class)
public abstract class DimensionSpecialEffectsCloudHeightMixin {

    @Inject(method = "getCloudHeight", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$sinkCloudsInUpsideDown(CallbackInfoReturnable<Float> cir) {
        float original = cir.getReturnValueF();
        if (Float.isNaN(original)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return;
        double t = ClientUpsideDownBand.upsideDownIntensityAt(camera.getPosition().x);
        float applied = t <= 0.0
            ? original
            : Mth.lerp((float) t, original, DungeonTrainCommonConfig.getUpsideDownCloudY());
        if (games.brennan.dungeontrain.client.ShaderDiagnostics.recording()) {
            games.brennan.dungeontrain.client.ShaderDiagnostics.recordCloudHeight(original, applied);
        }
        if (applied != original) cir.setReturnValue(applied);
    }
}
