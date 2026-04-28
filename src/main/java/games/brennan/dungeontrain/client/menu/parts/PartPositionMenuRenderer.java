package games.brennan.dungeontrain.client.menu.parts;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.MenuRenderStates;
import games.brennan.dungeontrain.train.CarriagePartAssignment.WeightedName;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;
import java.util.Locale;

/**
 * World-space renderer for {@link PartPositionMenu}.
 *
 * <p>Drawn during {@link RenderLevelStageEvent.Stage#AFTER_TRANSLUCENT_BLOCKS}
 * the same way {@link games.brennan.dungeontrain.client.menu.CommandMenuRenderer}
 * draws the keyboard-opened menu — billboarded panel with alpha-blended
 * quads, see-through text, full-bright lighting.</p>
 *
 * <p>Layout (panel-local coordinates, panel centred on the part):
 * <ul>
 *   <li>Header row at the top: {@code "Floor"} / {@code "Walls"} / etc.</li>
 *   <li>Toolbar row: {@code Add | Remove | Clear | X}.</li>
 *   <li>Grid of {@code name | weight} rows, with an {@code [X]} chip column
 *       drawn only in remove-mode. Wraps to a new column every
 *       {@link PartPositionMenu#ROWS_PER_COLUMN} entries.</li>
 * </ul></p>
 */
@Mod.EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = Dist.CLIENT
)
public final class PartPositionMenuRenderer {

    /** Same composite as CommandMenuRenderer's PANEL_QUAD — alpha-blended position+colour, depth test, no depth write. */
    static final RenderType PANEL_QUAD = RenderType.create(
        DungeonTrain.MOD_ID + ":part_menu_quad",
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
    /** Width of one grid column (a name + weight cell pair, plus the optional [X] chip in remove-mode). */
    static final double COLUMN_WIDTH = 1.6;
    /** Minimum panel width — guarantees the four-cell toolbar can fit "Remove" / "Cancel" / "Clear" labels without text overlap. */
    static final double MIN_PANEL_WIDTH = 2.4;
    /** Width of the X chip on the right of each entry row when remove-mode is on. */
    static final double X_CELL_WIDTH = 0.30;
    /** Width of the weight cell on the right side of each entry row. */
    static final double WEIGHT_CELL_WIDTH = 0.40;
    /** Width of the side-mode cell (walls/doors only) — fits "(1|2)" with padding. */
    static final double SIDE_MODE_CELL_WIDTH = 0.50;
    /** Text scale matches CommandMenuRenderer's. */
    static final double TEXT_SCALE = 0.012;

    private PartPositionMenuRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!PartPositionMenu.isActive()) return;
        if (CommandMenuState.isOpen()) return;
        CarriagePartKind kind = PartPositionMenu.kind();
        if (kind == null) return;

        // Refresh hover from the current camera frame so the highlight
        // tracks the crosshair without lagging by a tick.
        PartPositionMenuRaycast.updateHovered();

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        Vec3 anchor = PartPositionMenu.anchorPos();
        Vec3 right = PartPositionMenu.anchorRight();
        Vec3 up = PartPositionMenu.anchorUp();
        Vec3 normal = PartPositionMenu.anchorNormal();

        ps.pushPose();
        ps.translate(anchor.x - cam.x, anchor.y - cam.y, anchor.z - cam.z);
        Matrix3f basis = new Matrix3f(
            (float) right.x, (float) right.y, (float) right.z,
            (float) up.x, (float) up.y, (float) up.z,
            (float) normal.x, (float) normal.y, (float) normal.z
        );
        ps.mulPose(new Quaternionf().setFromNormalized(basis));

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        if (PartPositionMenu.screen() == PartPositionMenu.Screen.ROOT) {
            drawRoot(ps, buffer, font, kind);
        } else {
            drawSearch(ps, buffer, font, kind);
        }

