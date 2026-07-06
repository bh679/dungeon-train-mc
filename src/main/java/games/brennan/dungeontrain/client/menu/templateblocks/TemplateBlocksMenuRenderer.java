package games.brennan.dungeontrain.client.menu.templateblocks;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.MenuRenderStates;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.TemplateBlocksSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;

/**
 * World-space renderer for {@link TemplateBlocksMenu}. Draws a flat list of
 * blocks used in the plot — each row is {@code [icon] name | count | [Swap]},
 * with count in its own aligned column — anchored at the server-supplied
 * basis, wrapping into columns like the block-variant menu. Mirrors the
 * geometry / helpers of
 * {@link games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuRenderer}.
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class TemplateBlocksMenuRenderer {

    static final RenderType PANEL_QUAD = RenderType.create(
        DungeonTrain.MOD_ID + ":template_blocks_menu_quad",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        256,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(MenuRenderStates.SHADER_POSITION_COLOR)
            .setTransparencyState(MenuRenderStates.TRANSPARENCY_TRANSLUCENT)
            .setCullState(MenuRenderStates.CULL_DISABLED)
            .setDepthTestState(MenuRenderStates.DEPTH_LEQUAL)
            .setWriteMaskState(MenuRenderStates.WRITE_COLOR_ONLY)
            .createCompositeState(false)
    );

    static final double ROW_HEIGHT = 0.30;
    static final double HEADER_HEIGHT = 0.34;
    static final double COLUMN_WIDTH = 2.43;
    static final double MIN_PANEL_WIDTH = 2.6;
    static final double SWAP_CELL_WIDTH = 0.60;
    static final double COUNT_CELL_WIDTH = 0.38;
    static final double CLOSE_CELL_WIDTH = 0.42;
    static final double TEXT_SCALE = 0.012;
    static final double ICON_SIZE = 0.22;
    static final double ICON_LEFT_PAD = 0.04;
    static final double ICON_TEXT_GAP = 0.05;
    static final double NAME_TEXT_LEFT_OFFSET = ICON_LEFT_PAD + ICON_SIZE + ICON_TEXT_GAP;

    private TemplateBlocksMenuRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!TemplateBlocksMenu.isActive()) return;

        TemplateBlocksMenuRaycast.updateHovered();

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        Vec3 anchor = TemplateBlocksMenu.anchorPos();
        Vec3 right = TemplateBlocksMenu.anchorRight();
        Vec3 up = TemplateBlocksMenu.anchorUp();
        Vec3 normal = TemplateBlocksMenu.anchorNormal();

        ps.pushPose();
        ps.translate(anchor.x - cam.x, anchor.y - cam.y, anchor.z - cam.z);
        Matrix3f basis = new Matrix3f(
            (float) right.x, (float) right.y, (float) right.z,
            (float) up.x, (float) up.y, (float) up.z,
            (float) normal.x, (float) normal.y, (float) normal.z
        );
        ps.mulPose(new Quaternionf().setFromNormalized(basis));

        float worldScale = (float) ClientDisplayConfig.getWorldspaceScale();
        if (worldScale != 1.0f) {
            ps.scale(worldScale, worldScale, worldScale);
        }

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        drawPanel(ps, buffer, font);
        buffer.endBatch(PANEL_QUAD);
        buffer.endBatch();
        ps.popPose();
    }

    private static void drawPanel(PoseStack ps, MultiBufferSource buffer, Font font) {
        List<TemplateBlocksSyncPacket.Entry> entries = TemplateBlocksMenu.entries();
        int n = entries.size();
        int colCount = Math.max(1, (n + TemplateBlocksMenu.ROWS_PER_COLUMN - 1) / TemplateBlocksMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(MIN_PANEL_WIDTH, colCount * COLUMN_WIDTH);
        int displayedRows = Math.min(Math.max(n, 1), TemplateBlocksMenu.ROWS_PER_COLUMN);
        double gridH = displayedRows * ROW_HEIGHT;
        double panelH = HEADER_HEIGHT + gridH;
        double halfW = panelW / 2.0;
        double halfH = panelH / 2.0;
        TemplateBlocksMenu.Hit hovered = TemplateBlocksMenu.hovered();

        // Backdrop
        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, 0xC8000000);

        // Header — title + close button.
        double headerTop = halfH;
        double headerBottom = halfH - HEADER_HEIGHT;
        double headerCY = (headerTop + headerBottom) / 2.0;
        drawQuad(ps, buffer, -halfW, headerBottom, halfW, headerTop, 0x40FFEEBB);
        String title = n == 0 ? "No blocks in plot" : "Blocks used  (" + n + ")";
        drawCenteredText(ps, buffer, font, title, -CLOSE_CELL_WIDTH / 2.0, headerCY, 0xFFFFEEBB);

        boolean closeHover = hovered.kind() == TemplateBlocksMenu.CellKind.CLOSE;
        double closeL = halfW - CLOSE_CELL_WIDTH;
        drawQuad(ps, buffer, closeL + 0.01, headerBottom + 0.01, halfW - 0.01, headerTop - 0.01,
            closeHover ? 0xC0FF8080 : 0x40FF6060);
        drawCenteredText(ps, buffer, font, "X", (closeL + halfW) / 2.0, headerCY, 0xFFFFFFFF);

        // Grid
        double gridTop = headerBottom;
        double colActualW = panelW / colCount;
        for (int idx = 0; idx < n; idx++) {
            int col = idx / TemplateBlocksMenu.ROWS_PER_COLUMN;
            int row = idx % TemplateBlocksMenu.ROWS_PER_COLUMN;
            double colXL = -halfW + col * colActualW;
            double rowTop = gridTop - row * ROW_HEIGHT;
            double rowBot = rowTop - ROW_HEIGHT;
            double rowCY = (rowTop + rowBot) / 2.0;

            TemplateBlocksSyncPacket.Entry entry = entries.get(idx);
            boolean rowHover = hovered.index() == idx && hovered.kind() == TemplateBlocksMenu.CellKind.ROW;
            boolean swapHover = hovered.index() == idx && hovered.kind() == TemplateBlocksMenu.CellKind.ROW_SWAP;

            double swapR = colXL + colActualW - 0.02;
            double swapL = swapR - SWAP_CELL_WIDTH;
            double countR = swapL - 0.02;
            double countL = countR - COUNT_CELL_WIDTH;

            // Row body background (preview zone).
            drawQuad(ps, buffer, colXL + 0.01, rowBot + 0.01, countL - 0.01, rowTop - 0.01,
                rowHover ? 0x80FFCC33 : 0x20FFFFFF);
            // Swap button.
            drawQuad(ps, buffer, swapL + 0.01, rowBot + 0.02, swapR, rowTop - 0.02,
                swapHover ? 0xC033FF88 : 0x50339955);

            drawBlockIcon(ps, buffer, entry.blockId(), colXL, rowCY);
            String label = TemplateBlocksMenu.shortLabel(entry.blockId());
            drawLeftText(ps, buffer, font, label, colXL + NAME_TEXT_LEFT_OFFSET, rowCY, 0xFFFFFFFF);
            drawCenteredText(ps, buffer, font, "x" + entry.count(), (countL + countR) / 2.0, rowCY, 0xFFCCCCCC);
            drawCenteredText(ps, buffer, font, "Swap", (swapL + swapR) / 2.0, rowCY, 0xFFEAFFEA);
        }
    }

    /** Inventory-style block icon at the left of a row. BARRIER fallback for item-less blocks. */
    static void drawBlockIcon(PoseStack ps, MultiBufferSource buffer,
                              String blockId, double cellLeftX, double rowCY) {
        if (blockId == null || blockId.isEmpty()) return;
        ItemStack stack = TemplateBlocksMenu.iconStack(blockId);
        Minecraft mc = Minecraft.getInstance();
        ItemRenderer itemRenderer = mc.getItemRenderer();
        ps.pushPose();
        ps.translate(cellLeftX + ICON_LEFT_PAD + ICON_SIZE / 2.0, rowCY, 0.002);
        ps.scale((float) ICON_SIZE, (float) ICON_SIZE, (float) ICON_SIZE);
        itemRenderer.renderStatic(
            stack,
            ItemDisplayContext.GUI,
            LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY,
            ps,
            buffer,
            mc.level,
            0
        );
        ps.popPose();
    }

    static void drawCenteredText(PoseStack ps, MultiBufferSource buffer, Font font,
                                 String text, double worldX, double worldY, int colour) {
        ps.pushPose();
        ps.translate(worldX, worldY, 0.001f);
        float scale = (float) TEXT_SCALE;
        ps.scale(scale, -scale, scale);
        int textWidth = font.width(text);
        float x = -textWidth / 2f;
        float y = -font.lineHeight / 2f;
        Matrix4f mat = ps.last().pose();
        font.drawInBatch(text, x, y, colour, false, mat, buffer,
            Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
        ps.popPose();
    }

    static void drawLeftText(PoseStack ps, MultiBufferSource buffer, Font font,
                             String text, double worldX, double worldY, int colour) {
        ps.pushPose();
        ps.translate(worldX, worldY, 0.001f);
        float scale = (float) TEXT_SCALE;
        ps.scale(scale, -scale, scale);
        float y = -font.lineHeight / 2f;
        Matrix4f mat = ps.last().pose();
        font.drawInBatch(text, 0, y, colour, false, mat, buffer,
            Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
        ps.popPose();
    }

    static void drawQuad(PoseStack ps, MultiBufferSource buffer,
                         double x1, double y1, double x2, double y2, int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        VertexConsumer vc = buffer.getBuffer(PANEL_QUAD);
        Matrix4f mat = ps.last().pose();
        vc.addVertex(mat, (float) x1, (float) y1, (float) 0).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x2, (float) y1, (float) 0).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x2, (float) y2, (float) 0).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x1, (float) y2, (float) 0).setColor(r, g, b, a);
    }
}
