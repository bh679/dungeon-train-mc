package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.client.ClientVoidBand;
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
 * Pins the world lightmap to "constant End daylight" while the camera is inside a
 * disintegration End band, so the floating End-stone islands ({@code DisintegrationFeature})
 * stop responding to nightfall — matching the End sky/fog/music the band already renders.
 *
 * <p>Two effects, both scaled by the same band intensity {@code t} the rest of the band uses
 * ({@link ClientVoidBand#endSkyIntensityAt}), so the lighting crossfades in/out with the sky
 * instead of popping at the band edge:</p>
 * <ul>
 *   <li><b>Daytime pin</b> — {@code ClientLevel.getSkyDarken} (the night-dimming factor that
 *       feeds the whole lightmap) is lerped toward {@code 1.0} (noon) by {@code t}, so
 *       sky-lit surfaces keep their daytime brightness at night.</li>
 *   <li><b>Flat floor</b> — after vanilla combines each lightmap cell, it is lerped toward the
 *       End's bright tint ({@code 0.99, 1.12, 1.0}) by {@code 0.25 * t} — the exact tint and
 *       strength vanilla's own {@code forceBrightLightmap} path uses in the real End — so
 *       shaded faces and undersides glow evenly rather than falling into shadow.</li>
 * </ul>
 *
 * <p>Purely client-side and per-player: each client evaluates the band at its own camera-X, so a
 * player outside a band sees normal night at the same instant. No server state, no saved-world
 * change, and no game logic (mob spawns, the clock) is affected — this only retints the
 * lightmap. Self-disables ({@code t == 0}) outside the overworld, on non-train worlds, and when
 * disintegration is turned off, via {@code endSkyIntensityAt}.</p>
 *
 * <p>Sibling of {@code LevelRendererVoidSkyMixin} / {@code VoidSkyEvents}, which render the End
 * sky, fog and music for the same bands.</p>
 */
@Mixin(LightTexture.class)
public abstract class LightTextureEndBandMixin {

    /** Vanilla's End "bright lightmap" tint (the {@code forceBrightLightmap} path target). */
    @Unique
    private static final Vector3f DUNGEONTRAIN_END_TINT = new Vector3f(0.99F, 1.12F, 1.0F);

    /** Max floor-lift toward the End tint at full band intensity — matches vanilla's End {@code 0.25}. */
    @Unique
    private static final float DUNGEONTRAIN_END_LIFT = 0.25F;

    /**
     * Band intensity for the in-progress lightmap rebuild, captured by the daytime-pin hook
     * (which runs once, before the 16×16 cell loop) and reused by the per-cell floor-lift so the
     * band is evaluated a single time per rebuild rather than 256 times.
     */
    @Unique
    private float dungeontrain$bandT;

    /**
     * Daytime pin: lerp the sky-darken factor toward full daylight by the band intensity, and
     * cache that intensity for {@link #dungeontrain$liftFloor}.
     */
    @ModifyExpressionValue(
            method = "updateLightTexture(F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getSkyDarken(F)F"
            )
    )
    private float dungeontrain$pinDaylight(float skyDarken) {
        float t = dungeontrain$bandIntensity();
        this.dungeontrain$bandT = t;
        return t <= 0.0F ? skyDarken : Mth.lerp(t, skyDarken, 1.0F);
    }

    /**
     * Flat floor: after vanilla has combined block + (now-daytime) sky light for this cell, lift
     * it toward the End tint by {@code 0.25 * t} so shadowed faces glow like the real End.
     */
    @Inject(
            method = "updateLightTexture(F)V",
            at = @At(
                    value = "INVOKE",
                    shift = At.Shift.AFTER,
                    target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;"
                            + "adjustLightmapColors(Lnet/minecraft/client/multiplayer/ClientLevel;FFFFIILorg/joml/Vector3f;)V"
            )
    )
    private void dungeontrain$liftFloor(float partialTicks, CallbackInfo ci, @Local(ordinal = 1) Vector3f cellColor) {
        float t = this.dungeontrain$bandT;
        if (t > 0.0F) {
            cellColor.lerp(DUNGEONTRAIN_END_TINT, DUNGEONTRAIN_END_LIFT * t);
        }
    }

    /** End-sky band intensity at the camera, {@code 0} outside the overworld or any active band. */
    @Unique
    private static float dungeontrain$bandIntensity() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return 0.0F;
        double camX = mc.gameRenderer.getMainCamera().getPosition().x;
        return (float) ClientVoidBand.endSkyIntensityAt(camX);
    }
}
