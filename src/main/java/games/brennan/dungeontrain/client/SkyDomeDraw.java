package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * The drawing primitives every custom sky source shares: a solid dome that paints out whatever
 * sky vanilla already drew, and a celestial billboard on one of two orbits.
 *
 * <p>Extracted from {@link UpsideDownSkyRenderer}, which was the first to need them and still
 * behaves identically through this class. {@link NightSkyRenderer} and
 * {@link SunriseSkyRenderer} are the other two callers.</p>
 *
 * <p>Every method leaves the render state as it found it — blend off, depth writes on, shader
 * colour white. The additive blend the bodies use is vanilla's own sun/moon path and
 * <b>must</b> be restored afterwards: {@code sun.png} and {@code moon_phases.png} carry opaque
 * black borders that only vanish when added to the sky rather than composited over it, and
 * leaving that state set would tint whatever draws next.</p>
 */
public final class SkyDomeDraw {

    public static final ResourceLocation SUN =
            ResourceLocation.withDefaultNamespace("textures/environment/sun.png");
    public static final ResourceLocation MOON =
            ResourceLocation.withDefaultNamespace("textures/environment/moon_phases.png");

    /** Half-extent of the dome cube. The bodies sit just inside it so they draw in front. */
    public static final float DOME_HALF_EXTENT = 100.0F;

    /** Billboard distance for a horizon-orbiting body — inside the dome faces. */
    public static final float HORIZON_BODY_DIST = 96.0F;

    private SkyDomeDraw() {}

    /**
     * Six inward-facing quads in the sky's own frame, filling the view with {@code rgb} at
     * {@code alpha}. This is what lets a custom sky replace vanilla's rather than tint it.
     */
    public static void fillDome(Matrix4f frustumMatrix, int rgb, float alpha) {
        int color = argb(alpha, rgb);

        PoseStack pose = new PoseStack();
        pose.mulPose(frustumMatrix);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        Tesselator tesselator = Tesselator.getInstance();

        float e = DOME_HALF_EXTENT;
        for (int i = 0; i < 6; i++) {
            pose.pushPose();
            if (i == 1) pose.mulPose(Axis.XP.rotationDegrees(90.0F));
            if (i == 2) pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
            if (i == 3) pose.mulPose(Axis.XP.rotationDegrees(180.0F));
            if (i == 4) pose.mulPose(Axis.ZP.rotationDegrees(90.0F));
            if (i == 5) pose.mulPose(Axis.ZP.rotationDegrees(-90.0F));

            Matrix4f m = pose.last().pose();
            BufferBuilder bb = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            bb.addVertex(m, -e, -e, -e).setColor(color);
            bb.addVertex(m, -e, -e, e).setColor(color);
            bb.addVertex(m, e, -e, e).setColor(color);
            bb.addVertex(m, e, -e, -e).setColor(color);
            BufferUploader.drawWithShader(bb.buildOrThrow());
            pose.popPose();
        }

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * A body on a <b>horizontal</b> orbit: a vertical billboard straddling the horizon line,
     * swung to {@code azimuthDeg} about the vertical axis. Cull is off so the single quad reads
     * from either side.
     */
    public static void drawHorizonBody(Matrix4f frustumMatrix, ResourceLocation tex, float azimuthDeg,
                                       float size, float alpha, float u0, float v0, float u1, float v1) {
        PoseStack pose = new PoseStack();
        pose.mulPose(frustumMatrix);
        pose.mulPose(Axis.YP.rotationDegrees(azimuthDeg));
        Matrix4f m = pose.last().pose();

        beginBody(tex);
        int color = argb(alpha, 0xFFFFFF);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bb = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.addVertex(m, -size, -size, -HORIZON_BODY_DIST).setUv(u0, v1).setColor(color);
        bb.addVertex(m, -size, size, -HORIZON_BODY_DIST).setUv(u0, v0).setColor(color);
        bb.addVertex(m, size, size, -HORIZON_BODY_DIST).setUv(u1, v0).setColor(color);
        bb.addVertex(m, size, -size, -HORIZON_BODY_DIST).setUv(u1, v1).setColor(color);
        BufferUploader.drawWithShader(bb.buildOrThrow());
        endBody();
    }

    /**
     * A body on vanilla's <b>overhead</b> celestial orbit — the same two rotations
     * {@code LevelRenderer.renderSky} uses, so it rises and sets exactly where the real one
     * would at that {@code timeOfDay}.
     *
     * <p>{@code altitude} picks the slot: {@code +}{@value #DOME_HALF_EXTENT} is the sun's side of
     * the sky, {@code -}{@value #DOME_HALF_EXTENT} the moon's, half a day behind it. The far slot
     * is seen from the quad's other face, which mirrors it — so the {@code u} pair is swapped
     * back there, exactly as vanilla's own moon quad does. Cull is off, so the winding is moot.</p>
     */
    public static void drawCelestialBody(Matrix4f frustumMatrix, ResourceLocation tex, float timeOfDay,
                                         float altitude, float size, float alpha,
                                         float u0, float v0, float u1, float v1) {
        PoseStack pose = celestialPose(frustumMatrix, timeOfDay);
        Matrix4f m = pose.last().pose();
        float uLeft = altitude >= 0.0F ? u0 : u1;
        float uRight = altitude >= 0.0F ? u1 : u0;

        beginBody(tex);
        int color = argb(alpha, 0xFFFFFF);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bb = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.addVertex(m, -size, altitude, -size).setUv(uLeft, v0).setColor(color);
        bb.addVertex(m, size, altitude, -size).setUv(uRight, v0).setColor(color);
        bb.addVertex(m, size, altitude, size).setUv(uRight, v1).setColor(color);
        bb.addVertex(m, -size, altitude, size).setUv(uLeft, v1).setColor(color);
        BufferUploader.drawWithShader(bb.buildOrThrow());
        endBody();
    }

    /**
     * Vanilla's celestial frame for a given time of day: swing the sky a quarter turn, then
     * rotate a full circle over the day. Shared by the overhead body and the star field so the
     * moon always sits among the same stars.
     */
    public static PoseStack celestialPose(Matrix4f frustumMatrix, float timeOfDay) {
        PoseStack pose = new PoseStack();
        pose.mulPose(frustumMatrix);
        pose.mulPose(Axis.YP.rotationDegrees(-90.0F));
        pose.mulPose(Axis.XP.rotationDegrees(timeOfDay * 360.0F));
        return pose;
    }

    /** UVs of one cell of the 4x2 {@code moon_phases.png} atlas, as {@code u0, v0, u1, v1}. */
    public static float[] moonUv(int phase) {
        int px = phase % 4;
        int py = (phase / 4) % 2;
        return new float[] { px / 4.0F, py / 2.0F, (px + 1) / 4.0F, (py + 1) / 2.0F };
    }

    /** Pack an alpha in 0..1 over an RGB triple. Alpha never reaches 0 — a body at 0 is not drawn. */
    public static int argb(float alpha, int rgb) {
        int a = Math.max(1, Math.min(255, Math.round(alpha * 255.0F)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private static void beginBody(ResourceLocation tex) {
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, tex);
    }

    private static void endBody() {
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc(); // restore standard alpha blend so additive state doesn't leak
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