        buffer.endBatch(PANEL_QUAD);
        ps.popPose();
    }

    private static void drawRoot(PoseStack ps, MultiBufferSource buffer, Font font, CarriagePartKind kind) {
        List<WeightedName> entries = PartPositionMenu.entries();
        int n = entries.size();
        int colCount = Math.max(1, (n + PartPositionMenu.ROWS_PER_COLUMN - 1) / PartPositionMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(MIN_PANEL_WIDTH, colCount * COLUMN_WIDTH);
        // Show only the rows we actually have (capped at ROWS_PER_COLUMN);
        // avoids reserving a full 10-row column when the assignment is small.
        int displayedRows = Math.min(n, PartPositionMenu.ROWS_PER_COLUMN);
        if (displayedRows == 0) displayedRows = 1;
        double gridH = displayedRows * ROW_HEIGHT;
        double panelH = HEADER_HEIGHT + TOOLBAR_HEIGHT + gridH;
        double halfW = panelW / 2.0;
        double halfH = panelH / 2.0;
        boolean removeMode = PartPositionMenu.removeMode();
        PartPositionMenu.Hit hovered = PartPositionMenu.hovered();

        // Backdrop
        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, 0xC8000000);

        // Header
        double headerCY = halfH - HEADER_HEIGHT / 2.0;
        drawQuad(ps, buffer, -halfW, halfH - HEADER_HEIGHT, halfW, halfH, 0x40FFEEBB);
        drawCenteredText(ps, buffer, font, capitalize(kind.id()), 0, headerCY, 0xFFFFEEBB);

        // Toolbar — Add | Remove | Clear | X (close)
        double toolbarTop = halfH - HEADER_HEIGHT;
        double toolbarBottom = toolbarTop - TOOLBAR_HEIGHT;
        double toolbarCY = (toolbarTop + toolbarBottom) / 2.0;
        double cellW = panelW / 4.0;
        for (int i = 0; i < 4; i++) {
            double xL = -halfW + i * cellW;
            double xR = xL + cellW;
            PartPositionMenu.CellKind cellKind = switch (i) {
                case 0 -> PartPositionMenu.CellKind.ADD;
                case 1 -> PartPositionMenu.CellKind.REMOVE;
                case 2 -> PartPositionMenu.CellKind.CLEAR;
                default -> PartPositionMenu.CellKind.CLOSE;
            };
            boolean isHover = hovered.kind() == cellKind;
            int tint;
            if (cellKind == PartPositionMenu.CellKind.REMOVE && removeMode) {
                tint = isHover ? 0xC0FF6666 : 0x80AA4040;
            } else if (cellKind == PartPositionMenu.CellKind.CLOSE) {
                tint = isHover ? 0xC0FF8080 : 0x40FF6060;
            } else {
                tint = isHover ? 0xB0FFCC33 : 0x30FFFFFF;
            }
            drawQuad(ps, buffer, xL + 0.01, toolbarBottom + 0.005,
                xR - 0.01, toolbarTop - 0.005, tint);
            String label = switch (cellKind) {
                case ADD -> "Add";
                case REMOVE -> removeMode ? "Cancel" : "Remove";
                case CLEAR -> "Clear";
                case CLOSE -> "X";
                default -> "";
            };
            int colour = isHover ? 0xFF000000 : 0xFFFFFFFF;
            drawCenteredText(ps, buffer, font, label, (xL + xR) / 2.0, toolbarCY, colour);
        }

        // Grid — let single-column panels stretch to MIN_PANEL_WIDTH so the
        // entry row matches the toolbar above it.
        double colActualW = panelW / colCount;
        boolean showSideMode = PartPositionMenu.kindHasSideMode(kind);
        double gridTop = toolbarBottom;
        for (int i = 0; i < n; i++) {
            int col = i / PartPositionMenu.ROWS_PER_COLUMN;
            int row = i % PartPositionMenu.ROWS_PER_COLUMN;
            double colXL = -halfW + col * colActualW;
            double colXR = colXL + colActualW;
            double rowTop = gridTop - row * ROW_HEIGHT;
            double rowBottom = rowTop - ROW_HEIGHT;
            double rowCY = (rowTop + rowBottom) / 2.0;

            // Backdrop for the row
            int rowTint = (i % 2 == 0) ? 0x20FFFFFF : 0x10FFFFFF;
            drawQuad(ps, buffer, colXL + 0.01, rowBottom + 0.005,
                colXR - 0.01, rowTop - 0.005, rowTint);

            WeightedName entry = entries.get(i);

            // Layout cells, right-to-left:
            //   [X] (rightmost, in remove-mode only)
            //   side-mode cell (only for walls/doors)
            //   weight cell
            //   name cell (fills the remaining left space)
            double xCellW = removeMode ? X_CELL_WIDTH : 0.0;
            double sideCellW = showSideMode ? SIDE_MODE_CELL_WIDTH : 0.0;
            double sideCellR = colXR - xCellW;
            double sideCellL = sideCellR - sideCellW;
            double weightCellR = sideCellL;
            double weightCellL = weightCellR - WEIGHT_CELL_WIDTH;
            double nameCellL = colXL;
            double nameCellR = weightCellL;

            // Name highlight (just the name area)
            boolean nameHover = hovered.kind() == PartPositionMenu.CellKind.ENTRY_NAME && hovered.index() == i;
            if (nameHover) {
                drawQuad(ps, buffer, nameCellL + 0.01, rowBottom + 0.005,
                    nameCellR - 0.005, rowTop - 0.005, 0x60FFCC33);
            }
            drawLeftText(ps, buffer, font, entry.name(),
                nameCellL + 0.04, rowCY,
                nameHover ? 0xFF000000 : 0xFFFFFFFF);

            // Weight cell
            boolean weightHover = hovered.kind() == PartPositionMenu.CellKind.ENTRY_WEIGHT && hovered.index() == i;
            int weightTint = weightHover ? 0xC0FFCC33 : 0x40FFFFFF;
            drawQuad(ps, buffer, weightCellL + 0.005, rowBottom + 0.005,
                weightCellR - 0.005, rowTop - 0.005, weightTint);
            drawCenteredText(ps, buffer, font, Integer.toString(entry.weight()),
                (weightCellL + weightCellR) / 2.0, rowCY,
                weightHover ? 0xFF000000 : 0xFFFFFFFF);

            // Side-mode cell (walls/doors only) — click cycles BOTH→ONE→EITHER.
            if (showSideMode) {
                boolean sideHover = hovered.kind() == PartPositionMenu.CellKind.ENTRY_SIDE_MODE && hovered.index() == i;
                int sideTint = sideHover ? 0xC066CCFF : 0x60337799;
                drawQuad(ps, buffer, sideCellL + 0.005, rowBottom + 0.005,
                    sideCellR - 0.005, rowTop - 0.005, sideTint);
                drawCenteredText(ps, buffer, font, entry.sideMode().label(),
                    (sideCellL + sideCellR) / 2.0, rowCY,
                    sideHover ? 0xFF000000 : 0xFFFFFFFF);
            }

            // X cell (remove mode only)
            if (removeMode) {
                double xCellL = colXR - X_CELL_WIDTH;
                double xCellR = colXR;
                boolean xHover = hovered.kind() == PartPositionMenu.CellKind.ENTRY_REMOVE_X && hovered.index() == i;
                int xTint = xHover ? 0xC0FF4040 : 0x80AA2020;
                drawQuad(ps, buffer, xCellL + 0.005, rowBottom + 0.005,
                    xCellR - 0.005, rowTop - 0.005, xTint);
                drawCenteredText(ps, buffer, font, "X",
                    (xCellL + xCellR) / 2.0, rowCY, 0xFFFFFFFF);
            }
        }
    }

    private static void drawSearch(PoseStack ps, MultiBufferSource buffer, Font font, CarriagePartKind kind) {
        List<String> filtered = PartPositionMenu.filteredRegisteredNames();
        int n = filtered.size();
        int colCount = Math.max(1, (n + PartPositionMenu.ROWS_PER_COLUMN - 1) / PartPositionMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(MIN_PANEL_WIDTH, colCount * COLUMN_WIDTH);
        int displayedRows = Math.min(n, PartPositionMenu.ROWS_PER_COLUMN);
        if (displayedRows == 0) displayedRows = 1;
        double gridH = displayedRows * ROW_HEIGHT;
        double panelH = HEADER_HEIGHT + TOOLBAR_HEIGHT + gridH;
        double halfW = panelW / 2.0;
        double halfH = panelH / 2.0;
        PartPositionMenu.Hit hovered = PartPositionMenu.hovered();

        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, 0xC8000000);

        // Header — split into "< Back" (left chip) + "Add <Kind> Part" (rest).
        double headerTop = halfH;
        double headerBottom = halfH - HEADER_HEIGHT;
        double headerCY = (headerTop + headerBottom) / 2.0;
        double backCellW = 0.6;
        double backCellL = -halfW;
        double backCellR = backCellL + backCellW;
        boolean backHover = hovered.kind() == PartPositionMenu.CellKind.SEARCH_BACK;
        int backTint = backHover ? 0xC0FFCC33 : 0x60FFEEBB;
        drawQuad(ps, buffer, backCellL + 0.01, headerBottom + 0.005,
            backCellR - 0.005, headerTop - 0.005, backTint);
        drawCenteredText(ps, buffer, font, "< Back",
            (backCellL + backCellR) / 2.0, headerCY,
            backHover ? 0xFF000000 : 0xFFFFFFFF);
        drawQuad(ps, buffer, backCellR + 0.005, headerBottom + 0.005,
            halfW, headerTop - 0.005, 0x40FFEEBB);
        drawCenteredText(ps, buffer, font, "Add " + capitalize(kind.id()) + " Part",
            (backCellR + halfW) / 2.0, headerCY, 0xFFFFEEBB);

        // Search field row (acts like the typing buffer field)
        double searchTop = halfH - HEADER_HEIGHT;
        double searchBottom = searchTop - TOOLBAR_HEIGHT;
        double searchCY = (searchTop + searchBottom) / 2.0;
        boolean searchHover = hovered.kind() == PartPositionMenu.CellKind.SEARCH_FIELD;
        int searchTint = searchHover ? 0xB033FF99 : 0x60339966;
        drawQuad(ps, buffer, -halfW + 0.02, searchBottom + 0.01,
            halfW - 0.02, searchTop - 0.01, searchTint);
        String shown = "Search: " + PartPositionMenu.searchBuffer() + "_";
        drawLeftText(ps, buffer, font, shown, -halfW + 0.06, searchCY, 0xFFFFFFFF);

        double colActualW = panelW / colCount;
        double gridTop = searchBottom;
        for (int i = 0; i < n; i++) {
            int col = i / PartPositionMenu.ROWS_PER_COLUMN;
            int row = i % PartPositionMenu.ROWS_PER_COLUMN;
            double colXL = -halfW + col * colActualW;
            double colXR = colXL + colActualW;
            double rowTop = gridTop - row * ROW_HEIGHT;
            double rowBottom = rowTop - ROW_HEIGHT;
            double rowCY = (rowTop + rowBottom) / 2.0;
            boolean isHover = hovered.kind() == PartPositionMenu.CellKind.SEARCH_RESULT && hovered.index() == i;
            int tint = isHover ? 0xC0FFCC33 : (i % 2 == 0 ? 0x30FFFFFF : 0x18FFFFFF);
            drawQuad(ps, buffer, colXL + 0.01, rowBottom + 0.005,
                colXR - 0.01, rowTop - 0.005, tint);
            int textColour = isHover ? 0xFF000000 : 0xFFFFFFFF;
            drawLeftText(ps, buffer, font, filtered.get(i), colXL + 0.04, rowCY, textColour);
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
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
        vc.vertex(mat, (float) x1, (float) y1, 0).color(r, g, b, a).endVertex();
        vc.vertex(mat, (float) x2, (float) y1, 0).color(r, g, b, a).endVertex();
        vc.vertex(mat, (float) x2, (float) y2, 0).color(r, g, b, a).endVertex();
        vc.vertex(mat, (float) x1, (float) y2, 0).color(r, g, b, a).endVertex();
    }
}
