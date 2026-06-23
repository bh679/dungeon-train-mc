package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.client.ClientNetherBand;
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
 * Pins the world lightmap to a "constant dim Nether glow" while the camera is inside a
 * Nether transition band's real-Nether core, so the netherrack core stops responding to
 * nightfall — matching the red/orange fog the band already renders ({@code NetherFogEvents}).
 *
 * <p>The Nether sibling of {@link LightTextureEndBandMixin}: same two-stage mechanism, scaled
 * by the same band intensity the rest of the Nether band uses
 * ({@link ClientNetherBand#netherIntensityAt}), so the lighting crossfades in/out with the fog
 * rather than popping at the band edge. Two differences from the End version, because the
 * Nether is <em>dim and warm</em> where the End is <em>bright</em>:</p>
 * <ul>
 *   <li><b>Dim pin (not noon)</b> — {@code ClientLevel.getSkyDarken} (the night-dimming factor
 *       that feeds the whole lightmap) is lerped toward a constant {@link #DUNGEONTRAIN_NETHER_SKY}
 *       by {@code n}, instead of toward {@code 1.0} (noon). This converges day and night to the
 *       same dim level, so the core neither darkens at night nor brightens to full daylight —
 *       a perpetual dim glow, like the Nether's lack of a day/night cycle.</li>
 *   <li><b>Warm ember floor (not the bright End tint)</b> — after vanilla combines each lightmap
 *       cell, it is lerped toward a dim warm {@link #DUNGEONTRAIN_NETHER_TINT} by
 *       {@code DUNGEONTRAIN_NETHER_LIFT * n}, so everything takes a warm cast and shaded faces
 *       lift to a low ember floor (evoking the Nether dimension type's {@code ambient_light: 0.1})
 *       rather than falling into pure-black shadow.</li>
 * </ul>
 *
 * <p>Purely client-side and per-player: each client evaluates the band at its own camera-X, so a
 * player outside the core sees normal day/night at the same instant. No server state, no
 * saved-world change, and no game logic (mob spawns, the clock) is affected — this only retints
 * the lightmap. Self-disables ({@code n == 0}) outside the overworld, on non-train worlds, when
 * the Nether transition is turned off, and everywhere outside the real-Nether core — exactly
 * where {@code NetherFogEvents} stops tinting the fog — via {@code netherIntensityAt}.</p>
 *
 * <p>Shares its target method {@code LightTexture.updateLightTexture(F)V} with
 * {@link LightTextureEndBandMixin}; the two never both apply because End and Nether bands never
 * overlap in world-X (at most one of {@code t}/{@code n} is non-zero), so the stacked handlers
 * compose cleanly.</p>
 */
@Mixin(LightTexture.class)
public abstract class LightTextureNetherBandMixin {

    /** Dim warm Nether tint the shaded-cell floor is lifted toward (a low red/orange ember). */
    @Unique
    private static final Vector3f DUNGEONTRAIN_NETHER_TINT = new Vector3f(0.65F, 0.32F, 0.20F);

    /** Max floor-lift toward the Nether tint at full band intensity. */
    @Unique
    private static final float DUNGEONTRAIN_NETHER_LIFT = 0.30F;

    /**
     * Constant sky-darken value the daytime factor is pinned toward at full intensity — a dim
     * level (well below noon's {@code 1.0}) so the core stays a perpetual dim glow and ignores
     * the day/night clock.
     */
    @Unique
    private static final float DUNGEONTRAIN_NETHER_SKY = 0.60F;

    /**
     * Band intensity for the in-progress lightmap rebuild, captured by the dim-pin hook (which
     * runs once, before the 16×16 cell loop) and reused by the per-cell floor-lift so the band is
     * evaluated a single time per rebuild rather than 256 times.
     */
    @Unique
    private float dungeontrain$netherN;

    /**
     * Dim pin: lerp the sky-darken factor toward the constant dim Nether level by the band
     * intensity, and cache that intensity for {@link #dungeontrain$liftNetherFloor}.
     */
    @ModifyExpressionValue(
            method = "updateLightTexture(F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getSkyDarken(F)F"
            )
    )
    private float dungeontrain$pinNetherDim(float skyDarken) {
        float n = dungeontrain$netherIntensity();
        this.dungeontrain$netherN = n;
        return n <= 0.0F ? skyDarken : Mth.lerp(n, skyDarken, DUNGEONTRAIN_NETHER_SKY);
    }

    /**
     * Warm ember floor: after vanilla has combined block + (now-pinned) sky light for this cell,
     * lift it toward the dim warm Nether tint by {@code 0.30 * n} so shadowed faces glow like a
     * low Nether ember rather than going black.
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
    private void dungeontrain$liftNetherFloor(float partialTicks, CallbackInfo ci, @Local(ordinal = 1) Vector3f cellColor) {
        float n = this.dungeontrain$netherN;
        if (n > 0.0F) {
            cellColor.lerp(DUNGEONTRAIN_NETHER_TINT, DUNGEONTRAIN_NETHER_LIFT * n);
        }
    }

    /** Nether-core band intensity at the camera, {@code 0} outside the overworld or any active core. */
    @Unique
    private static float dungeontrain$netherIntensity() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return 0.0F;
        double camX = mc.gameRenderer.getMainCamera().getPosition().x;
        return (float) ClientNetherBand.netherIntensityAt(camX);
    }
}
