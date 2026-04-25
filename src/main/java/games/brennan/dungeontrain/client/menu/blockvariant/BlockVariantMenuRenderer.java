package games.brennan.dungeontrain.client.menu.blockvariant;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.MenuRenderStates;
import games.brennan.dungeontrain.net.BlockVariantSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;

/**
 * World-space renderer for {@link BlockVariantMenu}. Mirrors
 * {@link games.brennan.dungeontrain.client.menu.parts.PartPositionMenuRenderer}
 * with two tweaks: a 5-cell toolbar (Copy / Add / Remove / Clear / X)
 * instead of 4, and a per-row Lock column instead of side-mode.
 *
 * <p>Drawn during {@link RenderLevelStageEvent.Stage#AFTER_TRANSLUCENT_BLOCKS}.
 * The cell label shows the block id's path segment (e.g. "stone_bricks"
 * from "minecraft:stone_bricks") to keep the row narrow; full id is shown
 * in the search screen.</p>
 */
@Mod.EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = Dist.CLIENT
)
public final class BlockVariantMenuRenderer {

    static final RenderType PANEL_QUAD = RenderType.create(
        DungeonTrain.MOD_ID + ":block_variant_menu_quad",
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
    /** A grid column = name cell + weight + lock + (optional) X. Slightly wider than parts to fit longer block ids. */
    static final double COLUMN_WIDTH = 1.9;
    /** Five-cell toolbar needs a bit more width than the four-cell part menu. */
    static final double MIN_PANEL_WIDTH = 2.8;
    static final double X_CELL_WIDTH = 0.30;
    static final double WEIGHT_CELL_WIDTH = 0.40;
    static final double LOCK_CELL_WIDTH = 0.30;
    static final double TEXT_SCALE = 0.012;

    private BlockVariantMenuRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!BlockVariantMenu.isActive()) return;
        if (BlockVariantMenu.localPos() == null) return;

        BlockVariantMenuRaycast.updateHovered();

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        Vec3 anchor = BlockVariantMenu.anchorPos();
        Vec3 right = BlockVariantMenu.anchorRight();
        Vec3 up = BlockVariantMenu.anchorUp();
        Vec3 normal = BlockVariantMenu.anchorNormal();

        ps.pushPose();
        ps.translate(anchor.x - cam.x, anchor.y - cam.y, anchor.z - cam.z);
        Matrix3f basis = new Matrix3f(
            (float) right.x, (float) right.y, (float) right.z,
            (float) up.x, (float) up.y, (float) up.z,
            (float) normal.x, (float) normal.y, (float) normal.z
        );
        ps.mulPose(new Quaternionf().setFromNormalized(basis));

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        if (BlockVariantMenu.screen() == BlockVariantMenu.Screen.ROOT) {
            drawRoot(ps, buffer, font);
        } else {
            drawSearch(ps, buffer, font);
        }

