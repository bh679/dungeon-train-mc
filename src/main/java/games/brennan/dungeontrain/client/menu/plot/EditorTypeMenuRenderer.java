package games.brennan.dungeontrain.client.menu.plot;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer;
import games.brennan.dungeontrain.client.menu.MenuRenderStates;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
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
 * Client-side renderer for the floating template-type menus that float at
 * the start of every variant row in the active editor category. Each menu
 * lists every variant in its row with a name cell (left, teleport on click)
 * and a weight cell ({@code ×N} on the right, click=+1 / shift-click=-1).
 *
 * <p>Same world-space billboarded panel chrome as
 * {@link EditorPlotLabelsRenderer} — backdrop quad, cylindrical billboard
 * around world up, single SEE_THROUGH text pass. Layout constants are
 * package-private so {@link EditorTypeMenuRaycast} shares the same numbers
 * for hit detection.</p>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class EditorTypeMenuRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Cell kinds the raycast / input handler need to identify.
     *
     * <p>{@link #HEADER} represents the top row (the type-name banner).
     * Clicking it teleports to the first variant in the menu — useful as a
     * quick "go to the start of this row" jump.</p>
     */
    public enum CellKind { NONE, HEADER, NAME, WEIGHT }

    /**
     * Where the player's crosshair is currently pointing. {@code variantIdx}
     * is {@code -1} when the hit is on the header row (which is not a
     * per-variant cell).
     */
    public record Hovered(int menuIdx, int variantIdx, CellKind cell) {
        public static final Hovered NONE = new Hovered(-1, -1, CellKind.NONE);
    }

    /** Same composite as the per-plot panel — alpha-blend, depth-test, no cull, no depth-write. */
    private static final RenderType PANEL_QUAD = RenderType.create(
        DungeonTrain.MOD_ID + ":editor_type_menu_quad",
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
    static final double MIN_HALF_W = 1.10;
    static final double PAD_X = 0.10;
    /** Fraction of panel width allocated to the weight cell on rows that have one. */
    static final double WEIGHT_CELL_FRACTION = 0.25;
    /** Visible gap (panel-local units) between the per-plot panel and a companion type menu. */
    static final double COMPANION_GAP = 0.15;

    private static final int BACKDROP_COLOR = 0xC8000000;
    private static final int HOVER_COLOR = 0x60FFCC33;
    private static final int ROW_SEP_COLOR = 0x40FFFFFF;
    private static final int HEADER_BG = 0x60FFEEBB;
    /** Persistent green tint behind the row matching the player's current plot — same green family as the in-plot panel border. */
    private static final int ACTIVE_ROW_COLOR = 0x6055FF55;

    private static final int HEADER_COLOR = 0xFFFFEEBB;
    private static final int NAME_COLOR = 0xFFFFFFFF;
    private static final int WEIGHT_COLOR = 0xFFFFEEBB;

    private static volatile List<EditorTypeMenusPacket.Menu> CACHE = List.of();
    private static volatile Hovered HOVERED = Hovered.NONE;

    private EditorTypeMenuRenderer() {}

    public static void applySnapshot(EditorTypeMenusPacket packet) {
        if (packet.isEmpty()) {
            CACHE = List.of();
            HOVERED = Hovered.NONE;
            LOGGER.info("[DungeonTrain] EditorTypeMenus: snapshot cleared");
            return;
        }
        List<EditorTypeMenusPacket.Menu> menus = List.copyOf(packet.menus());
        CACHE = menus;
        EditorTypeMenusPacket.Menu first = menus.get(0);
        LOGGER.info("[DungeonTrain] EditorTypeMenus: client received {} menus (first: '{}' with {} variants @ {})",
            menus.size(), first.typeName(), first.variants().size(), first.worldPos());
    }

    public static List<EditorTypeMenusPacket.Menu> menus() {
        return CACHE;
    }

    public static Hovered hovered() {
        return HOVERED;
    }

    public static void setHovered(Hovered h) {
        HOVERED = h == null ? Hovered.NONE : h;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        List<EditorTypeMenusPacket.Menu> snapshot = CACHE;
        if (snapshot.isEmpty()) return;

        EditorTypeMenuRaycast.updateHovered();

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        Hovered hovered = HOVERED;
        for (int i = 0; i < snapshot.size(); i++) {
            EditorTypeMenusPacket.Menu menu = snapshot.get(i);
            BlockPos pos = menu.worldPos();
            Vec3 anchor = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            Hovered local = (hovered.menuIdx == i) ? hovered : Hovered.NONE;
            drawMenu(ps, buffer, font, cam, anchor, menu, local);
        }

        buffer.endBatch(PANEL_QUAD);
        buffer.endBatch();
    }

    /**
     * Half-width of the menu panel in world units. Scales to fit the longest
     * variant name plus its weight cell, clamped to {@link #MIN_HALF_W} so
     * short lists still read with comfortable padding.
     */
    public static double halfWidth(EditorTypeMenusPacket.Menu menu, Font font) {
        double headerW = font.width(menu.typeName()) * TEXT_SCALE + 2 * PAD_X;
        double maxNameW = 0;
        boolean anyWeight = false;
        for (EditorTypeMenusPacket.Variant v : menu.variants()) {
            double w = font.width(v.name()) * TEXT_SCALE + 2 * PAD_X;
            if (w > maxNameW) maxNameW = w;
            if (v.weight() != EditorPlotLabelsPacket.NO_WEIGHT) anyWeight = true;
        }
        // If any row has a weight cell, the name fills only (1 - WEIGHT_CELL_FRACTION)
        // of the panel; size the panel so the longest name fits in that fraction.
        double nameSpaceFraction = anyWeight ? (1.0 - WEIGHT_CELL_FRACTION) : 1.0;
        double scaledForName = maxNameW / nameSpaceFraction;
        double w = Math.max(MIN_HALF_W * 2.0, Math.max(headerW, scaledForName));
        return w / 2.0;
    }

    public static int rowCount(EditorTypeMenusPacket.Menu menu) {
        // header + N variants
        return 1 + menu.variants().size();
    }

    public static double halfHeight(EditorTypeMenusPacket.Menu menu) {
        return rowCount(menu) * ROW_H / 2.0;
    }

    /**
     * Panel-local +X shift applied to a companion menu so its left edge
     * sits just past the per-plot panel's right edge (with {@link #COMPANION_GAP}
     * of visible space). Returns 0 for non-companion menus.
     *
     * <p>The per-plot panel's half-width is looked up from the matching
     * inPlot label so the gap stays tight regardless of which template the
     * player is in. Falls back to {@link EditorPlotLabelsRenderer#MIN_HALF_W *
     * 2} if no inPlot entry is cached (shouldn't happen in normal flow but
     * keeps the renderer crash-free).</p>
     */
    public static double companionShiftX(EditorTypeMenusPacket.Menu menu, Font font) {
        if (!menu.isCompanion()) return 0;
        double perPlotHalfW = EditorPlotLabelsRenderer.MIN_HALF_W;
        for (EditorPlotLabelsPacket.Entry e : EditorPlotLabelsRenderer.entries()) {
            if (e.inPlot()) {
                perPlotHalfW = EditorPlotLabelsRenderer.halfWidth(e, font);
                break;
            }
        }
        return perPlotHalfW + COMPANION_GAP + halfWidth(menu, font);
    }

    /**
     * Map a panel-local hit at {@code (hitX, hitY)} to a {@link Hovered}
     * pair. Returns {@link Hovered#NONE} if the hit lies outside any
     * clickable cell or is on the header row.
     */
    public static Hovered hitFor(int menuIdx, EditorTypeMenusPacket.Menu menu, Font font,
                                 double hitX, double hitY) {
        double halfW = halfWidth(menu, font);
        double halfH = halfHeight(menu);
        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) return Hovered.NONE;

        int rows = rowCount(menu);
        int rowFromTop = (int) Math.floor((halfH - hitY) / ROW_H);
        if (rowFromTop < 0 || rowFromTop >= rows) return Hovered.NONE;

        // Row 0 is the header — clickable as a "teleport to first variant"
        // shortcut, with variantIdx=-1 (the dispatch resolves the actual
        // first variant from menu.variants().get(0)).
        if (rowFromTop == 0) return new Hovered(menuIdx, -1, CellKind.HEADER);

        int variantIdx = rowFromTop - 1;
        if (variantIdx >= menu.variants().size()) return Hovered.NONE;
        EditorTypeMenusPacket.Variant variant = menu.variants().get(variantIdx);

        boolean hasWeight = variant.weight() != EditorPlotLabelsPacket.NO_WEIGHT;
        if (hasWeight) {
            double weightCellLeft = halfW - (halfW * 2.0) * WEIGHT_CELL_FRACTION;
            if (hitX >= weightCellLeft) {
                return new Hovered(menuIdx, variantIdx, CellKind.WEIGHT);
            }
        }
        return new Hovered(menuIdx, variantIdx, CellKind.NAME);
    }

    // ---------- rendering ----------

    private static void drawMenu(
        PoseStack ps, MultiBufferSource buffer, Font font,
        Vec3 cam, Vec3 anchor,
        EditorTypeMenusPacket.Menu menu, Hovered hovered
    ) {
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

        // Same world-space scale used by every other in-world menu — applied
        // here so the type menu shrinks/grows uniformly with the X menu and
        // sibling editor panels. Matched on the input side by
        // {@link EditorTypeMenuRaycast}.
        float worldScale = (float) ClientDisplayConfig.getWorldspaceScale();
        if (worldScale != 1.0f) {
            ps.scale(worldScale, worldScale, worldScale);
        }

        // Companion menus share the per-plot panel's anchor + basis; shift
        // them in panel-local +X so they sit beside the panel like a second
        // column of a single extended UI. Non-companions render at
        // panel-local origin.
        double shiftX = companionShiftX(menu, font);
        if (shiftX != 0) {
            ps.translate(shiftX, 0, 0);
        }

        double halfW = halfWidth(menu, font);
        double halfH = halfHeight(menu);

        // Whole-panel backdrop.
        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, BACKDROP_COLOR);

        // Header row — yellow tint band + type name centred. Adds the
        // hover-tint overlay when the player aims at the header (whole row
        // is the click target — teleport-to-first-variant shortcut).
        double topY = halfH;
        double headerTop = topY;
        double headerBottom = headerTop - ROW_H;
        double headerCY = (headerTop + headerBottom) / 2.0;
        drawQuad(ps, buffer, -halfW, headerBottom, halfW, headerTop, HEADER_BG);
        if (hovered.cell == CellKind.HEADER) {
            drawQuad(ps, buffer, -halfW + 0.005, headerBottom + 0.005,
                halfW - 0.005, headerTop - 0.005, HOVER_COLOR);
        }
        drawCenteredText(ps, buffer, font, menu.typeName(), 0, headerCY, HEADER_COLOR);

        // Identify the player's currently active template (the per-plot
        // label flagged inPlot=true) so the matching variant row can be
        // highlighted with the same green family used by the in-plot panel
        // border. Empty when the player is outside every plot.
        String activeModelId = "";
        String activeModelName = "";
        for (EditorPlotLabelsPacket.Entry e : EditorPlotLabelsRenderer.entries()) {
            if (e.inPlot()) {
                activeModelId = e.modelId();
                activeModelName = e.modelName();
                break;
            }
        }

        // Variant rows.
        for (int vi = 0; vi < menu.variants().size(); vi++) {
            EditorTypeMenusPacket.Variant variant = menu.variants().get(vi);
            double rowTop = topY - (vi + 1) * ROW_H;
            double rowBottom = rowTop - ROW_H;
            double rowCY = (rowTop + rowBottom) / 2.0;

            // Row separator (top edge).
            drawQuad(ps, buffer, -halfW, rowTop - 0.005, halfW, rowTop + 0.005, ROW_SEP_COLOR);

            boolean hasWeight = variant.weight() != EditorPlotLabelsPacket.NO_WEIGHT;
            double weightCellLeft = halfW - (halfW * 2.0) * WEIGHT_CELL_FRACTION;
            double nameRight = hasWeight ? weightCellLeft : halfW;

            // Active-row tint for the variant matching the player's current
            // plot — drawn before hover so a hover-targeted active row shows
            // the yellow hover on top.
            boolean isActive = !activeModelId.isEmpty()
                && activeModelId.equals(variant.modelId())
                && activeModelName.equals(variant.modelName());
            if (isActive) {
                drawQuad(ps, buffer, -halfW + 0.005, rowBottom + 0.005,
                    halfW - 0.005, rowTop - 0.005, ACTIVE_ROW_COLOR);
            }

            // Hover highlight on the active cell.
            boolean nameHover = hovered.variantIdx == vi && hovered.cell == CellKind.NAME;
            boolean weightHover = hovered.variantIdx == vi && hovered.cell == CellKind.WEIGHT;
            if (nameHover) {
                drawQuad(ps, buffer, -halfW + 0.005, rowBottom + 0.005,
                    nameRight - 0.005, rowTop - 0.005, HOVER_COLOR);
            }
            if (weightHover) {
                drawQuad(ps, buffer, weightCellLeft + 0.005, rowBottom + 0.005,
                    halfW - 0.005, rowTop - 0.005, HOVER_COLOR);
            }

            // Name (left-aligned within its cell).
            double nameCX = (-halfW + nameRight) / 2.0;
            drawCenteredText(ps, buffer, font, variant.name(), nameCX, rowCY, NAME_COLOR);

            if (hasWeight) {
                double weightCX = (weightCellLeft + halfW) / 2.0;
                drawCenteredText(ps, buffer, font, "×" + variant.weight(),
                    weightCX, rowCY, WEIGHT_COLOR);
            }
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
