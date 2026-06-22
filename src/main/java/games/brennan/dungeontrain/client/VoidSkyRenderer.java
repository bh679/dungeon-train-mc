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
 * Draws the End skybox over the normal overworld sky at opacity {@code t} (the
 * void-band intensity), so the sky crossfades to the End's starfield as the player
 * enters the disintegration band and back as they leave. Invoked from a TAIL mixin
 * on {@code LevelRenderer.renderSky} — i.e. <i>after</i> vanilla has drawn the
 * overworld sky/sun/stars, with the exact sky matrices — so this is a clean overlay,
 * not a replacement (no sky "pop" at the band edge).
 *
 * <p>Replicates vanilla {@code LevelRenderer.renderEndSky} (6 textured faces of the
 * {@code end_sky.png} box) but with a per-vertex alpha driven by {@code t}.</p>
 */
public final class VoidSkyRenderer {

    private static final ResourceLocation END_SKY =
            ResourceLocation.withDefaultNamespace("textures/environment/end_sky.png");

    /** Vanilla end-sky face tint (RGB of {@code -14145496}), alpha supplied per-frame. */
    private static final int END_SKY_RGB = 0x282828;

    private VoidSkyRenderer() {}

    /** Overlay the End sky if the camera is inside the overworld void band. */
    public static void renderOverlay(Matrix4f frustumMatrix, Camera camera, boolean isFoggy) {
        if (isFoggy) return; // underwater / blindness — vanilla skipped the sky; skip ours too
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        double t = ClientVoidBand.voidAt(camera.getPosition().x);
        if (t <= 0.0) return;
        draw(frustumMatrix, (float) Math.min(1.0, t));
    }

    private static void draw(Matrix4f frustumMatrix, float alpha) {
        int a = Math.max(1, Math.min(255, Math.round(alpha * 255.0F)));
        int color = (a << 24) | END_SKY_RGB;

        PoseStack pose = new PoseStack();
        pose.mulPose(frustumMatrix);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, END_SKY);
        Tesselator tesselator = Tesselator.getInstance();

        for (int i = 0; i < 6; i++) {
            pose.pushPose();
            if (i == 1) pose.mulPose(Axis.XP.rotationDegrees(90.0F));
            if (i == 2) pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
            if (i == 3) pose.mulPose(Axis.XP.rotationDegrees(180.0F));
            if (i == 4) pose.mulPose(Axis.ZP.rotationDegrees(90.0F));
            if (i == 5) pose.mulPose(Axis.ZP.rotationDegrees(-90.0F));

            Matrix4f m = pose.last().pose();
            BufferBuilder bb = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            bb.addVertex(m, -100.0F, -100.0F, -100.0F).setUv(0.0F, 0.0F).setColor(color);
            bb.addVertex(m, -100.0F, -100.0F, 100.0F).setUv(0.0F, 16.0F).setColor(color);
            bb.addVertex(m, 100.0F, -100.0F, 100.0F).setUv(16.0F, 16.0F).setColor(color);
            bb.addVertex(m, 100.0F, -100.0F, -100.0F).setUv(16.0F, 0.0F).setColor(color);
            BufferUploader.drawWithShader(bb.buildOrThrow());
            pose.popPose();
        }

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
