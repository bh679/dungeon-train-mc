package games.brennan.dungeontrain.client.menu.creakingheart;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.ClientStages;
import games.brennan.dungeontrain.client.menu.MenuRenderStates;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Draws the creaking heart panel ({@link CreakingHeartPanelLayout}) on the heart's clicked face,
 * in the Z menu's style: translucent backdrop, tinted cells, yellow hover with black text.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CreakingHeartPanelRenderer {

    private static final RenderType PANEL_QUAD = MenuRenderStates.translucentQuad(DungeonTrain.MOD_ID + ":creaking_heart_quad");
    private static final double TEXT_INSET = 0.04;

    private CreakingHeartPanelRenderer() {}

    /** This frame's layout, also used by the raycast. */
    static CreakingHeartPanelLayout.Layout layout() {
        return CreakingHeartPanelLayout.build(CreakingHeartPanel.settings(), CreakingHeartPanel.popup(), ClientStages.all());
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!CreakingHeartPanel.isActive()) return;
        if (!games.brennan.dungeontrain.compat.vista.VistaRenderPass.isNestedRender()) {
            CreakingHeartPanelRaycast.updateHovered();
        }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        Vec3 anchor = CreakingHeartPanel.anchor();
        Vec3 right = CreakingHeartPanel.right();
        Vec3 up = CreakingHeartPanel.up();
        Vec3 normal = CreakingHeartPanel.normal();

        ps.pushPose();
        ps.translate(anchor.x - cam.x, anchor.y - cam.y, anchor.z - cam.z);
        ps.mulPose(new Quaternionf().setFromNormalized(new Matrix3f(
            (float) right.x, (float) right.y, (float) right.z,
            (float) up.x, (float) up.y, (float) up.z,
            (float) normal.x, (float) normal.y, (float) normal.z)));
        float worldScale = (float) ClientDisplayConfig.getWorldspaceScale();
        if (worldScale != 1.0f) ps.scale(worldScale, worldScale, worldScale);

        draw(ps, buffer, font, layout(), CreakingHeartPanel.hovered());

        ps.popPose();
        buffer.endBatch(PANEL_QUAD);
        buffer.endBatch();
    }

    private static void draw(PoseStack ps, MultiBufferSource buffer, Font font,
                             CreakingHeartPanelLayout.Layout layout, CreakingHeartPanelLayout.Hit hovered) {
        drawQuad(ps, buffer, layout.x1(), layout.y1(), layout.x2(), layout.y2(), CreakingHeartPanelLayout.BACKDROP);
        if (layout.hasPopup()) {
            drawQuad(ps, buffer, layout.x1(), layout.popupY1(), layout.x2(), layout.popupY2(),
                CreakingHeartPanelLayout.POPUP_BACKDROP);
        }
        for (CreakingHeartPanelLayout.Cell c : layout.cells()) {
            boolean hot = c.kind() != CreakingHeartPanelLayout.Kind.NONE
                && c.kind() == hovered.kind() && c.index() == hovered.index();
            int bg = hot ? c.bgHover() : c.bg();
            if ((bg >>> 24) != 0) {
                drawQuad(ps, buffer, c.x1() + 0.005, c.y1() + 0.005, c.x2() - 0.005, c.y2() - 0.005, bg);
            }
            int fg = hot ? CreakingHeartPanelLayout.TEXT_ON_HOVER : c.fg();
            double ty = (c.y1() + c.y2()) / 2;
            double tx = c.centered() ? (c.x1() + c.x2()) / 2 : c.x1() + TEXT_INSET;
            drawText(ps, buffer, font, c.text(), tx, ty, fg, c.centered());
        }
    }

    private static void drawText(PoseStack ps, MultiBufferSource buffer, Font font,
                                 String text, double x, double y, int colour, boolean centered) {
        ps.pushPose();
        ps.translate(x, y, 0.002f);
        float scale = (float) CreakingHeartPanelLayout.TEXT_SCALE;
        ps.scale(scale, -scale, scale);
        float dx = centered ? -font.width(text) / 2f : 0f;
        Matrix4f mat = ps.last().pose();
        font.drawInBatch(text, dx, -font.lineHeight / 2f, colour, false, mat, buffer,
            Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
        ps.popPose();
    }

    private static void drawQuad(PoseStack ps, MultiBufferSource buffer,
                                 double x1, double y1, double x2, double y2, int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        VertexConsumer vc = buffer.getBuffer(PANEL_QUAD);
        Matrix4f mat = ps.last().pose();
        vc.addVertex(mat, (float) x1, (float) y1, 0).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x2, (float) y1, 0).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x2, (float) y2, 0).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x1, (float) y2, 0).setColor(r, g, b, a);
    }
}
