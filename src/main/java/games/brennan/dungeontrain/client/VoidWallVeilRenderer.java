package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.worldgen.VoidWallLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * The void wall's fading half in the vanilla world: while the camera rides through the first stretch
 * of a void, its far-side wall stops culling and this draws a translucent veil there instead, in the
 * fog colour, thinning as {@link VoidWallLayout.Result#veilStrength} falls — so what lies past the
 * void comes into view gradually rather than all at once.
 *
 * <p>The veil is a vertical sheet across the track at the wall's X, depth-tested against the world so
 * anything nearer than the wall stays in front of it. It has a hole where the track runs, so the rails
 * carry on through it, and it thins towards the top of the world so the open sky over the far side is
 * barely tinted.</p>
 *
 * <p><b>Vanilla range only.</b> Beyond the vanilla render distance the world is Distant Horizons',
 * whose depth this pass cannot see, so there the veil is DH's own box ({@code DistantHorizonsVoidWall})
 * and this one is not drawn.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class VoidWallVeilRenderer {

    /** Rows above the rails before the veil starts thinning out towards the build limit. */
    private static final int FULL_HEIGHT_ABOVE_TRACK = 48;

    private VoidWallVeilRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        VoidWallLayout.Result wall = ClientVoidWall.result();
        if (!wall.hasVeil()) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Vec3 cam = event.getCamera().getPosition();
        double reach = mc.options.getEffectiveRenderDistance() * 16.0;
        if (wall.veilX() - cam.x > reach) return;

        float[] fog = RenderSystem.getShaderFogColor();
        float alpha = (float) wall.veilStrength();

        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = ps.last().pose();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        try {
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            Sheet sheet = new Sheet(b, m, (float) wall.veilX(), fog[0], fog[1], fog[2], alpha);
            int trainY = ClientUpsideDownBand.trainY();
            float z0 = (float) (cam.z - reach);
            float z1 = (float) (cam.z + reach);
            float bottom = level.getMinBuildHeight();
            float top = level.getMaxBuildHeight();
            float holeLo = trainY - 2;
            float holeHi = trainY + 1;
            float fullTop = Math.min(top, holeHi + FULL_HEIGHT_ABOVE_TRACK);
            float holeZ0 = 0;
            float holeZ1 = CarriageDims.DEFAULT_WIDTH;

            sheet.rect(z0, z1, bottom, holeLo, 1f, 1f);                 // under the track
            sheet.rect(z0, holeZ0, holeLo, holeHi, 1f, 1f);             // either side of the hole
            sheet.rect(holeZ1, z1, holeLo, holeHi, 1f, 1f);
            sheet.rect(z0, z1, holeHi, fullTop, 1f, 1f);                // over the track
            sheet.rect(z0, z1, fullTop, top, 1f, 0f);                   // thinning into the sky
            BufferUploader.drawWithShader(b.buildOrThrow());
        } finally {
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            ps.popPose();
        }
    }

    /** A sheet in the plane {@code x}: rectangles over Z and Y, alpha scaled per bottom / top edge. */
    private record Sheet(BufferBuilder b, Matrix4f m, float x, float r, float g, float bl, float alpha) {
        void rect(float z0, float z1, float y0, float y1, float bottomScale, float topScale) {
            if (z1 <= z0 || y1 <= y0) return;
            float a0 = alpha * bottomScale;
            float a1 = alpha * topScale;
            b.addVertex(m, x, y0, z0).setColor(r, g, bl, a0);
            b.addVertex(m, x, y0, z1).setColor(r, g, bl, a0);
            b.addVertex(m, x, y1, z1).setColor(r, g, bl, a1);
            b.addVertex(m, x, y1, z0).setColor(r, g, bl, a1);
        }
    }
}
