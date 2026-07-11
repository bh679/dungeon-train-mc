package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
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
 * <p>Cosmetic and per-player (each client evaluates the band at its own camera-X); no server state.
 * The engine's real skylight is unchanged — see {@link LightTextureUpsideDownBandMixin} /
 * {@code LevelGetShadeMixin} for the (static) bright, side-lit terrain treatment.</p>
 */
public final class UpsideDownSkyRenderer {

    private static final ResourceLocation SUN =
            ResourceLocation.withDefaultNamespace("textures/environment/sun.png");
    private static final ResourceLocation MOON =
            ResourceLocation.withDefaultNamespace("textures/environment/moon_phases.png");

    /** Day-sky fill colour that paints out vanilla's overhead sky (shared with the band fog tint). */
    public static final int SKY_RGB = 0x84B4E8;

    private static final float SUN_SIZE = 30.0F;
    private static final float MOON_SIZE = 20.0F;
    /** Height of the celestial bodies' orbit above the true horizon (kept low — "from the sides"). */
    private static final float HORIZON_ALT = 18.0F;
    /** Billboard distance — just inside the dome faces (±100) so the bodies draw in front of the fill. */
    private static final float BODY_DIST = 96.0F;

    private UpsideDownSkyRenderer() {}

    /** Overlay the rotating sky if the camera is inside the overworld upside-down band. */
    public static void renderOverlay(Matrix4f frustumMatrix, Camera camera, float partialTick, boolean isFoggy) {
        if (isFoggy) return; // underwater / blindness — vanilla skipped the sky; skip ours too
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        double t = ClientUpsideDownBand.upsideDownIntensityAt(camera.getPosition().x);
        if (t <= 0.0) return;
        float alpha = (float) Math.min(1.0, t);

        fillDome(frustumMatrix, alpha);

        // Sun + moon on a horizontal orbit: azimuth sweeps 0→360° over the day, staying near the horizon.
        float azimuth = mc.level.getTimeOfDay(partialTick) * 360.0F;
        drawBody(frustumMatrix, SUN, azimuth, SUN_SIZE, alpha, 0.0F, 0.0F, 1.0F, 1.0F);

        int phase = mc.level.getMoonPhase();
        int px = phase % 4;
        int py = (phase / 4) % 2;
        drawBody(frustumMatrix, MOON, azimuth + 180.0F, MOON_SIZE, alpha,
                px / 4.0F, py / 2.0F, (px + 1) / 4.0F, (py + 1) / 2.0F);
    }

    /** Solid day-sky box that paints out the vanilla sky (same 6-face technique as the other overlays). */
    private static void fillDome(Matrix4f frustumMatrix, float alpha) {
        int a = Math.max(1, Math.min(255, Math.round(alpha * 255.0F)));
        int color = (a << 24) | (SKY_RGB & 0xFFFFFF);

        PoseStack pose = new PoseStack();
        pose.mulPose(frustumMatrix);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        Tesselator tesselator = Tesselator.getInstance();

        for (int i = 0; i < 6; i++) {
            pose.pushPose();
            if (i == 1) pose.mulPose(Axis.XP.rotationDegrees(90.0F));
            if (i == 2) pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
            if (i == 3) pose.mulPose(Axis.XP.rotationDegrees(180.0F));
            if (i == 4) pose.mulPose(Axis.ZP.rotationDegrees(90.0F));
            if (i == 5) pose.mulPose(Axis.ZP.rotationDegrees(-90.0F));

            Matrix4f m = pose.last().pose();
            BufferBuilder bb = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            bb.addVertex(m, -100.0F, -100.0F, -100.0F).setColor(color);
            bb.addVertex(m, -100.0F, -100.0F, 100.0F).setColor(color);
            bb.addVertex(m, 100.0F, -100.0F, 100.0F).setColor(color);
            bb.addVertex(m, 100.0F, -100.0F, -100.0F).setColor(color);
            BufferUploader.drawWithShader(bb.buildOrThrow());
            pose.popPose();
        }

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Draw a celestial body as a vertical billboard on the horizon, rotated to {@code azimuthDeg}
     * around the vertical axis. Cull is disabled so the single quad is visible from either side.
     */
    private static void drawBody(Matrix4f frustumMatrix, ResourceLocation tex, float azimuthDeg, float size,
                                 float alpha, float u0, float v0, float u1, float v1) {
        int a = Math.max(1, Math.min(255, Math.round(alpha * 255.0F)));
        int color = (a << 24) | 0xFFFFFF;

        PoseStack pose = new PoseStack();
        pose.mulPose(frustumMatrix);
        pose.mulPose(Axis.YP.rotationDegrees(azimuthDeg));
        Matrix4f m = pose.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, tex);

        float lo = HORIZON_ALT - size;
        float hi = HORIZON_ALT + size;
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bb = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.addVertex(m, -size, lo, -BODY_DIST).setUv(u0, v1).setColor(color);
        bb.addVertex(m, -size, hi, -BODY_DIST).setUv(u0, v0).setColor(color);
        bb.addVertex(m, size, hi, -BODY_DIST).setUv(u1, v0).setColor(color);
        bb.addVertex(m, size, lo, -BODY_DIST).setUv(u1, v1).setColor(color);
        BufferUploader.drawWithShader(bb.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
