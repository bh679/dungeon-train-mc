package games.brennan.dungeontrain.client.menu.stagepanel;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer;
import games.brennan.dungeontrain.client.menu.MenuBlockIcons;
import games.brennan.dungeontrain.client.menu.MenuRenderStates;
import games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.StageBlocksSyncPacket;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;

/**
 * World-space renderer for the Stage Blocks panel — a cylindrical live billboard beside the Stages
 * panel (server-sent {@link StagePanelMenu#anchor()} at the editor door, basis rebuilt per frame
 * via {@link EditorPlotLabelsRenderer#basis}, same as the type menus — NOT the block-variant
 * menu's frozen face basis).
 *
 * <p>Three sub-screens: ROOT (header → Duplicate/Hide-unused/Close toolbar → aggregated block grid
 * → parts list with per-part icon strips), REPLACE_SEARCH (pick the replacement block), and
 * CONFIRM_REPLACE (red band before the irreversible stage-wide rewrite — the removeMode colour
 * family). {@link #hitFor} mirrors this layout exactly for {@link StagePanelMenuRaycast}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class StagePanelMenuRenderer {

    /** Same composite recipe as the type-menu panel, own buffer identity. */
    private static final RenderType PANEL_QUAD = RenderType.create(
        DungeonTrain.MOD_ID + ":stage_panel_quad",
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

    static final double TEXT_SCALE = 0.025;
    static final double ROW_H = 0.30;
    static final double PAD_X = 0.10;

    /** ROOT / CONFIRM panel half-width. */
    static final double ROOT_HALF_W = 2.2;
    /** REPLACE_SEARCH half-width — wide enough for full block ids. */
    static final double SEARCH_HALF_W = 3.0;

    /** Aggregated grid columns / display cap ("+K more" row past the cap). */
    static final int BLOCK_COLS = 4;
    static final int BLOCKS_DISPLAY_CAP = 32;
    /** Search results shown per filter pass (single column). */
    static final int SEARCH_RESULT_ROWS = 12;
    /** Icon sizing (shared with the Stages-panel strips). */
    static final double ICON_SIZE = 0.22;
    static final double ICON_SLOT = 0.24;
    static final int PART_ROW_ICONS = 6;

    /** Fraction of a part row given to the "kind/name" label; the strip fills the rest. */
    static final double PART_NAME_FRACTION = 0.45;
    /** Toolbar cell split: Duplicate | Hide unused | X. */
    static final double TOOLBAR_DUP_FRACTION = 0.40;
    static final double TOOLBAR_HIDE_FRACTION = 0.40;

    private static final int BACKDROP_COLOR = 0xC8000000;
    private static final int HOVER_COLOR = 0x60FFCC33;
    private static final int ROW_SEP_COLOR = 0x40FFFFFF;
    private static final int HEADER_BG = 0x60FFEEBB;
    private static final int HEADER_COLOR = 0xFFFFEEBB;
    private static final int NAME_COLOR = 0xFFFFFFFF;
    private static final int LABEL_COLOR = 0xFFBBD0FF;
    private static final int DIM_COLOR = 0xFF999999;
    private static final int DUPLICATE_COLOR = 0xFFAAFFAA;
    private static final int CLOSE_COLOR = 0xFFFF9999;
    /** Orange active-state tint (the Lock-cell family) behind "Hide unused: ON". */
    private static final int HIDE_ON_BG = 0x80CC7733;
    /** Red confirm colours — the removeMode family. */
    private static final int CONFIRM_BG = 0x80AA4040;
    private static final int CONFIRM_HOVER = 0xC0FF6666;
    private static final int CONFIRM_TEXT = 0xFFFFBBBB;

    private StagePanelMenuRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!StagePanelMenu.isActive()) return;
        if (!games.brennan.dungeontrain.client.EditorStatusHudOverlay.isEditorMenusVisible()) return;
        // Editor exited (type menus cleared) — don't render an orphan panel.
        if (EditorTypeMenuRenderer.menus().isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        BlockPos pos = StagePanelMenu.anchor();
        Vec3 anchor = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vec3[] b = EditorPlotLabelsRenderer.basis(anchor, cam);
        Vec3 right = b[0], up = b[1], normal = b[2];

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

        switch (StagePanelMenu.screen()) {
            case ROOT -> drawRoot(ps, buffer, font);
            case REPLACE_SEARCH -> drawSearch(ps, buffer, font);
            case CONFIRM_REPLACE -> drawConfirm(ps, buffer, font);
        }

        ps.popPose();
        buffer.endBatch(PANEL_QUAD);
        buffer.endBatch();
    }

    // ---------- layout ----------

    static double halfWidth() {
        return StagePanelMenu.screen() == StagePanelMenu.Screen.REPLACE_SEARCH
            ? SEARCH_HALF_W : ROOT_HALF_W;
    }

    /** Cells shown in the aggregated grid (server already caps the list; the client caps again). */
    static int shownBlockCells() {
        return Math.min(StagePanelMenu.blocks().size(), BLOCKS_DISPLAY_CAP);
    }

    static int blockGridRows() {
        int cells = shownBlockCells();
        return cells == 0 ? 1 : (cells + BLOCK_COLS - 1) / BLOCK_COLS;
    }

    /** True when a "+K more" label row follows the grid. */
    static boolean hasOverflowRow() {
        return StagePanelMenu.blocks().size() > shownBlockCells();
    }

    static int partRows() {
        return Math.max(1, StagePanelMenu.parts().size());
    }

    static int rowCount() {
        return switch (StagePanelMenu.screen()) {
            // header + toolbar + grid rows [+ overflow] + parts header + part rows
            case ROOT -> 2 + blockGridRows() + (hasOverflowRow() ? 1 : 0) + 1 + partRows();
            // header + field + results
            case REPLACE_SEARCH -> 2 + Math.min(
                StagePanelMenu.filteredBlockIds().size(), SEARCH_RESULT_ROWS);
            // header + from + to + note + buttons
            case CONFIRM_REPLACE -> 5;
        };
    }

    static double halfHeight() {
        return rowCount() * ROW_H / 2.0;
    }

    // ---------- ROOT ----------

    private static void drawRoot(PoseStack ps, MultiBufferSource buffer, Font font) {
        double halfW = halfWidth();
        double halfH = halfHeight();
        double topY = halfH;
        StagePanelMenu.Hit hovered = StagePanelMenu.hovered();

        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, BACKDROP_COLOR);

        // Header.
        double headerCY = topY - ROW_H / 2.0;
        drawQuad(ps, buffer, -halfW, topY - ROW_H, halfW, topY, HEADER_BG);
        drawCenteredText(ps, buffer, font, "Stage: " + StagePanelMenu.stageName(), 0, headerCY, HEADER_COLOR);

        // Toolbar: Duplicate | Hide unused | X.
        double tbTop = topY - ROW_H, tbBottom = tbTop - ROW_H, tbCY = (tbTop + tbBottom) / 2.0;
        double w = halfW * 2.0;
        double dupR = -halfW + w * TOOLBAR_DUP_FRACTION;
        double hideR = dupR + w * TOOLBAR_HIDE_FRACTION;
        drawQuad(ps, buffer, -halfW, tbTop - 0.005, halfW, tbTop + 0.005, ROW_SEP_COLOR);
        if (StagePanelMenu.hideUnused()) {
            drawQuad(ps, buffer, dupR + 0.005, tbBottom + 0.005, hideR - 0.005, tbTop - 0.005, HIDE_ON_BG);
        }
        switch (hovered.kind()) {
            case DUPLICATE -> drawQuad(ps, buffer, -halfW + 0.005, tbBottom + 0.005, dupR - 0.005, tbTop - 0.005, HOVER_COLOR);
            case HIDE_TOGGLE -> drawQuad(ps, buffer, dupR + 0.005, tbBottom + 0.005, hideR - 0.005, tbTop - 0.005, HOVER_COLOR);
            case CLOSE -> drawQuad(ps, buffer, hideR + 0.005, tbBottom + 0.005, halfW - 0.005, tbTop - 0.005, HOVER_COLOR);
            default -> { }
        }
        drawCenteredText(ps, buffer, font, "Duplicate", (-halfW + dupR) / 2.0, tbCY, DUPLICATE_COLOR);
        drawCenteredText(ps, buffer, font, "Hide unused: " + (StagePanelMenu.hideUnused() ? "ON" : "OFF"),
            (dupR + hideR) / 2.0, tbCY, NAME_COLOR);
        drawCenteredText(ps, buffer, font, "X", (hideR + halfW) / 2.0, tbCY, CLOSE_COLOR);

        // Aggregated block grid.
        List<String> blocks = StagePanelMenu.blocks();
        int shown = shownBlockCells();
        int gridRows = blockGridRows();
        double cellW = w / BLOCK_COLS;
        int rowBase = 2;
        if (shown == 0) {
            double cy = topY - (rowBase + 0.5) * ROW_H;
            drawCenteredText(ps, buffer, font, "(no blocks)", 0, cy, DIM_COLOR);
        } else {
            for (int i = 0; i < shown; i++) {
                int row = i / BLOCK_COLS;
                int col = i % BLOCK_COLS;
                double rowTop = topY - (rowBase + row) * ROW_H;
                double rowCY = rowTop - ROW_H / 2.0;
                double cellL = -halfW + col * cellW;
                if (hovered.kind() == StagePanelMenu.CellKind.BLOCK_CELL && hovered.index() == i) {
                    drawQuad(ps, buffer, cellL + 0.005, rowTop - ROW_H + 0.005,
                        cellL + cellW - 0.005, rowTop - 0.005, HOVER_COLOR);
                }
                MenuBlockIcons.drawBlockIcon(ps, buffer, blocks.get(i),
                    cellL + PAD_X + ICON_SIZE / 2.0, rowCY, ICON_SIZE);
                drawLeftText(ps, buffer, font, shortenBlockLabel(blocks.get(i), font, cellW - ICON_SLOT - 2 * PAD_X),
                    cellL + PAD_X + ICON_SLOT, rowCY, NAME_COLOR);
            }
        }
        int nextRow = rowBase + gridRows;
        if (hasOverflowRow()) {
            double cy = topY - (nextRow + 0.5) * ROW_H;
            drawCenteredText(ps, buffer, font, "+" + (blocks.size() - shown) + " more", 0, cy, DIM_COLOR);
            nextRow++;
        }

        // Parts subheader + rows.
        double phTop = topY - nextRow * ROW_H;
        drawQuad(ps, buffer, -halfW, phTop - 0.005, halfW, phTop + 0.005, ROW_SEP_COLOR);
        drawCenteredText(ps, buffer, font, "Parts (" + StagePanelMenu.parts().size() + ")",
            0, phTop - ROW_H / 2.0, HEADER_COLOR);
        nextRow++;

        List<StageBlocksSyncPacket.PartEntry> parts = StagePanelMenu.parts();
        if (parts.isEmpty()) {
            double cy = topY - (nextRow + 0.5) * ROW_H;
            drawCenteredText(ps, buffer, font, "No parts linked to this stage.", 0, cy, DIM_COLOR);
            return;
        }
        double nameRight = -halfW + w * PART_NAME_FRACTION;
        for (int pi = 0; pi < parts.size(); pi++) {
            StageBlocksSyncPacket.PartEntry pe = parts.get(pi);
            double rowTop = topY - (nextRow + pi) * ROW_H;
            double rowCY = rowTop - ROW_H / 2.0;
            drawQuad(ps, buffer, -halfW, rowTop - 0.005, halfW, rowTop + 0.005, ROW_SEP_COLOR);
            drawLeftText(ps, buffer, font, partLabel(pe), -halfW + PAD_X, rowCY, NAME_COLOR);
            int drawn = Math.min(pe.blockIds().size(), PART_ROW_ICONS);
            for (int i = 0; i < drawn; i++) {
                double cx = nameRight + (i + 0.5) * ICON_SLOT;
                if (hovered.kind() == StagePanelMenu.CellKind.PART_BLOCK
                        && hovered.index() == pi && hovered.secondary() == i) {
                    drawQuad(ps, buffer, cx - ICON_SLOT / 2.0, rowTop - ROW_H + 0.005,
                        cx + ICON_SLOT / 2.0, rowTop - 0.005, HOVER_COLOR);
                }
                MenuBlockIcons.drawBlockIcon(ps, buffer, pe.blockIds().get(i), cx, rowCY, ICON_SIZE);
            }
            if (pe.totalUnique() > drawn) {
                drawLeftText(ps, buffer, font, "+" + (pe.totalUnique() - drawn),
                    nameRight + drawn * ICON_SLOT + PAD_X, rowCY, LABEL_COLOR);
            }
        }
    }

    // ---------- REPLACE_SEARCH ----------

    private static void drawSearch(PoseStack ps, MultiBufferSource buffer, Font font) {
        double halfW = halfWidth();
        double halfH = halfHeight();
        double topY = halfH;
        StagePanelMenu.Hit hovered = StagePanelMenu.hovered();

        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, BACKDROP_COLOR);

        // Header: "< Back" chip (left quarter) + "Replace <from>".
        double headerCY = topY - ROW_H / 2.0;
        drawQuad(ps, buffer, -halfW, topY - ROW_H, halfW, topY, HEADER_BG);
        double backR = -halfW + halfW * 0.5;
        if (hovered.kind() == StagePanelMenu.CellKind.SEARCH_BACK) {
            drawQuad(ps, buffer, -halfW + 0.005, topY - ROW_H + 0.005, backR - 0.005, topY - 0.005, HOVER_COLOR);
        }
        drawLeftText(ps, buffer, font, "< Back", -halfW + PAD_X, headerCY, CLOSE_COLOR);
        drawCenteredText(ps, buffer, font, "Replace " + stripNamespace(StagePanelMenu.replaceFrom()),
            halfW * 0.25, headerCY, HEADER_COLOR);

        // Search field row.
        double fTop = topY - ROW_H, fCY = fTop - ROW_H / 2.0;
        if (hovered.kind() == StagePanelMenu.CellKind.SEARCH_FIELD) {
            drawQuad(ps, buffer, -halfW + 0.005, fTop - ROW_H + 0.005, halfW - 0.005, fTop - 0.005, HOVER_COLOR);
        }
        drawLeftText(ps, buffer, font, "Search: " + StagePanelMenu.searchBuffer() + "_",
            -halfW + PAD_X, fCY, LABEL_COLOR);

        // Result rows.
        List<String> filtered = StagePanelMenu.filteredBlockIds();
        int rows = Math.min(filtered.size(), SEARCH_RESULT_ROWS);
        for (int i = 0; i < rows; i++) {
            double rowTop = topY - (2 + i) * ROW_H;
            double rowCY = rowTop - ROW_H / 2.0;
            drawQuad(ps, buffer, -halfW, rowTop - 0.005, halfW, rowTop + 0.005, ROW_SEP_COLOR);
            if (hovered.kind() == StagePanelMenu.CellKind.SEARCH_RESULT && hovered.index() == i) {
                drawQuad(ps, buffer, -halfW + 0.005, rowTop - ROW_H + 0.005, halfW - 0.005, rowTop - 0.005, HOVER_COLOR);
            }
            MenuBlockIcons.drawBlockIcon(ps, buffer, filtered.get(i),
                -halfW + PAD_X + ICON_SIZE / 2.0, rowCY, ICON_SIZE);
            drawLeftText(ps, buffer, font, filtered.get(i), -halfW + PAD_X + ICON_SLOT, rowCY, NAME_COLOR);
        }
    }

    // ---------- CONFIRM_REPLACE ----------

    private static void drawConfirm(PoseStack ps, MultiBufferSource buffer, Font font) {
        double halfW = halfWidth();
        double halfH = halfHeight();
        double topY = halfH;
        StagePanelMenu.Hit hovered = StagePanelMenu.hovered();

        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, BACKDROP_COLOR);

        double headerCY = topY - ROW_H / 2.0;
        drawQuad(ps, buffer, -halfW, topY - ROW_H, halfW, topY, CONFIRM_BG);
        drawCenteredText(ps, buffer, font, "Replace across stage '" + StagePanelMenu.stageId() + "'",
            0, headerCY, CONFIRM_TEXT);

        double fromCY = topY - 1.5 * ROW_H;
        MenuBlockIcons.drawBlockIcon(ps, buffer, StagePanelMenu.replaceFrom(),
            -halfW + PAD_X + ICON_SIZE / 2.0, fromCY, ICON_SIZE);
        drawLeftText(ps, buffer, font, StagePanelMenu.replaceFrom(), -halfW + PAD_X + ICON_SLOT, fromCY, NAME_COLOR);

        double toCY = topY - 2.5 * ROW_H;
        drawLeftText(ps, buffer, font, "→", -halfW + PAD_X, toCY, HEADER_COLOR);
        MenuBlockIcons.drawBlockIcon(ps, buffer, StagePanelMenu.replaceTo(),
            -halfW + PAD_X + 0.30 + ICON_SIZE / 2.0, toCY, ICON_SIZE);
        drawLeftText(ps, buffer, font, StagePanelMenu.replaceTo(), -halfW + PAD_X + 0.30 + ICON_SLOT, toCY, NAME_COLOR);

        double noteCY = topY - 3.5 * ROW_H;
        drawCenteredText(ps, buffer, font, "Rewrites " + StagePanelMenu.parts().size()
            + " part(s) — no undo.", 0, noteCY, CONFIRM_TEXT);

        double btnTop = topY - 4 * ROW_H, btnBottom = btnTop - ROW_H, btnCY = (btnTop + btnBottom) / 2.0;
        boolean yesHover = hovered.kind() == StagePanelMenu.CellKind.CONFIRM_YES;
        boolean noHover = hovered.kind() == StagePanelMenu.CellKind.CONFIRM_NO;
        drawQuad(ps, buffer, -halfW + 0.005, btnBottom + 0.005, -0.005, btnTop - 0.005,
            yesHover ? CONFIRM_HOVER : CONFIRM_BG);
        if (noHover) drawQuad(ps, buffer, 0.005, btnBottom + 0.005, halfW - 0.005, btnTop - 0.005, HOVER_COLOR);
        drawCenteredText(ps, buffer, font, "Confirm", -halfW / 2.0, btnCY, NAME_COLOR);
        drawCenteredText(ps, buffer, font, "Cancel", halfW / 2.0, btnCY, NAME_COLOR);
    }

    // ---------- hit testing (shared with StagePanelMenuRaycast) ----------

    static StagePanelMenu.Hit hitFor(double hitX, double hitY) {
        double halfW = halfWidth();
        double halfH = halfHeight();
        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) {
            return StagePanelMenu.Hit.NONE;
        }
        int rowFromTop = (int) Math.floor((halfH - hitY) / ROW_H);
        if (rowFromTop < 0 || rowFromTop >= rowCount()) return StagePanelMenu.Hit.NONE;
        return switch (StagePanelMenu.screen()) {
            case ROOT -> hitForRoot(rowFromTop, hitX, halfW);
            case REPLACE_SEARCH -> hitForSearch(rowFromTop, hitX, halfW);
            case CONFIRM_REPLACE -> hitForConfirm(rowFromTop, hitX, halfW);
        };
    }

    private static StagePanelMenu.Hit hitForRoot(int rowFromTop, double hitX, double halfW) {
        double w = halfW * 2.0;
        if (rowFromTop == 0) return StagePanelMenu.Hit.NONE;
        if (rowFromTop == 1) {
            double dupR = -halfW + w * TOOLBAR_DUP_FRACTION;
            double hideR = dupR + w * TOOLBAR_HIDE_FRACTION;
            if (hitX < dupR) return new StagePanelMenu.Hit(StagePanelMenu.CellKind.DUPLICATE, -1, -1);
            if (hitX < hideR) return new StagePanelMenu.Hit(StagePanelMenu.CellKind.HIDE_TOGGLE, -1, -1);
            return new StagePanelMenu.Hit(StagePanelMenu.CellKind.CLOSE, -1, -1);
        }
        int gridRows = blockGridRows();
        int shown = shownBlockCells();
        int row = rowFromTop - 2;
        if (row < gridRows) {
            if (shown == 0) return StagePanelMenu.Hit.NONE;
            int col = (int) ((hitX + halfW) / (w / BLOCK_COLS));
            if (col < 0) col = 0;
            if (col >= BLOCK_COLS) col = BLOCK_COLS - 1;
            int idx = row * BLOCK_COLS + col;
            if (idx >= shown) return StagePanelMenu.Hit.NONE;
            return new StagePanelMenu.Hit(StagePanelMenu.CellKind.BLOCK_CELL, idx, -1);
        }
        row -= gridRows;
        if (hasOverflowRow()) {
            if (row == 0) return StagePanelMenu.Hit.NONE;
            row--;
        }
        if (row == 0) return StagePanelMenu.Hit.NONE; // "Parts (N)" subheader
        int partIdx = row - 1;
        List<StageBlocksSyncPacket.PartEntry> parts = StagePanelMenu.parts();
        if (partIdx < 0 || partIdx >= parts.size()) return StagePanelMenu.Hit.NONE;
        double nameRight = -halfW + w * PART_NAME_FRACTION;
        if (hitX < nameRight) return StagePanelMenu.Hit.NONE;
        int iconIdx = (int) ((hitX - nameRight) / ICON_SLOT);
        int drawn = Math.min(parts.get(partIdx).blockIds().size(), PART_ROW_ICONS);
        if (iconIdx < 0 || iconIdx >= drawn) return StagePanelMenu.Hit.NONE;
        return new StagePanelMenu.Hit(StagePanelMenu.CellKind.PART_BLOCK, partIdx, iconIdx);
    }

    private static StagePanelMenu.Hit hitForSearch(int rowFromTop, double hitX, double halfW) {
        if (rowFromTop == 0) {
            return hitX < -halfW + halfW * 0.5
                ? new StagePanelMenu.Hit(StagePanelMenu.CellKind.SEARCH_BACK, -1, -1)
                : StagePanelMenu.Hit.NONE;
        }
        if (rowFromTop == 1) return new StagePanelMenu.Hit(StagePanelMenu.CellKind.SEARCH_FIELD, -1, -1);
        int idx = rowFromTop - 2;
        int rows = Math.min(StagePanelMenu.filteredBlockIds().size(), SEARCH_RESULT_ROWS);
        if (idx < 0 || idx >= rows) return StagePanelMenu.Hit.NONE;
        return new StagePanelMenu.Hit(StagePanelMenu.CellKind.SEARCH_RESULT, idx, -1);
    }

    private static StagePanelMenu.Hit hitForConfirm(int rowFromTop, double hitX, double halfW) {
        if (rowFromTop != 4) return StagePanelMenu.Hit.NONE;
        return hitX < 0
            ? new StagePanelMenu.Hit(StagePanelMenu.CellKind.CONFIRM_YES, -1, -1)
            : new StagePanelMenu.Hit(StagePanelMenu.CellKind.CONFIRM_NO, -1, -1);
    }

    // ---------- text / label helpers ----------

    private static String partLabel(StageBlocksSyncPacket.PartEntry pe) {
        CarriagePartKind[] kinds = CarriagePartKind.values();
        String kind = pe.kindOrd() >= 0 && pe.kindOrd() < kinds.length
            ? kinds[pe.kindOrd()].id() : "?";
        return kind + "/" + pe.partName();
    }

    private static String stripNamespace(String blockId) {
        int colon = blockId.indexOf(':');
        return colon >= 0 ? blockId.substring(colon + 1) : blockId;
    }

    /** Namespace-stripped label, ellipsised to fit {@code maxW} panel units. */
    private static String shortenBlockLabel(String blockId, Font font, double maxW) {
        String label = stripNamespace(blockId);
        if (font.width(label) * TEXT_SCALE <= maxW) return label;
        while (label.length() > 1 && font.width(label + "…") * TEXT_SCALE > maxW) {
            label = label.substring(0, label.length() - 1);
        }
        return label + "…";
    }

    private static void drawCenteredText(PoseStack ps, MultiBufferSource buffer, Font font,
                                         String text, double worldX, double worldY, int colour) {
        drawText(ps, buffer, font, text, worldX, worldY, colour, true);
    }

    private static void drawLeftText(PoseStack ps, MultiBufferSource buffer, Font font,
                                     String text, double worldX, double worldY, int colour) {
        drawText(ps, buffer, font, text, worldX, worldY, colour, false);
    }

    private static void drawText(PoseStack ps, MultiBufferSource buffer, Font font,
                                 String text, double worldX, double worldY, int colour, boolean centered) {
        ps.pushPose();
        ps.translate(worldX, worldY, 0.001f);
        float scale = (float) TEXT_SCALE;
        ps.scale(scale, -scale, scale);
        float x = centered ? -font.width(text) / 2f : 0f;
        float y = -font.lineHeight / 2f;
        Matrix4f mat = ps.last().pose();
        font.drawInBatch(text, x, y, colour, false, mat, buffer,
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
