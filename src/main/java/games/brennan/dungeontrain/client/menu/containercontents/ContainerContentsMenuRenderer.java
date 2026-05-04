package games.brennan.dungeontrain.client.menu.containercontents;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.MenuRenderStates;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.ContainerContentsSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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
 * World-space renderer for {@link ContainerContentsMenu}. Mirrors
 * {@link games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuRenderer}
 * with a 3-cell toolbar (Add / Clear / X) and a per-row layout of
 * name + count + weight + remove.
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class ContainerContentsMenuRenderer {

    static final RenderType PANEL_QUAD = RenderType.create(
        DungeonTrain.MOD_ID + ":container_contents_menu_quad",
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
    static final double HEADER_HEIGHT = 0.32;
    static final double TOOLBAR_HEIGHT = 0.32;
    static final double COLUMN_WIDTH = 1.9;
    static final double MIN_PANEL_WIDTH = 2.4;
    static final double X_CELL_WIDTH = 0.30;
    static final double COUNT_CELL_WIDTH = 0.40;
    static final double WEIGHT_CELL_WIDTH = 0.40;
    static final double TEXT_SCALE = 0.012;

    private ContainerContentsMenuRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!ContainerContentsMenu.isActive()) return;
        if (ContainerContentsMenu.localPos() == null) return;

        ContainerContentsMenuRaycast.updateHovered();

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        Vec3 anchor = ContainerContentsMenu.anchorPos();
        Vec3 right = ContainerContentsMenu.anchorRight();
        Vec3 up = ContainerContentsMenu.anchorUp();
        Vec3 normal = ContainerContentsMenu.anchorNormal();

        ps.pushPose();
        ps.translate(anchor.x - cam.x, anchor.y - cam.y, anchor.z - cam.z);
        Matrix3f basis = new Matrix3f(
            (float) right.x, (float) right.y, (float) right.z,
            (float) up.x, (float) up.y, (float) up.z,
            (float) normal.x, (float) normal.y, (float) normal.z
        );
        ps.mulPose(new Quaternionf().setFromNormalized(basis));

        // Same world-space scale used by every other in-world menu —
        // matched on the input side by {@link ContainerContentsMenuRaycast}.
        float worldScale = (float) ClientDisplayConfig.getWorldspaceScale();
        if (worldScale != 1.0f) {
            ps.scale(worldScale, worldScale, worldScale);
        }

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        if (ContainerContentsMenu.screen() == ContainerContentsMenu.Screen.ROOT) {
            drawRoot(ps, buffer, font);
        } else {
            drawSearch(ps, buffer, font);
        }

        buffer.endBatch(PANEL_QUAD);
        ps.popPose();
    }

    private static void drawRoot(PoseStack ps, MultiBufferSource buffer, Font font) {
        List<ContainerContentsSyncPacket.Entry> entries = ContainerContentsMenu.entries();
        int n = entries.size();
        int colCount = Math.max(1, (n + ContainerContentsMenu.ROWS_PER_COLUMN - 1) / ContainerContentsMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(MIN_PANEL_WIDTH, colCount * COLUMN_WIDTH);
        int displayedRows = Math.min(n, ContainerContentsMenu.ROWS_PER_COLUMN);
        if (displayedRows == 0) displayedRows = 1;
        double gridH = displayedRows * ROW_HEIGHT;
        double panelH = HEADER_HEIGHT + TOOLBAR_HEIGHT + gridH;
        double halfW = panelW / 2.0;
        double halfH = panelH / 2.0;
        ContainerContentsMenu.Hit hovered = ContainerContentsMenu.hovered();

        // Backdrop
        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, 0xC8000000);

        // Header
        double headerCY = halfH - HEADER_HEIGHT / 2.0;
        drawQuad(ps, buffer, -halfW, halfH - HEADER_HEIGHT, halfW, halfH, 0x4099CCFF);
        net.minecraft.core.BlockPos local = ContainerContentsMenu.localPos();
        String headerLabel = local == null
            ? "Container Contents"
            : "Container Contents @ " + local.getX() + "," + local.getY() + "," + local.getZ();
        drawCenteredText(ps, buffer, font, headerLabel, 0, headerCY, 0xFF99CCFF);

        // Toolbar — 6 cells: Add | Save | FillMin | FillMax | Clear | X
        double toolbarTop = halfH - HEADER_HEIGHT;
        double toolbarBottom = toolbarTop - TOOLBAR_HEIGHT;
        double toolbarCY = (toolbarTop + toolbarBottom) / 2.0;
        double cellW = panelW / 6.0;
        int fmin = ContainerContentsMenu.fillMin();
        int fmax = ContainerContentsMenu.fillMax();
        int cs = ContainerContentsMenu.containerSize();
        for (int i = 0; i < 6; i++) {
            double xL = -halfW + i * cellW;
            double xR = xL + cellW;
            ContainerContentsMenu.CellKind cellKind = switch (i) {
                case 0 -> ContainerContentsMenu.CellKind.ADD;
                case 1 -> ContainerContentsMenu.CellKind.SAVE;
                case 2 -> ContainerContentsMenu.CellKind.FILL_MIN;
                case 3 -> ContainerContentsMenu.CellKind.FILL_MAX;
                case 4 -> ContainerContentsMenu.CellKind.CLEAR;
                default -> ContainerContentsMenu.CellKind.CLOSE;
            };
            boolean isHover = hovered.kind() == cellKind;
            int tint = switch (cellKind) {
                case CLOSE -> isHover ? 0xC0FF8080 : 0x40FF6060;
                case CLEAR -> isHover ? 0xC0FFAA66 : 0x40CC7733;
                case FILL_MIN, FILL_MAX -> isHover ? 0xC0FFCC55 : 0x4099AA33;
                case SAVE -> isHover ? 0xB033CCFF : 0x40337799;
                default -> isHover ? 0xB066FF99 : 0x4033CC66;
            };
            drawQuad(ps, buffer, xL + 0.01, toolbarBottom + 0.005,
                xR - 0.01, toolbarTop - 0.005, tint);
            String label = switch (cellKind) {
                case ADD -> "Add";
                case SAVE -> "Save";
                case FILL_MIN -> Integer.toString(fmin);
                case FILL_MAX -> {
                    String shown = fmax < 0 ? "all" : Integer.toString(fmax);
                    yield cs > 0 ? shown + "/" + cs : shown;
                }
                case CLEAR -> "Clear";
                case CLOSE -> "X";
                default -> "";
            };
            int colour = isHover ? 0xFF000000 : 0xFFFFFFFF;
            drawCenteredText(ps, buffer, font, label, (xL + xR) / 2.0, toolbarCY, colour);
        }

        // Grid
        double colActualW = panelW / colCount;
        double gridTop = toolbarBottom;
        for (int i = 0; i < n; i++) {
            int col = i / ContainerContentsMenu.ROWS_PER_COLUMN;
            int row = i % ContainerContentsMenu.ROWS_PER_COLUMN;
            double colXL = -halfW + col * colActualW;
            double colXR = colXL + colActualW;
            double rowTop = gridTop - row * ROW_HEIGHT;
            double rowBottom = rowTop - ROW_HEIGHT;
            double rowCY = (rowTop + rowBottom) / 2.0;

            int rowTint = (i % 2 == 0) ? 0x20FFFFFF : 0x10FFFFFF;
            drawQuad(ps, buffer, colXL + 0.01, rowBottom + 0.005,
                colXR - 0.01, rowTop - 0.005, rowTint);

            ContainerContentsSyncPacket.Entry entry = entries.get(i);

            // Right-to-left: [×] [weight] [count] [name]
            double xCellL = colXR - X_CELL_WIDTH;
            double xCellR = colXR;
            double weightR = xCellL;
            double weightL = weightR - WEIGHT_CELL_WIDTH;
            double countR = weightL;
            double countL = countR - COUNT_CELL_WIDTH;
            double nameL = colXL;
            double nameR = countL;

            // Name
            boolean nameHover = hovered.kind() == ContainerContentsMenu.CellKind.ENTRY_NAME && hovered.index() == i;
            if (nameHover) {
                drawQuad(ps, buffer, nameL + 0.01, rowBottom + 0.005,
                    nameR - 0.005, rowTop - 0.005, 0x60FFCC33);
            }
            drawLeftText(ps, buffer, font, shortenItemLabel(entry.itemId()),
                nameL + 0.04, rowCY,
                nameHover ? 0xFF000000 : 0xFFFFFFFF);

            // Count cell
            boolean countHover = hovered.kind() == ContainerContentsMenu.CellKind.ENTRY_COUNT_PLUS && hovered.index() == i;
            int countTint = countHover ? 0xC0FFCC33 : 0x40FFFFFF;
            drawQuad(ps, buffer, countL + 0.005, rowBottom + 0.005,
                countR - 0.005, rowTop - 0.005, countTint);
            drawCenteredText(ps, buffer, font, "x" + entry.count(),
                (countL + countR) / 2.0, rowCY,
                countHover ? 0xFF000000 : 0xFFFFFFFF);

            // Weight cell
            boolean weightHover = hovered.kind() == ContainerContentsMenu.CellKind.ENTRY_WEIGHT_PLUS && hovered.index() == i;
            int weightTint = weightHover ? 0xC0FFCC33 : 0x40FFFFFF;
            drawQuad(ps, buffer, weightL + 0.005, rowBottom + 0.005,
                weightR - 0.005, rowTop - 0.005, weightTint);
            drawCenteredText(ps, buffer, font, "w" + entry.weight(),
                (weightL + weightR) / 2.0, rowCY,
                weightHover ? 0xFF000000 : 0xFFFFFFFF);

            // Remove ×
            boolean xHover = hovered.kind() == ContainerContentsMenu.CellKind.ENTRY_REMOVE_X && hovered.index() == i;
            int xTint = xHover ? 0xC0FF4040 : 0x80AA2020;
            drawQuad(ps, buffer, xCellL + 0.005, rowBottom + 0.005,
                xCellR - 0.005, rowTop - 0.005, xTint);
            drawCenteredText(ps, buffer, font, "X",
                (xCellL + xCellR) / 2.0, rowCY, 0xFFFFFFFF);
        }
    }

    private static void drawSearch(PoseStack ps, MultiBufferSource buffer, Font font) {
        List<String> filtered = ContainerContentsMenu.filteredItemIds();
        int maxRows = ContainerContentsMenu.ROWS_PER_COLUMN * 4;
        int n = Math.min(filtered.size(), maxRows);
        int colCount = Math.max(1, (n + ContainerContentsMenu.ROWS_PER_COLUMN - 1) / ContainerContentsMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(MIN_PANEL_WIDTH, colCount * COLUMN_WIDTH);
        int displayedRows = Math.min(n, ContainerContentsMenu.ROWS_PER_COLUMN);
        if (displayedRows == 0) displayedRows = 1;
        double gridH = displayedRows * ROW_HEIGHT;
        double panelH = HEADER_HEIGHT + TOOLBAR_HEIGHT + gridH;
        double halfW = panelW / 2.0;
        double halfH = panelH / 2.0;
        ContainerContentsMenu.Hit hovered = ContainerContentsMenu.hovered();

        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, 0xC8000000);

        double headerTop = halfH;
        double headerBottom = halfH - HEADER_HEIGHT;
        double headerCY = (headerTop + headerBottom) / 2.0;
        double backCellW = 0.6;
        double backCellL = -halfW;
        double backCellR = backCellL + backCellW;
        boolean backHover = hovered.kind() == ContainerContentsMenu.CellKind.SEARCH_BACK;
        int backTint = backHover ? 0xC0FFCC33 : 0x6099CCFF;
        drawQuad(ps, buffer, backCellL + 0.01, headerBottom + 0.005,
            backCellR - 0.005, headerTop - 0.005, backTint);
        drawCenteredText(ps, buffer, font, "< Back",
            (backCellL + backCellR) / 2.0, headerCY,
            backHover ? 0xFF000000 : 0xFFFFFFFF);
        drawQuad(ps, buffer, backCellR + 0.005, headerBottom + 0.005,
            halfW, headerTop - 0.005, 0x4099CCFF);
        drawCenteredText(ps, buffer, font, "Add Item",
            (backCellR + halfW) / 2.0, headerCY, 0xFF99CCFF);

        double searchTop = halfH - HEADER_HEIGHT;
        double searchBottom = searchTop - TOOLBAR_HEIGHT;
        double searchCY = (searchTop + searchBottom) / 2.0;
        boolean searchHover = hovered.kind() == ContainerContentsMenu.CellKind.SEARCH_FIELD;
        int searchTint = searchHover ? 0xB033FF99 : 0x60339966;
        drawQuad(ps, buffer, -halfW + 0.02, searchBottom + 0.01,
            halfW - 0.02, searchTop - 0.01, searchTint);
        String shown = "Search: " + ContainerContentsMenu.searchBuffer() + "_";
        drawLeftText(ps, buffer, font, shown, -halfW + 0.06, searchCY, 0xFFFFFFFF);

        double colActualW = panelW / colCount;
        double gridTop = searchBottom;
        for (int i = 0; i < n; i++) {
            int col = i / ContainerContentsMenu.ROWS_PER_COLUMN;
            int row = i % ContainerContentsMenu.ROWS_PER_COLUMN;
            double colXL = -halfW + col * colActualW;
            double colXR = colXL + colActualW;
            double rowTop = gridTop - row * ROW_HEIGHT;
            double rowBottom = rowTop - ROW_HEIGHT;
            double rowCY = (rowTop + rowBottom) / 2.0;
            boolean isHover = hovered.kind() == ContainerContentsMenu.CellKind.SEARCH_RESULT && hovered.index() == i;
            int tint = isHover ? 0xC0FFCC33 : (i % 2 == 0 ? 0x30FFFFFF : 0x18FFFFFF);
            drawQuad(ps, buffer, colXL + 0.01, rowBottom + 0.005,
                colXR - 0.01, rowTop - 0.005, tint);
            int textColour = isHover ? 0xFF000000 : 0xFFFFFFFF;
            drawLeftText(ps, buffer, font, filtered.get(i), colXL + 0.04, rowCY, textColour);
        }
    }

    /** Drop {@code modid:} prefix for the row label. */
    static String shortenItemLabel(String itemId) {
        if (itemId == null) return "";
        int colon = itemId.indexOf(':');
        return colon >= 0 ? itemId.substring(colon + 1) : itemId;
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
        vc.addVertex(mat, (float) x1, (float) y1, 0).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x2, (float) y1, 0).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x2, (float) y2, 0).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x1, (float) y2, 0).setColor(r, g, b, a);
    }
}
