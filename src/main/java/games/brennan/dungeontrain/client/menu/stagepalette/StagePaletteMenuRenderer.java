package games.brennan.dungeontrain.client.menu.stagepalette;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer;
import games.brennan.dungeontrain.client.menu.MenuBlockIcons;
import games.brennan.dungeontrain.client.menu.MenuRenderStates;
import games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer;
import games.brennan.dungeontrain.client.menu.stagepalette.StagePaletteMenu.CellKind;
import games.brennan.dungeontrain.client.menu.stagepalette.StagePaletteMenu.Hit;
import games.brennan.dungeontrain.client.menu.stagepalette.StagePaletteMenu.Row;
import games.brennan.dungeontrain.client.menu.stagepalette.StagePaletteMenu.RowKind;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.StagePaletteSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
 * World-space renderer for the Stage Palette panel — the sibling billboard of
 * {@link games.brennan.dungeontrain.client.menu.stagepanel.StagePanelMenuRenderer}, anchored one
 * panel further along {@code +X}. Fixed layout from {@link StagePaletteMenu#LAYOUT}: header →
 * {@code [Re-bake] [X]} → sections of placeholder cells (placeholder icon {@code →} resolved
 * icon; gold underline = user override) → a status row naming the hovered cell's assignment.
 * {@link #hitFor} mirrors the layout for {@link StagePaletteMenuRaycast}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class StagePaletteMenuRenderer {

    private static final RenderType PANEL_QUAD = RenderType.create(
        DungeonTrain.MOD_ID + ":stage_palette_quad",
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
    static final double ICON_SIZE = 0.22;
    /** Fraction of a cells row given to the row label (stone kinds); 0 for unlabelled rows. */
    static final double LABEL_FRACTION = 0.18;
    /** Toolbar split: Re-bake | X. */
    static final double TOOLBAR_REBAKE_FRACTION = 0.75;

    private static final int BACKDROP_COLOR = 0xC8000000;
    private static final int HOVER_COLOR = 0x60FFCC33;
    private static final int ROW_SEP_COLOR = 0x40FFFFFF;
    private static final int HEADER_BG = 0x60FFEEBB;
    private static final int HEADER_COLOR = 0xFFFFEEBB;
    private static final int NAME_COLOR = 0xFFFFFFFF;
    private static final int DIM_COLOR = 0xFF999999;
    private static final int ARROW_COLOR = 0xFFBBD0FF;
    private static final int OVERRIDE_COLOR = 0xFFFFCC33;
    private static final int LOCKED_BG = 0x80CC7733;
    private static final int REBAKE_COLOR = 0xFFAAFFAA;
    private static final int CLOSE_COLOR = 0xFFFF9999;

    private StagePaletteMenuRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!StagePaletteMenu.isActive()) return;
        if (!games.brennan.dungeontrain.client.EditorStatusHudOverlay.isEditorMenusVisible()) return;
        if (EditorTypeMenuRenderer.menus().isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        BlockPos pos = StagePaletteMenu.anchor();
        Vec3 anchor = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (!games.brennan.dungeontrain.client.EditorMenusModeState.withinRange(anchor, cam)) return;
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
        if (worldScale != 1.0f) ps.scale(worldScale, worldScale, worldScale);

        drawRoot(ps, buffer, font);

        ps.popPose();
        buffer.endBatch(PANEL_QUAD);
        buffer.endBatch();
    }

    // ---------- layout ----------

    static int rowCount() {
        return StagePaletteMenu.LAYOUT.size();
    }

    static double halfHeight() {
        return rowCount() * ROW_H / 2.0;
    }

    /** Left edge of the cell area of a cells row (after the optional label column). */
    private static double cellsLeft(Row row) {
        return row.label().isEmpty() ? -HALF_W : -HALF_W + HALF_W * 2.0 * LABEL_FRACTION;
    }

    private static double cellWidth(Row row) {
        return (HALF_W - cellsLeft(row)) / Math.max(1, row.cells().size());
    }

    // ---------- drawing ----------

    private static void drawRoot(PoseStack ps, MultiBufferSource buffer, Font font) {
        double halfW = HALF_W;
        double halfH = halfHeight();
        double topY = halfH;
        Hit hovered = StagePaletteMenu.hovered();

        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, BACKDROP_COLOR);

        List<Row> rows = StagePaletteMenu.LAYOUT;
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            double rowTop = topY - r * ROW_H;
            double rowBottom = rowTop - ROW_H;
            double rowCY = rowTop - ROW_H / 2.0;
            if (r > 0) drawQuad(ps, buffer, -halfW, rowTop - 0.005, halfW, rowTop + 0.005, ROW_SEP_COLOR);
            switch (row.kind()) {
                case HEADER -> {
                    drawQuad(ps, buffer, -halfW, rowBottom, halfW, rowTop, HEADER_BG);
                    drawCenteredText(ps, buffer, font, "Stage palette: " + StagePaletteMenu.stageId(),
                        0, rowCY, HEADER_COLOR);
                }
                case TOOLBAR -> {
                    double split = -halfW + halfW * 2.0 * TOOLBAR_REBAKE_FRACTION;
                    if (hovered.kind() == CellKind.REBAKE) {
                        drawQuad(ps, buffer, -halfW + 0.005, rowBottom + 0.005, split - 0.005, rowTop - 0.005, HOVER_COLOR);
                    } else if (hovered.kind() == CellKind.CLOSE) {
                        drawQuad(ps, buffer, split + 0.005, rowBottom + 0.005, halfW - 0.005, rowTop - 0.005, HOVER_COLOR);
                    }
                    drawCenteredText(ps, buffer, font, "Re-bake (keeps overrides)", (-halfW + split) / 2.0, rowCY, REBAKE_COLOR);
                    drawCenteredText(ps, buffer, font, "X", (split + halfW) / 2.0, rowCY, CLOSE_COLOR);
                }
                case SUBHEADER -> drawCenteredText(ps, buffer, font, row.label(), 0, rowCY, HEADER_COLOR);
                case WOOD_HEADER, STONE_HEADER -> {
                    boolean wood = row.kind() == RowKind.WOOD_HEADER;
                    boolean locked = wood ? StagePaletteMenu.woodLocked() : StagePaletteMenu.stoneLocked();
                    CellKind mine = wood ? CellKind.WOOD_HEADER : CellKind.STONE_HEADER;
                    if (locked) drawQuad(ps, buffer, -halfW + 0.005, rowBottom + 0.005, halfW - 0.005, rowTop - 0.005, LOCKED_BG);
                    if (hovered.kind() == mine) drawQuad(ps, buffer, -halfW + 0.005, rowBottom + 0.005, halfW - 0.005, rowTop - 0.005, HOVER_COLOR);
                    String family = wood ? StagePaletteMenu.wood() : StagePaletteMenu.stone();
                    drawCenteredText(ps, buffer, font, row.label() + ": " + family
                        + (locked ? " (locked)" : "") + "  — click with a held block to set", 0, rowCY, HEADER_COLOR);
                }
                case CELLS -> drawCellsRow(ps, buffer, font, row, r, rowTop, rowBottom, rowCY, hovered);
                case STATUS -> drawCenteredText(ps, buffer, font, statusText(hovered), 0, rowCY,
                    hovered.kind() == CellKind.CELL ? NAME_COLOR : DIM_COLOR);
            }
        }
    }

    private static void drawCellsRow(PoseStack ps, MultiBufferSource buffer, Font font, Row row, int r,
                                     double rowTop, double rowBottom, double rowCY, Hit hovered) {
        if (!row.label().isEmpty()) {
            drawLeftText(ps, buffer, font, row.label(), -HALF_W + PAD_X, rowCY, DIM_COLOR);
        }
        double left = cellsLeft(row);
        double cw = cellWidth(row);
        for (int c = 0; c < row.cells().size(); c++) {
            String name = row.cells().get(c);
            double x0 = left + c * cw;
            double cx = x0 + cw / 2.0;
            if (hovered.kind() == CellKind.CELL && hovered.index() == r && hovered.secondary() == c) {
                drawQuad(ps, buffer, x0 + 0.005, rowBottom + 0.005, x0 + cw - 0.005, rowTop - 0.005, HOVER_COLOR);
            }
            StagePaletteSyncPacket.Entry e = StagePaletteMenu.entry(name);
            // placeholder icon → resolved icon, centred as a group.
            double group = ICON_SIZE * 2 + 0.16;
            double gx = cx - group / 2.0;
            MenuBlockIcons.drawBlockIcon(ps, buffer, DungeonTrain.MOD_ID + ":" + name, gx + ICON_SIZE / 2.0, rowCY, ICON_SIZE);
            drawCenteredText(ps, buffer, font, "→", gx + ICON_SIZE + 0.08, rowCY, ARROW_COLOR);
            if (e != null) {
                MenuBlockIcons.drawBlockIcon(ps, buffer, e.blockId(), gx + ICON_SIZE + 0.16 + ICON_SIZE / 2.0, rowCY, ICON_SIZE);
                if (e.overridden()) {
                    drawQuad(ps, buffer, x0 + 0.03, rowBottom + 0.02, x0 + cw - 0.03, rowBottom + 0.04, OVERRIDE_COLOR);
                }
            }
        }
    }

    private static String statusText(Hit hovered) {
        String name = StagePaletteMenu.cellName(hovered);
        if (name == null) return "Hover a cell · click with a held block to override · empty hand clears";
        StagePaletteSyncPacket.Entry e = StagePaletteMenu.entry(name);
        String label = Component.translatable("block." + DungeonTrain.MOD_ID + "." + name).getString();
        if (e == null) return label;
        return label + " → " + e.blockId() + (e.overridden() ? "  (override)" : "");
    }

    // ---------- hit testing (shared with StagePaletteMenuRaycast) ----------

    static Hit hitFor(double hitX, double hitY) {
        double halfW = HALF_W;
        double halfH = halfHeight();
        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) return Hit.NONE;
        int r = (int) Math.floor((halfH - hitY) / ROW_H);
        List<Row> rows = StagePaletteMenu.LAYOUT;
        if (r < 0 || r >= rows.size()) return Hit.NONE;
        Row row = rows.get(r);
        return switch (row.kind()) {
            case TOOLBAR -> hitX < -halfW + halfW * 2.0 * TOOLBAR_REBAKE_FRACTION
                ? new Hit(CellKind.REBAKE, -1, -1) : new Hit(CellKind.CLOSE, -1, -1);
            case WOOD_HEADER -> new Hit(CellKind.WOOD_HEADER, -1, -1);
            case STONE_HEADER -> new Hit(CellKind.STONE_HEADER, -1, -1);
            case CELLS -> {
                double left = cellsLeft(row);
                if (hitX < left) yield Hit.NONE;
                int c = (int) ((hitX - left) / cellWidth(row));
                yield c >= 0 && c < row.cells().size() ? new Hit(CellKind.CELL, r, c) : Hit.NONE;
            }
            default -> Hit.NONE;
        };
    }

    // ---------- text / quad helpers ----------

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
