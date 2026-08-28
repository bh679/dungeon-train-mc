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
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * A sky held permanently at dawn, for the {@code skybox_sunrise} block.
 *
 * <p>Rotates <b>sideways</b>, like {@link UpsideDownSkyRenderer}: instead of climbing overhead,
 * the sun sweeps a full 360° around the horizon over each day, with the moon trailing 180°
 * opposite and the stars turning with them. Because the sun never leaves the horizon line it
 * never stops rising — the dawn glow just travels around the viewer.</p>
 *
 * <p>Cosmetic and client-side; the engine's real skylight is unchanged.</p>
 */
public final class SunriseSkyRenderer {

    /** Dusky pre-dawn blue. The warmth comes from the glow, not the fill, so the sky stays deep. */
    public static final int SKY_RGB = 0x35406B;

    /** Sunrise glow colour, blended additively — vanilla's dawn is this kind of amber. */
    private static final int GLOW_RGB = 0xFF9846;

    private static final float SUN_SIZE = 30.0F;
    private static final float MOON_SIZE = 20.0F;
    /** Radius of the glow disc around the sun. Several times the sun so the falloff is soft. */
    private static final float GLOW_RADIUS = 110.0F;
    private static final float GLOW_ALPHA = 0.55F;
    /** Segments in the glow fan. 24 is smooth at any FOV and still a single trivial draw. */
    private static final int GLOW_SEGMENTS = 24;
    /**
     * Stars are faint rather than absent: this is a dawn sky, and at full brightness they would
     * fight the glow instead of sitting behind it.
     */
    private static final float STAR_ALPHA = 0.4F;
    /** Just behind the sun's own billboard, so the disc always sits on top of its glow. */
    private static final float GLOW_DIST = SkyDomeDraw.HORIZON_BODY_DIST + 1.0F;

    private SunriseSkyRenderer() {}

    /**
     * Draw the sunrise sky unconditionally at full opacity, as a <em>sky source</em> for a skybox
     * block — see {@link VoidSkyRenderer#renderAsSkySource}.
     */
    public static void renderAsSkySource(Matrix4f frustumMatrix, float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        SkyDomeDraw.fillDome(frustumMatrix, SKY_RGB, 1.0F);

        float azimuth = level.getTimeOfDay(partialTick) * 360.0F;

        // Stars first, in the same horizontal frame, so they sweep with the bodies rather than
        // sitting still behind a moving sun.
        PoseStack pose = new PoseStack();
        pose.mulPose(frustumMatrix);
        pose.mulPose(Axis.YP.rotationDegrees(azimuth));
        StarFieldRenderer.draw(pose.last().pose(), STAR_ALPHA);

        drawGlow(frustumMatrix, azimuth);
        SkyDomeDraw.drawHorizonBody(frustumMatrix, SkyDomeDraw.SUN, azimuth, SUN_SIZE, 1.0F,
                0.0F, 0.0F, 1.0F, 1.0F);

        float[] uv = SkyDomeDraw.moonUv(level.getMoonPhase());
        SkyDomeDraw.drawHorizonBody(frustumMatrix, SkyDomeDraw.MOON, azimuth + 180.0F, MOON_SIZE, 1.0F,
                uv[0], uv[1], uv[2], uv[3]);
    }

    /**
     * The dawn bloom: a triangle fan centred on the sun, bright at the centre and fading to fully
     * transparent at the rim, added to the sky beneath it. Untextured — a vertex-colour gradient
     * is what gives a smooth falloff without an asset.
     */
    private static void drawGlow(Matrix4f frustumMatrix, float azimuthDeg) {
        PoseStack pose = new PoseStack();
        pose.mulPose(frustumMatrix);
        pose.mulPose(Axis.YP.rotationDegrees(azimuthDeg));
        Matrix4f m = pose.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        int centre = SkyDomeDraw.argb(GLOW_ALPHA, GLOW_RGB);
        int rim = GLOW_RGB & 0xFFFFFF; // alpha 0
        BufferBuilder bb = Tesselator.getInstance()
                .begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(m, 0.0F, 0.0F, -GLOW_DIST).setColor(centre);
        for (int i = 0; i <= GLOW_SEGMENTS; i++) {
            double angle = i * 2.0 * Math.PI / GLOW_SEGMENTS;
            float x = (float) Math.cos(angle) * GLOW_RADIUS;
            float y = (float) Math.sin(angle) * GLOW_RADIUS;
            bb.addVertex(m, x, y, -GLOW_DIST).setColor(rim);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc(); // restore standard alpha blend so additive state doesn't leak
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
