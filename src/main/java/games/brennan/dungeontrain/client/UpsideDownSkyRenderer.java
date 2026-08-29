package games.brennan.dungeontrain.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;

/**
 * Draws the upside-down band's sky over the normal overworld sky at opacity {@code t} (the band
 * intensity), the upside-down counterpart to {@link VoidSkyRenderer} / {@link NetherSkyRenderer}.
 * Instead of the sun/moon crossing overhead, they <b>orbit horizontally around the vertical Y axis</b>
 * — a full 360° sweep low near the horizon over each day — so the light source is always at the side
 * and its direction rotates. Invoked from a TAIL mixin on {@code LevelRenderer.renderSky} (shared with
 * the End/Nether overlays), so vanilla has already drawn the overworld sky; this fills the dome with a
 * day-sky colour to paint out vanilla's overhead sun/gradient, then draws its own horizon sun + moon
 * on top. The three bands never overlap in world-X, so at most one overlay paints.
 *
 * <p>The dome fill and the horizon billboards live in {@link SkyDomeDraw}, shared with
 * {@link SunriseSkyRenderer} (same sideways orbit, dawn palette) and {@link NightSkyRenderer}.</p>
 *
 * <p>Cosmetic and per-player (each client evaluates the band at its own camera-X); no server state.
 * The engine's real skylight is unchanged — see {@link LightTextureUpsideDownBandMixin} /
 * {@code LevelGetShadeMixin} for the (static) bright, side-lit terrain treatment.</p>
 */
public final class UpsideDownSkyRenderer {

    /** Day-sky fill colour that paints out vanilla's overhead sky (shared with the band fog tint). */
    public static final int SKY_RGB = 0x84B4E8;

    private static final float SUN_SIZE = 30.0F;
    private static final float MOON_SIZE = 20.0F;

    private UpsideDownSkyRenderer() {}

    /** Overlay the rotating sky if the camera is inside the overworld upside-down band. */
    public static void renderOverlay(Matrix4f frustumMatrix, Camera camera, float partialTick, boolean isFoggy) {
        if (isFoggy) return; // underwater / blindness — vanilla skipped the sky; skip ours too
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        double t = ClientUpsideDownBand.upsideDownIntensityAt(camera.getPosition().x);
        if (t <= 0.0) return;
        drawAll(frustumMatrix, partialTick, (float) Math.min(1.0, t));
    }

    /**
     * Draw the upside-down sky unconditionally at full opacity, as a <em>sky source</em> for a
     * skybox block rather than a band overlay.
     *
     * <p>Deliberately bypasses {@link #renderOverlay}, which returns early on zero band
     * intensity — see {@link VoidSkyRenderer#renderAsSkySource}.</p>
     */
    public static void renderAsSkySource(Matrix4f frustumMatrix, float partialTick) {
        if (Minecraft.getInstance().level == null) return;
        drawAll(frustumMatrix, partialTick, 1.0F);
    }

    /** Dome fill plus the horizon-orbiting sun and moon, at the given opacity. */
    private static void drawAll(Matrix4f frustumMatrix, float partialTick, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        SkyDomeDraw.fillDome(frustumMatrix, SKY_RGB, alpha);

        // Sun + moon on a horizontal orbit: azimuth sweeps 0→360° over the day, staying near the horizon.
        float azimuth = mc.level.getTimeOfDay(partialTick) * 360.0F;
        SkyDomeDraw.drawHorizonBody(frustumMatrix, SkyDomeDraw.SUN, azimuth, SUN_SIZE, alpha,
                0.0F, 0.0F, 1.0F, 1.0F);

        float[] uv = SkyDomeDraw.moonUv(mc.level.getMoonPhase());
        SkyDomeDraw.drawHorizonBody(frustumMatrix, SkyDomeDraw.MOON, azimuth + 180.0F, MOON_SIZE, alpha,
                uv[0], uv[1], uv[2], uv[3]);
    }
}
