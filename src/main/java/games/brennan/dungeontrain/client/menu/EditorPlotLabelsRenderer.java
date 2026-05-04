package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
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
import org.slf4j.Logger;

import java.util.List;

/**
 * Client-side renderer for the floating editor-plot control panels. Each panel
 * is a small world-space billboard above a stamped template plot, showing the
 * model name plus interactive controls (weight arrows, save / reset / clear,
 * and template-specific buttons like Contents for carriages).
 *
 * <p>Cylindrical billboard around world-up so the panel rotates to face the
 * camera horizontally but stays upright in Y — same basis the older labels
 * used.</p>
 *
 * <p>Layout (top → bottom, only the rows applicable to the entry's category
 * are rendered):
 * <ol>
 *   <li><b>Name</b> — always.</li>
 *   <li><b>Weight</b> — when {@code weight != NO_WEIGHT}: {@code [-] xN [+]}.</li>
 *   <li><b>Actions</b> — when the entry has an actionable category
 *       (CARRIAGES / CONTENTS / TRACKS pillars/adjuncts/tunnels):
 *       {@code [Save 70%][R 15%][C 15%]}.</li>
 *   <li><b>Contents</b> — CARRIAGES only.</li>
 * </ol></p>
 *
 * <p>All layout constants are package-private so
 * {@link games.brennan.dungeontrain.client.menu.plot.EditorPlotPanelRaycast}
 * shares the same numbers — the raycast hit math and the visible cells must
 * match exactly or the wrong cell highlights / dispatches.</p>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class EditorPlotLabelsRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Cell kinds the raycast and input handler need to identify. Order doesn't
     * matter — only the values are referenced by name.
     */
    public enum CellKind {
        NONE,
        /** Name row — clicking teleports the player to this template's plot (lands on top by default). Always clickable. */
        NAME,
        WEIGHT_DEC,
        WEIGHT_INC,
        ACTION_SAVE,
        ACTION_RESET,
        ACTION_CLEAR,
        BUTTON_CONTENTS,
        /** "Enter" row — clicking teleports the player INSIDE the plot (under the cage). Visible only when already inPlot. */
        BUTTON_ENTER_INSIDE
    }

    /** Where the player's crosshair is currently pointing. */
    public record Hovered(int entryIndex, CellKind cell) {
        public static final Hovered NONE = new Hovered(-1, CellKind.NONE);
    }

    /** Same composite as PartPositionMenuRenderer.PANEL_QUAD. */
    private static final RenderType PANEL_QUAD = RenderType.create(
        DungeonTrain.MOD_ID + ":editor_plot_label_quad",
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

    /** Glyph→world scale. Vanilla nameplate is 0.025 — bumped for editor reading. */
    static final double TEXT_SCALE = 0.025;
    /** Height of one row (world units). */
    static final double ROW_H = 0.30;
    /** Min half-width so a 24-row panel still fits the "Save"/"Contents" button labels. */
    public static final double MIN_HALF_W = 0.85;
    /** Horizontal padding inside each row. */
    static final double PAD_X = 0.10;

    /** Backdrop fill for the whole panel. */
    private static final int BACKDROP_COLOR = 0xC8000000;
    /** Tint fill behind the hovered cell — copied from the part menu's hover yellow. */
    private static final int HOVER_COLOR = 0x60FFCC33;
    /** Subtle row separator. */
    private static final int ROW_SEP_COLOR = 0x40FFFFFF;
    /** Soft tint behind the action / contents buttons so they look pressable. */
    private static final int BUTTON_BG = 0x40FFEEBB;
    /** Green border drawn around the panel when the player is inside this plot. */
    private static final int BORDER_COLOR = 0xFF55FF55;
    /**
     * Border thickness in world units. Sits OUTSIDE the panel's
     * {@code ±halfW / ±halfH} bounding rectangle so it never overlaps the
     * dark backdrop fill — visually a frame around the panel.
     */
    private static final double BORDER_THICKNESS = 0.04;

    private static final int NAME_COLOR = 0xFFFFFFFF;
    private static final int WEIGHT_COLOR = 0xFFFFEEBB;
    private static final int ARROW_COLOR = 0xFFFFFFFF;
    private static final int BUTTON_TEXT_COLOR = 0xFFFFFFFF;
    private static final int BUTTON_TEXT_DIM_COLOR = 0xFFAAAAAA;

    private static volatile List<EditorPlotLabelsPacket.Entry> CACHE = List.of();
    private static volatile Hovered HOVERED = Hovered.NONE;

    private EditorPlotLabelsRenderer() {}

    /** Called from the packet handler on the client thread. */
    public static void applySnapshot(EditorPlotLabelsPacket packet) {
        if (packet.isEmpty()) {
            CACHE = List.of();
            HOVERED = Hovered.NONE;
            LOGGER.info("[DungeonTrain] EditorPlotLabels: snapshot cleared");
            return;
        }
        List<EditorPlotLabelsPacket.Entry> entries = List.copyOf(packet.entries());
        CACHE = entries;
        EditorPlotLabelsPacket.Entry first = entries.get(0);
        LOGGER.info("[DungeonTrain] EditorPlotLabels: client received {} entries (first: '{}' weight={} @ {})",
            entries.size(), first.name(), first.weight(), first.worldPos());
    }

    /** Read by the raycast / input handler. */
    public static List<EditorPlotLabelsPacket.Entry> entries() {
        return CACHE;
    }

    /** Read the latest hover (set by the raycast each frame). */
    public static Hovered hovered() {
        return HOVERED;
    }

    /** Set by the raycast each frame. */
    public static void setHovered(Hovered h) {
        HOVERED = h == null ? Hovered.NONE : h;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        List<EditorPlotLabelsPacket.Entry> snapshot = CACHE;
        if (snapshot.isEmpty()) return;

        // Refresh hover from the camera frame so the highlight tracks the
        // crosshair without a one-tick lag, mirroring PartPositionMenuRenderer.
        games.brennan.dungeontrain.client.menu.plot.EditorPlotPanelRaycast.updateHovered();

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        Hovered hovered = HOVERED;
        for (int i = 0; i < snapshot.size(); i++) {
            EditorPlotLabelsPacket.Entry entry = snapshot.get(i);
            BlockPos pos = entry.worldPos();
            Vec3 anchor = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            CellKind hitCell = (hovered.entryIndex == i) ? hovered.cell : CellKind.NONE;
            drawPanel(ps, buffer, font, cam, anchor, entry, hitCell);
        }

        buffer.endBatch(PANEL_QUAD);
        buffer.endBatch();
    }

    /**
     * Compute the half-width of {@code entry}'s panel in world units. Same
     * formula used by the renderer and the raycast so cell rectangles agree.
     */
    public static double halfWidth(EditorPlotLabelsPacket.Entry entry, Font font) {
        double nameW = font.width(entry.name()) * TEXT_SCALE + 2 * PAD_X;
        double w = Math.max(MIN_HALF_W * 2.0, nameW);
        return w / 2.0;
    }

    /**
     * Number of rows visible for {@code entry}. Always ≥ 1 (name).
     *
     * <p>The weight DISPLAY row stays visible whenever the entry has a weight
     * pool, so a player flying along the row can still see each plot's pick
     * weight at a glance. The interactive arrows + the action / contents
     * rows only render when {@code entry.inPlot()} is true — the player has
     * to step into a cage to edit it.</p>
     */
    public static int rowCount(EditorPlotLabelsPacket.Entry entry) {
        int rows = 1; // name
        if (entry.weight() != EditorPlotLabelsPacket.NO_WEIGHT) rows++;
        if (hasEnterRow(entry)) rows++;
        if (hasActionRow(entry)) rows++;
        if (hasContentsButton(entry)) rows++;
        return rows;
    }

    /** "Enter" row — visible only when the player is already inside this plot. */
    public static boolean hasEnterRow(EditorPlotLabelsPacket.Entry entry) {
        return entry.inPlot() && !entry.category().isEmpty();
    }

    public static double halfHeight(EditorPlotLabelsPacket.Entry entry) {
        return rowCount(entry) * ROW_H / 2.0;
    }

    /**
     * True when the entry should show the Save/Reset/Clear action row —
     * requires both an actionable category AND the player being inside this
     * specific plot.
     */
    public static boolean hasActionRow(EditorPlotLabelsPacket.Entry entry) {
        if (!entry.inPlot()) return false;
        String c = entry.category();
        return "CARRIAGES".equals(c) || "CONTENTS".equals(c) || "TRACKS".equals(c);
    }

    /** Contents button — carriages only, gated on the player being inside the plot. */
    public static boolean hasContentsButton(EditorPlotLabelsPacket.Entry entry) {
        return entry.inPlot() && "CARRIAGES".equals(entry.category());
    }

    /** True when the weight arrows (interactive [-] / [+]) should render and accept clicks. */
    public static boolean hasWeightArrows(EditorPlotLabelsPacket.Entry entry) {
        return entry.inPlot() && entry.weight() != EditorPlotLabelsPacket.NO_WEIGHT;
    }

    /**
     * Build a cylindrical-billboard basis facing {@code cam} from {@code anchor}.
     * Shared by renderer and raycast so the click hit math matches the visible
     * panel exactly. Returns {@code [right, up, normal]}.
     */
    public static Vec3[] basis(Vec3 anchor, Vec3 cam) {
        Vec3 toCam = cam.subtract(anchor);
        Vec3 horiz = new Vec3(toCam.x, 0, toCam.z);
        Vec3 normal = horiz.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : horiz.normalize();
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 right = up.cross(normal).normalize();
        return new Vec3[]{right, up, normal};
    }

    /**
     * Map a panel-local hit at {@code (hitX, hitY)} to a {@link CellKind}.
     * Returns {@link CellKind#NONE} if the hit lies outside any clickable cell.
     */
    public static CellKind cellAt(EditorPlotLabelsPacket.Entry entry, Font font,
                                  double hitX, double hitY) {
        double halfW = halfWidth(entry, font);
        double halfH = halfHeight(entry);
        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) return CellKind.NONE;

        int rows = rowCount(entry);
        // Row 0 is the topmost (name); subsequent rows go down.
        // y range for row N: [halfH - (N+1)*ROW_H, halfH - N*ROW_H].
        int rowFromTop = (int) Math.floor((halfH - hitY) / ROW_H);
        if (rowFromTop < 0 || rowFromTop >= rows) return CellKind.NONE;

        // Row 0: name — clickable teleport target (only for actionable
        // categories; parts plots have an empty category and stay
        // non-interactive on the name row, matching their lack of action
        // buttons).
        if (rowFromTop == 0) {
            return entry.category().isEmpty() ? CellKind.NONE : CellKind.NAME;
        }

        // Walk through optional rows in display order to figure out what this row IS.
        int next = 1;
        if (entry.weight() != EditorPlotLabelsPacket.NO_WEIGHT) {
            if (rowFromTop == next) {
                // Weight row is always visible (display only) but arrow cells
                // are only clickable when the player is inside the plot.
                return hasWeightArrows(entry) ? weightRowCell(hitX, halfW) : CellKind.NONE;
            }
            next++;
        }
        if (hasEnterRow(entry)) {
            if (rowFromTop == next) return CellKind.BUTTON_ENTER_INSIDE;
            next++;
        }
        if (hasActionRow(entry)) {
            if (rowFromTop == next) return actionRowCell(hitX, halfW);
            next++;
        }
        if (hasContentsButton(entry)) {
            if (rowFromTop == next) return CellKind.BUTTON_CONTENTS;
        }
        return CellKind.NONE;
    }

    private static CellKind weightRowCell(double hitX, double halfW) {
        // Decrement on the left third; increment on the right third; the middle
        // (number display) is non-interactive.
        double third = (halfW * 2.0) / 3.0;
        if (hitX < -halfW + third) return CellKind.WEIGHT_DEC;
        if (hitX > halfW - third) return CellKind.WEIGHT_INC;
        return CellKind.NONE;
    }

    private static CellKind actionRowCell(double hitX, double halfW) {
        double w = halfW * 2.0;
        // Save 70%, Reset 15%, Clear 15% — left-to-right.
        double saveR = -halfW + 0.70 * w;
        double resetR = -halfW + 0.85 * w;
        if (hitX < saveR) return CellKind.ACTION_SAVE;
        if (hitX < resetR) return CellKind.ACTION_RESET;
        return CellKind.ACTION_CLEAR;
    }

    // ---------- rendering ----------

    private static void drawPanel(
        PoseStack ps, MultiBufferSource buffer, Font font,
        Vec3 cam, Vec3 anchor,
        EditorPlotLabelsPacket.Entry entry, CellKind hovered
    ) {
        Vec3[] b = basis(anchor, cam);
        Vec3 right = b[0], up = b[1], normal = b[2];

        ps.pushPose();
        ps.translate(anchor.x - cam.x, anchor.y - cam.y, anchor.z - cam.z);
        Matrix3f basis = new Matrix3f(
            (float) right.x, (float) right.y, (float) right.z,
            (float) up.x, (float) up.y, (float) up.z,
            (float) normal.x, (float) normal.y, (float) normal.z
        );
        ps.mulPose(new Quaternionf().setFromNormalized(basis));

        // Same world-space scale used by every other in-world menu — applied
        // here so the panel shrinks/grows uniformly with the X menu, parts
        // panel, and block-variant menu. Matched on the input side by
        // {@link games.brennan.dungeontrain.client.menu.plot.EditorPlotPanelRaycast}.
        float worldScale = (float) ClientDisplayConfig.getWorldspaceScale();
        if (worldScale != 1.0f) {
            ps.scale(worldScale, worldScale, worldScale);
        }

        double halfW = halfWidth(entry, font);
        double halfH = halfHeight(entry);

        // Whole-panel backdrop.
        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, BACKDROP_COLOR);

        // Green border when the player is inside this plot — sits OUTSIDE
        // the backdrop's ±halfW/±halfH rectangle as a frame, so it never
        // overlaps the dark panel fill. Top and bottom span the full
        // border-extended width; left and right fill only the gap between
        // them so the four quads don't double up at the corners.
        if (entry.inPlot()) {
            double t = BORDER_THICKNESS;
            // top (full width incl. corners)
            drawQuad(ps, buffer, -halfW - t, halfH, halfW + t, halfH + t, BORDER_COLOR);
            // bottom (full width incl. corners)
            drawQuad(ps, buffer, -halfW - t, -halfH - t, halfW + t, -halfH, BORDER_COLOR);
            // left (between top + bottom)
            drawQuad(ps, buffer, -halfW - t, -halfH, -halfW, halfH, BORDER_COLOR);
            // right (between top + bottom)
            drawQuad(ps, buffer, halfW, -halfH, halfW + t, halfH, BORDER_COLOR);
        }

        int rowIdx = 0;
        // Name row (always). Hover-highlight when the player is aiming at it
        // and the row is teleport-clickable (i.e. the entry has a category).
        double topY = halfH;
        double rowTop = topY - rowIdx * ROW_H;
        double rowBottom = rowTop - ROW_H;
        if (hovered == CellKind.NAME) {
            drawQuad(ps, buffer, -halfW + 0.005, rowBottom + 0.005,
                halfW - 0.005, rowTop - 0.005, HOVER_COLOR);
        }
        drawCenteredText(ps, buffer, font, entry.name(),
            0, (rowTop + rowBottom) / 2.0, NAME_COLOR);
        rowIdx++;

        // Weight row — display always when there's a weight pool; arrows only
        // when inside the plot (the player has to step into the cage to edit).
        if (entry.weight() != EditorPlotLabelsPacket.NO_WEIGHT) {
            double wTop = topY - rowIdx * ROW_H;
            double wBot = wTop - ROW_H;
            double wCY = (wTop + wBot) / 2.0;
            drawQuad(ps, buffer, -halfW, wTop - 0.005, halfW, wTop + 0.005, ROW_SEP_COLOR);

            if (hasWeightArrows(entry)) {
                double third = (halfW * 2.0) / 3.0;
                double leftR = -halfW + third;
                double rightL = halfW - third;
                if (hovered == CellKind.WEIGHT_DEC) {
                    drawQuad(ps, buffer, -halfW + 0.005, wBot + 0.005, leftR - 0.005, wTop - 0.005, HOVER_COLOR);
                } else if (hovered == CellKind.WEIGHT_INC) {
                    drawQuad(ps, buffer, rightL + 0.005, wBot + 0.005, halfW - 0.005, wTop - 0.005, HOVER_COLOR);
                }
                drawCenteredText(ps, buffer, font, "-", (-halfW + leftR) / 2.0, wCY, ARROW_COLOR);
                drawCenteredText(ps, buffer, font, "+", (rightL + halfW) / 2.0, wCY, ARROW_COLOR);
            }
            drawCenteredText(ps, buffer, font, "×" + entry.weight(), 0, wCY, WEIGHT_COLOR);
            rowIdx++;
        }

        // Enter row — full-width "Enter" button, visible only when the
        // player is already inside the plot. Clicking dispatches
        // EditorPlotActionPacket(ENTER_INSIDE) so the player teleports to
        // the plot floor (the default teleport now lands on top).
        if (hasEnterRow(entry)) {
            double eTop = topY - rowIdx * ROW_H;
            double eBot = eTop - ROW_H;
            double eCY = (eTop + eBot) / 2.0;
            drawQuad(ps, buffer, -halfW, eTop - 0.005, halfW, eTop + 0.005, ROW_SEP_COLOR);
            int bg = hovered == CellKind.BUTTON_ENTER_INSIDE ? HOVER_COLOR : BUTTON_BG;
            drawQuad(ps, buffer, -halfW + 0.01, eBot + 0.005, halfW - 0.01, eTop - 0.005, bg);
            drawCenteredText(ps, buffer, font, "Enter", 0, eCY, BUTTON_TEXT_COLOR);
            rowIdx++;
        }

        // Action row (Save / R / C).
        if (hasActionRow(entry)) {
            double aTop = topY - rowIdx * ROW_H;
            double aBot = aTop - ROW_H;
            double aCY = (aTop + aBot) / 2.0;
            double w = halfW * 2.0;
            double saveR = -halfW + 0.70 * w;
            double resetR = -halfW + 0.85 * w;
            double saveCX = (-halfW + saveR) / 2.0;
            double resetCX = (saveR + resetR) / 2.0;
            double clearCX = (resetR + halfW) / 2.0;

            // Row separator (top edge).
            drawQuad(ps, buffer, -halfW, aTop - 0.005, halfW, aTop + 0.005, ROW_SEP_COLOR);

            // Faint button backgrounds so they look pressable, brighten on hover.
            int saveBg = hovered == CellKind.ACTION_SAVE ? HOVER_COLOR : BUTTON_BG;
            int resetBg = hovered == CellKind.ACTION_RESET ? HOVER_COLOR : BUTTON_BG;
            int clearBg = hovered == CellKind.ACTION_CLEAR ? HOVER_COLOR : BUTTON_BG;
            drawQuad(ps, buffer, -halfW + 0.01, aBot + 0.005, saveR - 0.005, aTop - 0.005, saveBg);
            drawQuad(ps, buffer, saveR + 0.005, aBot + 0.005, resetR - 0.005, aTop - 0.005, resetBg);
            drawQuad(ps, buffer, resetR + 0.005, aBot + 0.005, halfW - 0.01, aTop - 0.005, clearBg);

            drawCenteredText(ps, buffer, font, "Save", saveCX, aCY, BUTTON_TEXT_COLOR);
            drawCenteredText(ps, buffer, font, "R", resetCX, aCY, BUTTON_TEXT_COLOR);
            drawCenteredText(ps, buffer, font, "C", clearCX, aCY, BUTTON_TEXT_COLOR);
            rowIdx++;
        }

        // Contents row (carriages).
        if (hasContentsButton(entry)) {
            double cTop = topY - rowIdx * ROW_H;
            double cBot = cTop - ROW_H;
            double cCY = (cTop + cBot) / 2.0;
            int bg = hovered == CellKind.BUTTON_CONTENTS ? HOVER_COLOR : BUTTON_BG;
            drawQuad(ps, buffer, -halfW, cTop - 0.005, halfW, cTop + 0.005, ROW_SEP_COLOR);
            drawQuad(ps, buffer, -halfW + 0.01, cBot + 0.005, halfW - 0.01, cTop - 0.005, bg);
            drawCenteredText(ps, buffer, font, "Contents", 0, cCY, BUTTON_TEXT_COLOR);
        }

        ps.popPose();
    }

    private static void drawCenteredText(
        PoseStack ps, MultiBufferSource buffer, Font font,
        String text, double worldX, double worldY, int colour
    ) {
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

    private static void drawQuad(
        PoseStack ps, MultiBufferSource buffer,
        double x1, double y1, double x2, double y2, int argb
    ) {
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
