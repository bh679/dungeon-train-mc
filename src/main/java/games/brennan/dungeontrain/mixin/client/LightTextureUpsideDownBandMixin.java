package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.client.ClientUpsideDownBand;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pins the world lightmap to constant daylight while the camera is inside an upside-down band, so the
 * mirrored ceiling overhead and the player-facing undersides stay bright and playable instead of
 * falling into shadow (the engine's real skylight is still top-down, so without this the underside of
 * the ceiling would be dark). Sibling of {@link LightTextureEndBandMixin} — same two hooks, scaled by
 * the upside-down band intensity ({@link ClientUpsideDownBand#upsideDownIntensityAt}) so the lighting
 * crossfades in/out with the rotating sky rather than popping at the band edge.
 *
 * <p>Purely client-side and per-player; no server state, no saved-world change, and no game logic
 * (mob spawns, the clock) is affected — this only retints the lightmap. Self-disables ({@code t == 0})
 * outside the overworld, on non-train worlds, and when the band is disabled.</p>
 */
@Mixin(LightTexture.class)
public abstract class LightTextureUpsideDownBandMixin {

    /** Neutral bright daylight tint the shaded cells are lifted toward. */
    @Unique
    private static final Vector3f DUNGEONTRAIN_UD_TINT = new Vector3f(1.0F, 1.0F, 1.0F);

    /** Band intensity for the in-progress lightmap rebuild, captured once before the 16×16 cell loop. */
    @Unique
    private float dungeontrain$udBandT;

    /** Daytime pin: lerp the sky-darken factor toward full daylight by the band intensity. */
    @ModifyExpressionValue(
            method = "updateLightTexture(F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getSkyDarken(F)F"
            )
    )
    private float dungeontrain$udPinDaylight(float skyDarken) {
        float t = dungeontrain$udBandIntensity();
        this.dungeontrain$udBandT = t;
        return t <= 0.0F ? skyDarken : Mth.lerp(t, skyDarken, 1.0F);
    }

    /** Flat floor: after vanilla combines each cell, lift it toward the daylight tint by {@code lift * t}. */
    @Inject(
            method = "updateLightTexture(F)V",
            at = @At(
                    value = "INVOKE",
                    shift = At.Shift.AFTER,
                    target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;"
                            + "adjustLightmapColors(Lnet/minecraft/client/multiplayer/ClientLevel;FFFFIILorg/joml/Vector3f;)V"
            )
    )
    private void dungeontrain$udLiftFloor(float partialTicks, CallbackInfo ci, @Local(ordinal = 1) Vector3f cellColor) {
        float t = this.dungeontrain$udBandT;
        if (t > 0.0F) {
            float lift = (float) DungeonTrainCommonConfig.getUpsideDownLightLift();
            cellColor.lerp(DUNGEONTRAIN_UD_TINT, lift * t);
        }
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