        buffer.endBatch(PANEL_QUAD);
        ps.popPose();
    }

    private static void drawRoot(PoseStack ps, MultiBufferSource buffer, Font font) {
        List<BlockVariantSyncPacket.Entry> entries = BlockVariantMenu.entries();
        int n = entries.size();
        int colCount = Math.max(1, (n + BlockVariantMenu.ROWS_PER_COLUMN - 1) / BlockVariantMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(MIN_PANEL_WIDTH, colCount * COLUMN_WIDTH);
        int displayedRows = Math.min(n, BlockVariantMenu.ROWS_PER_COLUMN);
        if (displayedRows == 0) displayedRows = 1;
        double gridH = displayedRows * ROW_HEIGHT;
        double panelH = HEADER_HEIGHT + TOOLBAR_HEIGHT + gridH;
        double halfW = panelW / 2.0;
        double halfH = panelH / 2.0;
        boolean removeMode = BlockVariantMenu.removeMode();
        BlockVariantMenu.Hit hovered = BlockVariantMenu.hovered();

        // Backdrop
        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, 0xC8000000);

        // Header — show the cell's local position so the player can confirm what they're editing.
        double headerCY = halfH - HEADER_HEIGHT / 2.0;
        drawQuad(ps, buffer, -halfW, halfH - HEADER_HEIGHT, halfW, halfH, 0x40FFEEBB);
        net.minecraft.core.BlockPos local = BlockVariantMenu.localPos();
        String headerLabel = local == null
            ? "Block Variants"
            : "Block Variants @ " + local.getX() + "," + local.getY() + "," + local.getZ();
        drawCenteredText(ps, buffer, font, headerLabel, 0, headerCY, 0xFFFFEEBB);

        // Toolbar — 5 cells: Copy | Add | Remove | Clear | X (close).
        double toolbarTop = halfH - HEADER_HEIGHT;
        double toolbarBottom = toolbarTop - TOOLBAR_HEIGHT;
        double toolbarCY = (toolbarTop + toolbarBottom) / 2.0;
        double cellW = panelW / 5.0;
        for (int i = 0; i < 5; i++) {
            double xL = -halfW + i * cellW;
            double xR = xL + cellW;
            BlockVariantMenu.CellKind cellKind = switch (i) {
                case 0 -> BlockVariantMenu.CellKind.COPY;
                case 1 -> BlockVariantMenu.CellKind.ADD;
                case 2 -> BlockVariantMenu.CellKind.REMOVE;
                case 3 -> BlockVariantMenu.CellKind.CLEAR;
                default -> BlockVariantMenu.CellKind.CLOSE;
            };
            boolean isHover = hovered.kind() == cellKind;
            int tint;
            if (cellKind == BlockVariantMenu.CellKind.REMOVE && removeMode) {
                tint = isHover ? 0xC0FF6666 : 0x80AA4040;
            } else if (cellKind == BlockVariantMenu.CellKind.CLOSE) {
                tint = isHover ? 0xC0FF8080 : 0x40FF6060;
            } else if (cellKind == BlockVariantMenu.CellKind.COPY) {
                tint = isHover ? 0xB033FFCC : 0x40339999;
            } else {
                tint = isHover ? 0xB0FFCC33 : 0x30FFFFFF;
            }
            drawQuad(ps, buffer, xL + 0.01, toolbarBottom + 0.005,
                xR - 0.01, toolbarTop - 0.005, tint);
            String label = switch (cellKind) {
                case COPY -> "Copy";
                case ADD -> "Add";
                case REMOVE -> removeMode ? "Cancel" : "Remove";
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
            int col = i / BlockVariantMenu.ROWS_PER_COLUMN;
            int row = i % BlockVariantMenu.ROWS_PER_COLUMN;
            double colXL = -halfW + col * colActualW;
            double colXR = colXL + colActualW;
            double rowTop = gridTop - row * ROW_HEIGHT;
            double rowBottom = rowTop - ROW_HEIGHT;
            double rowCY = (rowTop + rowBottom) / 2.0;

            int rowTint = (i % 2 == 0) ? 0x20FFFFFF : 0x10FFFFFF;
            drawQuad(ps, buffer, colXL + 0.01, rowBottom + 0.005,
                colXR - 0.01, rowTop - 0.005, rowTint);

            BlockVariantSyncPacket.Entry entry = entries.get(i);

            // Cell layout right-to-left:
            //   [X]  (rightmost, remove-mode only)
            //   [Lock]
            //   [Weight]
            //   [Name] (fills the remaining left)
            double xCellW = removeMode ? X_CELL_WIDTH : 0.0;
            double lockCellR = colXR - xCellW;
            double lockCellL = lockCellR - LOCK_CELL_WIDTH;
            double weightCellR = lockCellL;
            double weightCellL = weightCellR - WEIGHT_CELL_WIDTH;
            double nameCellL = colXL;
            double nameCellR = weightCellL;

            // Name highlight + label
            boolean nameHover = hovered.kind() == BlockVariantMenu.CellKind.ENTRY_NAME && hovered.index() == i;
            if (nameHover) {
                drawQuad(ps, buffer, nameCellL + 0.01, rowBottom + 0.005,
                    nameCellR - 0.005, rowTop - 0.005, 0x60FFCC33);
            }
            drawLeftText(ps, buffer, font, shortenStateLabel(entry.stateString()),
                nameCellL + 0.04, rowCY,
                nameHover ? 0xFF000000 : 0xFFFFFFFF);

            // Weight cell
            boolean weightHover = hovered.kind() == BlockVariantMenu.CellKind.ENTRY_WEIGHT && hovered.index() == i;
            int weightTint = weightHover ? 0xC0FFCC33 : 0x40FFFFFF;
            drawQuad(ps, buffer, weightCellL + 0.005, rowBottom + 0.005,
                weightCellR - 0.005, rowTop - 0.005, weightTint);
            drawCenteredText(ps, buffer, font, Integer.toString(entry.weight()),
                (weightCellL + weightCellR) / 2.0, rowCY,
                weightHover ? 0xFF000000 : 0xFFFFFFFF);

            // Lock cell — visual: closed-padlock symbol when locked, open-padlock when not.
            boolean lockHover = hovered.kind() == BlockVariantMenu.CellKind.ENTRY_LOCK && hovered.index() == i;
            int lockTint;
            if (entry.locked()) {
                lockTint = lockHover ? 0xC0FFAA55 : 0x80CC7733;
            } else {
                lockTint = lockHover ? 0xC0AAAAAA : 0x60777777;
            }
            drawQuad(ps, buffer, lockCellL + 0.005, rowBottom + 0.005,
                lockCellR - 0.005, rowTop - 0.005, lockTint);
            drawCenteredText(ps, buffer, font, entry.locked() ? "L" : "-",
                (lockCellL + lockCellR) / 2.0, rowCY,
                lockHover ? 0xFF000000 : 0xFFFFFFFF);

            // Remove [X]
            if (removeMode) {
                double xCellL = colXR - X_CELL_WIDTH;
                double xCellR = colXR;
                boolean xHover = hovered.kind() == BlockVariantMenu.CellKind.ENTRY_REMOVE_X && hovered.index() == i;
                int xTint = xHover ? 0xC0FF4040 : 0x80AA2020;
                drawQuad(ps, buffer, xCellL + 0.005, rowBottom + 0.005,
                    xCellR - 0.005, rowTop - 0.005, xTint);
                drawCenteredText(ps, buffer, font, "X",
                    (xCellL + xCellR) / 2.0, rowCY, 0xFFFFFFFF);
            }
        }
    }

    private static void drawSearch(PoseStack ps, MultiBufferSource buffer, Font font) {
        List<String> filtered = BlockVariantMenu.filteredBlockIds();
        // Cap the rendered list to one column's worth so the panel doesn't
        // explode horizontally when the player hasn't typed yet (vanilla
        // has ~600+ blocks).
        int maxRows = BlockVariantMenu.ROWS_PER_COLUMN * 4;
        int n = Math.min(filtered.size(), maxRows);
        int colCount = Math.max(1, (n + BlockVariantMenu.ROWS_PER_COLUMN - 1) / BlockVariantMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(MIN_PANEL_WIDTH, colCount * COLUMN_WIDTH);
        int displayedRows = Math.min(n, BlockVariantMenu.ROWS_PER_COLUMN);
        if (displayedRows == 0) displayedRows = 1;
        double gridH = displayedRows * ROW_HEIGHT;
        double panelH = HEADER_HEIGHT + TOOLBAR_HEIGHT + gridH;
        double halfW = panelW / 2.0;
        double halfH = panelH / 2.0;
        BlockVariantMenu.Hit hovered = BlockVariantMenu.hovered();

        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, 0xC8000000);

        // Header: "< Back" left chip + "Add Block Variant" rest.
        double headerTop = halfH;
        double headerBottom = halfH - HEADER_HEIGHT;
        double headerCY = (headerTop + headerBottom) / 2.0;
        double backCellW = 0.6;
        double backCellL = -halfW;
        double backCellR = backCellL + backCellW;
        boolean backHover = hovered.kind() == BlockVariantMenu.CellKind.SEARCH_BACK;
        int backTint = backHover ? 0xC0FFCC33 : 0x60FFEEBB;
        drawQuad(ps, buffer, backCellL + 0.01, headerBottom + 0.005,
            backCellR - 0.005, headerTop - 0.005, backTint);
        drawCenteredText(ps, buffer, font, "< Back",
            (backCellL + backCellR) / 2.0, headerCY,
            backHover ? 0xFF000000 : 0xFFFFFFFF);
        drawQuad(ps, buffer, backCellR + 0.005, headerBottom + 0.005,
            halfW, headerTop - 0.005, 0x40FFEEBB);
        drawCenteredText(ps, buffer, font, "Add Block Variant",
            (backCellR + halfW) / 2.0, headerCY, 0xFFFFEEBB);

        // Search field row
        double searchTop = halfH - HEADER_HEIGHT;
        double searchBottom = searchTop - TOOLBAR_HEIGHT;
        double searchCY = (searchTop + searchBottom) / 2.0;
        boolean searchHover = hovered.kind() == BlockVariantMenu.CellKind.SEARCH_FIELD;
        int searchTint = searchHover ? 0xB033FF99 : 0x60339966;
        drawQuad(ps, buffer, -halfW + 0.02, searchBottom + 0.01,
            halfW - 0.02, searchTop - 0.01, searchTint);
        String shown = "Search: " + BlockVariantMenu.searchBuffer() + "_";
        drawLeftText(ps, buffer, font, shown, -halfW + 0.06, searchCY, 0xFFFFFFFF);

        double colActualW = panelW / colCount;
        double gridTop = searchBottom;
        for (int i = 0; i < n; i++) {
            int col = i / BlockVariantMenu.ROWS_PER_COLUMN;
            int row = i % BlockVariantMenu.ROWS_PER_COLUMN;
            double colXL = -halfW + col * colActualW;
            double colXR = colXL + colActualW;
            double rowTop = gridTop - row * ROW_HEIGHT;
            double rowBottom = rowTop - ROW_HEIGHT;
            double rowCY = (rowTop + rowBottom) / 2.0;
            boolean isHover = hovered.kind() == BlockVariantMenu.CellKind.SEARCH_RESULT && hovered.index() == i;
            int tint = isHover ? 0xC0FFCC33 : (i % 2 == 0 ? 0x30FFFFFF : 0x18FFFFFF);
            drawQuad(ps, buffer, colXL + 0.01, rowBottom + 0.005,
                colXR - 0.01, rowTop - 0.005, tint);
            int textColour = isHover ? 0xFF000000 : 0xFFFFFFFF;
            drawLeftText(ps, buffer, font, filtered.get(i), colXL + 0.04, rowCY, textColour);
        }
    }

    /**
     * Strip {@code modid:} prefix and any blockstate properties for the
     * row label — just the path segment fits the narrow name cell. The
     * full state is preserved in the underlying entry; tooltip / search
     * still uses it.
     */
    static String shortenStateLabel(String stateString) {
        if (stateString == null) return "";
        // Drop properties section "[...]"
        int bracket = stateString.indexOf('[');
        String trimmed = bracket >= 0 ? stateString.substring(0, bracket) : stateString;
        int colon = trimmed.indexOf(':');
        return colon >= 0 ? trimmed.substring(colon + 1) : trimmed;
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
