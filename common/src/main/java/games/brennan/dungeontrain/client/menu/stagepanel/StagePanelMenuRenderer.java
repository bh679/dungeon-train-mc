package games.brennan.dungeontrain.client.menu.stagepanel;
import games.brennan.dungeontrain.DtCore;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

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
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;

/**
 * World-space renderer for the Stage Blocks panel — a cylindrical live billboard beside the Stages
 * panel (server-sent {@link StagePanelMenu#anchor()} at the editor door, basis rebuilt per frame
 * via {@link EditorPlotLabelsRenderer#basis}, same as the type menus).
 *
 * <p>Single screen: header → {@code [Duplicate] [Hide unused] [X]} toolbar → the stage's
 * usage-ordered block <b>list</b> (icon + name + count column, mirroring #636's V menu) → a
 * {@code Parts (N)} list with per-part icon strips. Clicking a block row (or a part-strip icon)
 * swaps that block across the whole stage with the player's held block. {@link #hitFor} mirrors
 * this layout exactly for {@link StagePanelMenuRaycast}.</p>
 */
public final class StagePanelMenuRenderer {

    /** Same composite recipe as the type-menu panel, own buffer identity. */
    private static final RenderType PANEL_QUAD = RenderType.create(
        DtCore.MOD_ID + ":stage_panel_quad",
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

    static final double HALF_W = 2.6;

    /** Max block rows shown in the list; the rest collapse into a "+K more" row. */
    static final int BLOCKS_DISPLAY_CAP = 24;
    /** Right-hand reserve for the usage count column. */
    static final double COUNT_COL_W = 0.55;
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
    private static final int COUNT_COLOR = 0xFFBBD0FF;
    private static final int LABEL_COLOR = 0xFFBBD0FF;
    private static final int DIM_COLOR = 0xFF999999;
    private static final int DUPLICATE_COLOR = 0xFFAAFFAA;
    private static final int CLOSE_COLOR = 0xFFFF9999;
    /** Orange active-state tint (the Lock-cell family) behind "Hide unused: ON". */
    private static final int HIDE_ON_BG = 0x80CC7733;

    private StagePanelMenuRenderer() {}

    public static void onRenderLevelStage(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.Camera camera, net.minecraft.client.DeltaTracker deltaTracker) {
        if (!StagePanelMenu.isActive()) return;
        if (!games.brennan.dungeontrain.client.EditorStatusHudOverlay.isEditorMenusVisible()) return;
        // Editor exited (type menus cleared) — don't render an orphan panel.
        if (EditorTypeMenuRenderer.menus().isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PoseStack ps = poseStack;
        Vec3 cam = camera.getPosition();
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

        drawRoot(ps, buffer, font);

        ps.popPose();
        buffer.endBatch(PANEL_QUAD);
        buffer.endBatch();
    }

    // ---------- layout ----------

    static double halfWidth() {
        return HALF_W;
    }

    /** Block rows shown in the list (server already usage-caps; the client caps display again). */
    static int shownBlockRows() {
        return Math.min(StagePanelMenu.blocks().size(), BLOCKS_DISPLAY_CAP);
    }

    /** Rows the block section occupies — the shown rows, or one reserved row for the empty placeholder. */
    static int blockSectionRows() {
        return Math.max(1, shownBlockRows());
    }

    /** True when a "+K more" label row follows the list — against the REAL total, not the wire cap. */
    static boolean hasOverflowRow() {
        return StagePanelMenu.totalBlocks() > shownBlockRows();
    }

    static int partRows() {
        return Math.max(1, StagePanelMenu.parts().size());
    }

    /** header + toolbar + block rows (min 1 for the empty placeholder) [+ overflow] + parts header + part rows. */
    static int rowCount() {
        return 2 + blockSectionRows() + (hasOverflowRow() ? 1 : 0) + 1 + partRows();
    }

    static double halfHeight() {
        return rowCount() * ROW_H / 2.0;
    }

    // ---------- ROOT ----------

    private static void drawRoot(PoseStack ps, MultiBufferSource buffer, Font font) {
        double halfW = HALF_W;
        double halfH = halfHeight();
        double topY = halfH;
        double w = halfW * 2.0;
        StagePanelMenu.Hit hovered = StagePanelMenu.hovered();

        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, BACKDROP_COLOR);

        // Header.
        double headerCY = topY - ROW_H / 2.0;
        drawQuad(ps, buffer, -halfW, topY - ROW_H, halfW, topY, HEADER_BG);
        drawCenteredText(ps, buffer, font, "Stage: " + StagePanelMenu.stageName(), 0, headerCY, HEADER_COLOR);

        // Toolbar: Duplicate | Hide unused | X.
        double tbTop = topY - ROW_H, tbBottom = tbTop - ROW_H, tbCY = (tbTop + tbBottom) / 2.0;
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

        // Block list — icon + name + count column; a click swaps the row's block with the held block.
        List<StageBlocksSyncPacket.BlockCount> blocks = StagePanelMenu.blocks();
        int shown = shownBlockRows();
        int rowBase = 2;
        double countCX = halfW - COUNT_COL_W / 2.0 - PAD_X;
        double nameMaxW = (countCX - COUNT_COL_W / 2.0) - (-halfW + PAD_X + ICON_SLOT) - PAD_X;
        if (shown == 0) {
            drawCenteredText(ps, buffer, font, "(no blocks)", 0, topY - (rowBase + 0.5) * ROW_H, DIM_COLOR);
        }
        for (int i = 0; i < shown; i++) {
            StageBlocksSyncPacket.BlockCount bc = blocks.get(i);
            double rowTop = topY - (rowBase + i) * ROW_H;
            double rowCY = rowTop - ROW_H / 2.0;
            drawQuad(ps, buffer, -halfW, rowTop - 0.005, halfW, rowTop + 0.005, ROW_SEP_COLOR);
            if (hovered.kind() == StagePanelMenu.CellKind.BLOCK_ROW && hovered.index() == i) {
                drawQuad(ps, buffer, -halfW + 0.005, rowTop - ROW_H + 0.005, halfW - 0.005, rowTop - 0.005, HOVER_COLOR);
            }
            MenuBlockIcons.drawBlockIcon(ps, buffer, bc.blockId(),
                -halfW + PAD_X + ICON_SIZE / 2.0, rowCY, ICON_SIZE);
            drawLeftText(ps, buffer, font, shortenBlockLabel(bc.blockId(), font, nameMaxW),
                -halfW + PAD_X + ICON_SLOT, rowCY, NAME_COLOR);
            drawCenteredText(ps, buffer, font, "×" + bc.count(), countCX, rowCY, COUNT_COLOR);
        }
        // Reserve one row for the empty placeholder so the Parts subheader doesn't overlap it.
        int nextRow = rowBase + blockSectionRows();
        if (hasOverflowRow()) {
            drawCenteredText(ps, buffer, font, "+" + (StagePanelMenu.totalBlocks() - shown) + " more",
                0, topY - (nextRow + 0.5) * ROW_H, DIM_COLOR);
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
            drawCenteredText(ps, buffer, font, "No parts linked to this stage.",
                0, topY - (nextRow + 0.5) * ROW_H, DIM_COLOR);
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

    // ---------- hit testing (shared with StagePanelMenuRaycast) ----------

    static StagePanelMenu.Hit hitFor(double hitX, double hitY) {
        double halfW = HALF_W;
        double halfH = halfHeight();
        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) {
            return StagePanelMenu.Hit.NONE;
        }
        int rowFromTop = (int) Math.floor((halfH - hitY) / ROW_H);
        if (rowFromTop < 0 || rowFromTop >= rowCount()) return StagePanelMenu.Hit.NONE;
        double w = halfW * 2.0;

        if (rowFromTop == 0) return StagePanelMenu.Hit.NONE; // header
        if (rowFromTop == 1) {
            double dupR = -halfW + w * TOOLBAR_DUP_FRACTION;
            double hideR = dupR + w * TOOLBAR_HIDE_FRACTION;
            if (hitX < dupR) return new StagePanelMenu.Hit(StagePanelMenu.CellKind.DUPLICATE, -1, -1);
            if (hitX < hideR) return new StagePanelMenu.Hit(StagePanelMenu.CellKind.HIDE_TOGGLE, -1, -1);
            return new StagePanelMenu.Hit(StagePanelMenu.CellKind.CLOSE, -1, -1);
        }
        int shown = shownBlockRows();
        int blockRows = blockSectionRows();
        int row = rowFromTop - 2;
        if (row < blockRows) {
            // The reserved empty-placeholder row (shown == 0) isn't a clickable block.
            return shown == 0 ? StagePanelMenu.Hit.NONE
                : new StagePanelMenu.Hit(StagePanelMenu.CellKind.BLOCK_ROW, row, -1);
        }
        row -= blockRows;
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
