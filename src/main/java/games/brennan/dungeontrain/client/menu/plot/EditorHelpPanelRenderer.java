package games.brennan.dungeontrain.client.menu.plot;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.EditorPanelFacing;
import games.brennan.dungeontrain.client.menu.MenuRenderStates;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
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

/**
 * Worldspace help panel that floats to the right of the first template-type
 * navigation menu. Static content — teaches the editor keybinds and links
 * to the wiki — derived entirely client-side, anchored at a fixed
 * world-space offset from the first nav menu in
 * {@link EditorTypeMenuRenderer#menus()}.
 *
 * <p>Shares the nav menu's fixed facing ({@link #basis}) and sits beside it on
 * the reader's left, far enough out that the two never overlap however wide
 * the nav menu grows ({@link #helpAnchor}).</p>
 *
 * <p>Layout constants are package-private so {@link EditorHelpPanelRaycast}
 * shares the same numbers for the wiki-button hit test.</p>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class EditorHelpPanelRenderer {

    /** Cell kinds the raycast / input handler need to identify. */
    public enum CellKind { NONE, WIKI_BUTTON, CLOSE_BUTTON }

    /** Resolved hover state — set each tick by {@link EditorHelpPanelRaycast}. */
    public record Hovered(CellKind cell) {
        public static final Hovered NONE = new Hovered(CellKind.NONE);
    }

    /** Same composite as the per-plot panel — alpha-blend, depth-test, no cull, no depth-write. */
    private static final RenderType PANEL_QUAD = RenderType.create(
        DungeonTrain.MOD_ID + ":editor_help_panel_quad",
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
    /** Fixed half-width — wide enough to fit the welcome line plus padding without truncation. */
    static final double HALF_W = 2.60;
    /** Clear air (panel-local units) between the nav menu's edge and the help panel's edge. */
    static final double NAV_GAP = 0.5;

    /**
     * Width of the close (X) cell at the right end of the header band. A square — one row tall by
     * one row wide — so the button reads as a corner control rather than a slice of the title.
     */
    static final double CLOSE_W = ROW_H;

    private static final int BACKDROP_COLOR = 0xC8000000;
    private static final int HEADER_BG = 0x60FFEEBB;
    private static final int ROW_SEP_COLOR = 0x40FFFFFF;
    /** Faint green band behind the wiki button — same family as the +New row tint. */
    private static final int BUTTON_BG = 0x4055FF55;
    private static final int HOVER_COLOR = 0x60FFCC33;
    /** Muted red behind the close cell — the one destructive-ish control on the panel. */
    private static final int CLOSE_BG = 0x50FF5555;

    private static final int HEADER_COLOR = 0xFFFFEEBB;
    private static final int BODY_COLOR = 0xFFFFFFFF;
    /** Slightly dimmed text for the keybind hint rows so the header reads as primary. */
    private static final int KEY_COLOR = 0xFFCCCCCC;
    /** Bright green text for the wiki button so it stands out as the actionable row. */
    private static final int BUTTON_COLOR = 0xFFAAFFAA;
    /** Close glyph, tinted to match its band. */
    private static final int CLOSE_COLOR = 0xFFFFDDDD;

    /**
     * The close glyph. Plain ASCII rather than {@code ✕} / {@code ✖}: nothing else in this mod
     * depends on the extended font, and {@code X} is also the keybind the panel itself teaches for
     * the editor menu.
     */
    private static final String CLOSE_GLYPH = "X";

    /** 8 rows total: header, 3 welcome lines, 3 keybind hints, wiki button. */
    static final int ROW_COUNT = 8;
    static final int ROW_WIKI_BUTTON = 7;

    private static volatile Hovered HOVERED = Hovered.NONE;

    private EditorHelpPanelRenderer() {}

    public static Hovered hovered() {
        return HOVERED;
    }

    public static void setHovered(Hovered h) {
        HOVERED = h == null ? Hovered.NONE : h;
    }

    public static double halfHeight() {
        return ROW_COUNT * ROW_H / 2.0;
    }

    /**
     * The first nav menu in the cached snapshot — its world position
     * anchors the help panel. Returns {@code null} when no nav menu is
     * cached (editor inactive or only companions present, which is a
     * transient state).
     */
    public static EditorTypeMenusPacket.Menu firstNavMenu() {
        for (EditorTypeMenusPacket.Menu m : EditorTypeMenuRenderer.menus()) {
            if (m.isNavMenu()) return m;
        }
        return null;
    }

    /** The help panel's fixed basis — the same door facing as the nav menu it sits beside. */
    public static Vec3[] basis(EditorTypeMenusPacket.Menu navMenu) {
        return EditorPanelFacing.doorPanel(EditorPanelFacing.isZRow(navMenu.activeCategoryId()));
    }

    /**
     * Help panel's world-space anchor: the nav menu's anchor pushed along the reader's left (the
     * shared basis's {@code -right}) by both panels' half-widths plus {@link #NAV_GAP}, so its right
     * edge clears the nav menu's left edge. Scaled by the world-space scale because both panels are
     * drawn scaled about their own anchors. Left is {@code +Z} for X-row categories and {@code -X}
     * for the tracks / dimensional-carriage Z-rows.
     */
    public static Vec3 helpAnchor(EditorTypeMenusPacket.Menu navMenu, Font font) {
        BlockPos pos = navMenu.worldPos();
        Vec3 navAnchor = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        double spacing = EditorTypeMenuRenderer.halfWidth(navMenu, font) + NAV_GAP + HALF_W;
        spacing *= ClientDisplayConfig.getWorldspaceScale();
        Vec3 right = basis(navMenu)[0];
        return navAnchor.subtract(right.scale(spacing));
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        // Master "Editor Menus" toggle — hide all world-space editor panels when off.
        if (!games.brennan.dungeontrain.client.EditorStatusHudOverlay.isEditorMenusVisible()) {
            HOVERED = Hovered.NONE;
            return;
        }
        // Closed by this player in this world (server-reported, persisted in the world save).
        if (EditorTypeMenuRenderer.helpPanelDismissed()) {
            HOVERED = Hovered.NONE;
            return;
        }
        EditorTypeMenusPacket.Menu navMenu = firstNavMenu();
        if (navMenu == null) {
            HOVERED = Hovered.NONE;
            return;
        }

        EditorHelpPanelRaycast.updateHovered();

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        Vec3 anchor = helpAnchor(navMenu, font);
        // Auto culls the board once you have walked away from the door it sits beside.
        if (!games.brennan.dungeontrain.client.EditorMenusModeState.withinRange(anchor, cam)) {
            HOVERED = Hovered.NONE;
            return;
        }
        drawPanel(ps, buffer, font, cam, anchor, basis(navMenu), hovered());

        buffer.endBatch(PANEL_QUAD);
        buffer.endBatch();
    }

    private static void drawPanel(
        PoseStack ps, MultiBufferSource buffer, Font font,
        Vec3 cam, Vec3 anchor, Vec3[] b, Hovered hovered
    ) {
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

        drawRows(ps, buffer, font, hovered);

        ps.popPose();
    }

    private static void drawRows(
        PoseStack ps, MultiBufferSource buffer, Font font, Hovered hovered
    ) {
        double halfW = HALF_W;
        double halfH = halfHeight();
        double topY = halfH;

        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, BACKDROP_COLOR);

        // Row 0 — header band.
        double headerTop = topY;
        double headerBottom = topY - ROW_H;
        double headerCY = (headerTop + headerBottom) / 2.0;
        drawQuad(ps, buffer, -halfW, headerBottom, halfW, headerTop, HEADER_BG);
        drawCenteredText(ps, buffer, font,
            Component.translatable("gui.dungeontrain.editor_help.title").getString(),
            0, headerCY, HEADER_COLOR);

        // Close (X) — top-right corner of the header band. Drawn after the title so it sits over
        // the header tint; the title stays centred on the full width, which reads fine because the
        // title is far shorter than the panel.
        double closeLeft = halfW - CLOSE_W;
        drawQuad(ps, buffer, closeLeft, headerBottom, halfW, headerTop, CLOSE_BG);
        if (hovered.cell == CellKind.CLOSE_BUTTON) {
            drawQuad(ps, buffer, closeLeft + 0.005, headerBottom + 0.005,
                halfW - 0.005, headerTop - 0.005, HOVER_COLOR);
        }
        drawCenteredText(ps, buffer, font, CLOSE_GLYPH,
            closeLeft + CLOSE_W / 2.0, headerCY, CLOSE_COLOR);

        // Rows 1..3 — welcome body.
        drawBodyRow(ps, buffer, font, topY, 1, "gui.dungeontrain.editor_help.welcome_1", BODY_COLOR);
        drawBodyRow(ps, buffer, font, topY, 2, "gui.dungeontrain.editor_help.welcome_2", BODY_COLOR);
        drawBodyRow(ps, buffer, font, topY, 3, "gui.dungeontrain.editor_help.welcome_3", BODY_COLOR);

        // Rows 4..6 — keybind hints.
        drawBodyRow(ps, buffer, font, topY, 4, "gui.dungeontrain.editor_help.key_x", KEY_COLOR);
        drawBodyRow(ps, buffer, font, topY, 5, "gui.dungeontrain.editor_help.key_c", KEY_COLOR);
        drawBodyRow(ps, buffer, font, topY, 6, "gui.dungeontrain.editor_help.key_z", KEY_COLOR);

        // Row 7 — wiki button.
        double btnTop = topY - ROW_WIKI_BUTTON * ROW_H;
        double btnBottom = btnTop - ROW_H;
        double btnCY = (btnTop + btnBottom) / 2.0;
        drawQuad(ps, buffer, -halfW, btnTop - 0.005, halfW, btnTop + 0.005, ROW_SEP_COLOR);
        drawQuad(ps, buffer, -halfW, btnBottom, halfW, btnTop, BUTTON_BG);
        if (hovered.cell == CellKind.WIKI_BUTTON) {
            drawQuad(ps, buffer, -halfW + 0.005, btnBottom + 0.005,
                halfW - 0.005, btnTop - 0.005, HOVER_COLOR);
        }
        drawCenteredText(ps, buffer, font,
            Component.translatable("gui.dungeontrain.editor_help.wiki_button").getString(),
            0, btnCY, BUTTON_COLOR);
    }

    private static void drawBodyRow(
        PoseStack ps, MultiBufferSource buffer, Font font,
        double topY, int rowIdx, String translationKey, int color
    ) {
        double rowTop = topY - rowIdx * ROW_H;
        double rowBottom = rowTop - ROW_H;
        double rowCY = (rowTop + rowBottom) / 2.0;
        drawQuad(ps, buffer, -HALF_W, rowTop - 0.005, HALF_W, rowTop + 0.005, ROW_SEP_COLOR);
        drawCenteredText(ps, buffer, font,
            Component.translatable(translationKey).getString(),
            0, rowCY, color);
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

    /** Map a panel-local hit at {@code (hitX, hitY)} to a {@link Hovered}. */
    public static Hovered hitFor(double hitX, double hitY) {
        double halfH = halfHeight();
        if (hitX < -HALF_W || hitX > HALF_W || hitY < -halfH || hitY > halfH) return Hovered.NONE;
        int rowFromTop = (int) Math.floor((halfH - hitY) / ROW_H);
        if (rowFromTop == 0 && hitX >= HALF_W - CLOSE_W) return new Hovered(CellKind.CLOSE_BUTTON);
        if (rowFromTop == ROW_WIKI_BUTTON) return new Hovered(CellKind.WIKI_BUTTON);
        return Hovered.NONE;
    }
}
