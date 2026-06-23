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
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;

/**
 * Paints out the overworld sky over the Nether transition band, the Nether counterpart to
 * {@link VoidSkyRenderer}. The real Nether has <i>no</i> sky ({@code SkyType.NONE}) — you just see
 * fog in every direction — so where the End band overlays a starfield texture, this fills the sky
 * dome with a solid {@link NetherFogEvents#netherTargetColor Nether fog colour} at opacity
 * {@code n} (the nether-band intensity). As the player enters the core the overworld sun/moon/
 * stars/blue gradient fade out under the fill and back as they leave — a clean crossfade in
 * lockstep with the fog/lighting, no pop.
 *
 * <p>Invoked from a TAIL mixin on {@code LevelRenderer.renderSky} (shared with
 * {@link VoidSkyRenderer}) — i.e. <i>after</i> vanilla has drawn the overworld sky, with the exact
 * sky matrices — so this is an overlay, not a replacement. Replicates vanilla's 6-face sky box but
 * with a solid {@link DefaultVertexFormat#POSITION_COLOR} fill and a per-vertex alpha driven by
 * {@code n}. End and Nether bands never overlap in world-X (see {@code LightTextureNetherBandMixin}),
 * so this and the End overlay never both paint.</p>
 */
public final class NetherSkyRenderer {

    private NetherSkyRenderer() {}

    /** Overlay the Nether sky fill if the camera is inside the overworld Nether band. */
    public static void renderOverlay(Matrix4f frustumMatrix, Camera camera, boolean isFoggy) {
        if (isFoggy) return; // underwater / blindness — vanilla skipped the sky; skip ours too
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        double n = ClientNetherBand.netherIntensityAt(camera.getPosition().x);
        if (n <= 0.0) return;
        int rgb = NetherFogEvents.netherTargetColor(mc.level, camera.getBlockPosition());
        draw(frustumMatrix, (float) Math.min(1.0, n), rgb);
    }

    private static void draw(Matrix4f frustumMatrix, float alpha, int rgb) {
        int a = Math.max(1, Math.min(255, Math.round(alpha * 255.0F)));
        int color = (a << 24) | (rgb & 0xFFFFFF);

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
}
