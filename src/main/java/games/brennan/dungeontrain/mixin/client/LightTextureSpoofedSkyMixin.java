package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.brennan.dungeontrain.client.ClientNetherBand;
import games.brennan.dungeontrain.client.ClientVoidBand;
import games.brennan.dungeontrain.client.ShaderCompat;
import games.brennan.dungeontrain.client.shader.ShaderBisect;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes sky light out of a Nether or End band while a shader pack is rendering it.
 *
 * <h2>The problem this solves</h2>
 * <p>Sable meshes each carriage in plot space — an open area with a clear view of the sky — so a
 * carriage's blocks carry a baked sky-light value of roughly full daylight wherever the carriage
 * physically ends up. In the overworld that never shows, because a pack resolves shading through
 * its shadow map and the shadow map correctly puts a covered train in shadow. Under the pack's
 * <em>Nether</em> programs there is no sun and no shadow map to consult, so the pack falls back on
 * the sky light baked into the geometry: the train claimed full daylight while the Nether terrain
 * beside it correctly claimed none, and light appeared to pour through a solid roof onto the train
 * and nothing else.</p>
 *
 * <p>The real Nether and End both have {@code hasSkyLight = false}: sky light does not exist there,
 * so a high baked value cannot mean anything. Driving the lightmap's sky term to nothing across the
 * band says the same, and a baked value of fifteen stops meaning daylight. The band is then lit by
 * block light and the pack's own ambient, which is how those dimensions actually look.</p>
 *
 * <h2>Two deliberate choices</h2>
 * <p><b>Ramped by the band, not switched by the spoof.</b> The spoof flips between two whole
 * pipelines and the cross-fade renders both in one frame, but the lightmap is rebuilt on a tick and
 * shared between those two renders. Switching on the spoof would darken the outgoing world's half
 * of the blend as well and make the transition lurch; following the band's own ramp eases it in
 * alongside every other band effect.</p>
 *
 * <p><b>Shader packs only.</b> Without a pack the vanilla path lights the band as it always has,
 * so the change cannot alter what players without shaders see.</p>
 */
@Mixin(LightTexture.class)
public abstract class LightTextureSpoofedSkyMixin {

    /**
     * How far to take the sky term out, {@code 0}..{@code 1}. Computed once per rebuild rather than
     * per cell: the loop below runs 256 times and the answer is the same for all of them.
     */
    @Unique
    private float dungeontrain$skyFlatten;

    @Inject(method = "updateLightTexture", at = @At("HEAD"))
    private void dungeontrain$measureBand(float partialTicks, CallbackInfo ci) {
        dungeontrain$skyFlatten = 0.0f;
        if (!ShaderCompat.active() || !ShaderBisect.spoofEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) return;
        if (!mc.level.dimension().equals(Level.OVERWORLD)) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return;

        double camX = camera.getPosition().x;
        double band = Math.max(ClientNetherBand.netherIntensityAt(camX),
            ClientVoidBand.endSkyIntensityAt(camX));
        dungeontrain$skyFlatten = (float) Math.max(0.0, Math.min(1.0, band));
    }

    /**
     * The sky half of each lightmap cell. Ordinal 0 is the sky index; ordinal 1, immediately after,
     * is block light and is deliberately untouched — a torch has to keep working in the Nether.
     */
    @ModifyExpressionValue(
            method = "updateLightTexture",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LightTexture;getBrightness(Lnet/minecraft/world/level/dimension/DimensionType;I)F",
                    ordinal = 0
            )
    )
    private float dungeontrain$flattenSky(float original) {
        float t = dungeontrain$skyFlatten;
        return t <= 0.0f ? original : original * (1.0f - t);
    }
}
