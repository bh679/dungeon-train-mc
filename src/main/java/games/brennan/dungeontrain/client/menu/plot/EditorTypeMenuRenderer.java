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
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.slf4j.Logger;

import java.util.List;

/**
 * Client-side renderer for the floating template-type menus that anchor at
 * the start of every variant row in the active editor category.
 *
 * <p>Row-start menus ({@link EditorTypeMenusPacket.Menu#isNavMenu()}) render
 * a wide <b>template navigation panel</b>: a category bar across the top,
 * a type-tab strip immediately below, and the variant list of the menu's
 * own type expanded inside its column while every other tab collapses to
 * just its type-name header. The collapsed tabs remain clickable so a
 * player at the floor row can jump to the walls row without typing a
 * command. The expanded column also hosts the {@code + New} footer
 * (unchanged dispatch).</p>
 *
 * <p>Companion menus ({@code isCompanion=true}, e.g. the sub-variants
 * column anchored next to a per-plot panel) keep their pre-nav single
 * column layout — no category bar, no tab strip.</p>
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
     * <p>{@link #HEADER} represents the top row of a companion menu (the
     * type-name banner); clicking it teleports to the first variant in the
     * menu. Nav menus do not use HEADER — the equivalent action is a click
     * on the expanded {@link #TYPE_TAB} (same target, just expressed through
     * the tab strip).</p>
     *
     * <p>{@link #NEW} is the bottom-row "+ New" button — clicking opens the
     * keyboard worldspace menu pre-positioned at the source picker / typing
     * prompt for this menu's category.</p>
     *
     * <p>{@link #CATEGORY} is one of the category-bar buttons on a nav menu;
     * click dispatch runs {@code /dt editor <id>}.</p>
     *
     * <p>{@link #TYPE_TAB} is one of the tab cells in the strip below the
     * category bar on a nav menu; click dispatch teleports to the first
     * variant of that type (collapsed tabs jump to a different row, the
     * expanded tab repeats the current row's first variant).</p>
     *
     * <p>{@link #SUB_VARIANT} is one of the horizontal cells drawn to the
     * right of the active variant's row on a CONTENTS nav menu — inlines
     * the sub-variants list that used to render as a separate floating
     * companion. Click dispatch teleports to the sub-variant.</p>
     */
    public enum CellKind {
        NONE, HEADER, NAME, WEIGHT, NEW, CATEGORY, TYPE_TAB, SUB_VARIANT,
        /** Package row — clicking activates that package. */
        PKG_NAME,
        /** Package Save cell — falls through to the X-menu's flat package screen for typing. */
        PKG_SAVE,
        /** Package Open cell — opens the package's working folder in the OS file manager. */
        PKG_OPEN,
        /** Package Enable/Disable cell — toggles the package's enabled flag. */
        PKG_ENABLE,
        /** Top-row Reload cell — runs {@code dungeontrain editor import}. */
        PKG_RELOAD,
        /** Top-row Open Packages cell — opens the dtpacks root. */
        PKG_OPEN_FOLDER
    }

    /**
     * Where the player's crosshair is currently pointing. {@code variantIdx}
     * is {@code -1} when the hit is on a non-variant cell. {@code slotIdx}
     * carries the index into {@link EditorTypeMenusPacket.Menu#categoryBar()}
     * for {@link CellKind#CATEGORY} hits and the index into
     * {@link EditorTypeMenusPacket.Menu#typeStrip()} for
     * {@link CellKind#TYPE_TAB} hits; {@code -1} otherwise.
     */
    public record Hovered(int menuIdx, int variantIdx, CellKind cell, int slotIdx) {
        public static final Hovered NONE = new Hovered(-1, -1, CellKind.NONE, -1);

        /** Convenience constructor for non-slot cells (NAME, WEIGHT, HEADER, NEW). */
        public Hovered(int menuIdx, int variantIdx, CellKind cell) {
            this(menuIdx, variantIdx, cell, -1);
        }
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
    /** Minimum width of a collapsed tab column — keeps single-character type names readable. */
    static final double COLLAPSED_TAB_MIN_W = 0.55;
    /** Faint vertical line between adjacent type-tab columns for visual structure. */
    private static final double COLUMN_DIVIDER_W = 0.01;
    /** Reserved minimum width of the right-hand sub-variant area on every nav menu — keeps the panel wide enough that the sub-variants row reads as part of the menu, not as overflow. */
    static final double SUB_VARIANT_AREA_MIN_W = 2.20;
    /** Minimum width of a single sub-variant cell — keeps short ids readable next to the variant row. */
    private static final double SUB_VARIANT_CELL_MIN_W = 0.70;
    /** Horizontal gap between the expanded column and the first sub-variant cell. */
    private static final double SUB_VARIANT_GAP = 0.10;

    private static final int BACKDROP_COLOR = 0xC8000000;
    private static final int HOVER_COLOR = 0x60FFCC33;
    private static final int ROW_SEP_COLOR = 0x40FFFFFF;
    private static final int COLUMN_SEP_COLOR = 0x30FFFFFF;
    private static final int HEADER_BG = 0x60FFEEBB;
    /** Stronger green band behind the active category button — distinct from active-row tint. */
    private static final int ACTIVE_CATEGORY_BG = 0x8055FF55;
    /** Dim band behind every collapsed tab so they read as inactive columns. */
    private static final int COLLAPSED_TAB_BG = 0x60303030;
    /** Dim band behind every sub-variant cell so the horizontal row reads as a distinct affordance. */
    private static final int SUB_VARIANT_CELL_BG = 0x60303030;
    /** Faint tint behind the empty sub-variant area when no children exist — same dim grey as a collapsed tab but lower alpha. */
    private static final int SUB_VARIANT_AREA_BG = 0x30303030;
    /** Persistent green tint behind the row matching the player's current plot — same green family as the in-plot panel border. */
    private static final int ACTIVE_ROW_COLOR = 0x6055FF55;
    /** Subtle blue band behind every row whose variant has a user-authored file under {@code config/dungeontrain/user/...}. Drawn below the active-row tint so the active blue-and-green merge reads as "active user variant". */
    private static final int USER_ROW_BG = 0x405599FF;
    /** Subtle orange band behind every row whose variant came from an import package and hasn't been edited locally. Takes precedence over the blue user tint so a freshly-imported variant reads as "imported" until the player saves over it. */
    private static final int IMPORTED_ROW_BG = 0x40FF9933;
    /** Faint green band behind the bottom "+ New" row. Lower alpha than {@link #ACTIVE_ROW_COLOR} so the two never read as the same row. */
    private static final int NEW_ROW_BG = 0x4055FF55;

    private static final int HEADER_COLOR = 0xFFFFEEBB;
    private static final int NAME_COLOR = 0xFFFFFFFF;
    private static final int WEIGHT_COLOR = 0xFFFFEEBB;
    /** Bright green text for the "+ New" label so it stands out from variant rows. */
    private static final int NEW_COLOR = 0xFFAAFFAA;
    /** Slightly dimmed text for collapsed tabs so the expanded tab reads as primary. */
    private static final int COLLAPSED_TAB_COLOR = 0xFFCCCCCC;
    /** Bright white text on the active category button — boosted contrast over the green backdrop. */
    private static final int ACTIVE_CATEGORY_COLOR = 0xFFFFFFFF;
    /** Slightly dimmed text on inactive category buttons. */
    private static final int CATEGORY_COLOR = 0xFFCCCCCC;
    /** Label rendered in the bottom-row New button. */
    private static final String NEW_LABEL = "+ New";

    private static volatile List<EditorTypeMenusPacket.Menu> CACHE = List.of();
    private static volatile Hovered HOVERED = Hovered.NONE;

    /**
     * Sticky billboard basis for the package menu — captured on the first
     * render frame after the menu appears, reused every frame after. Unlike
     * the cylindrical billboards used elsewhere, the package panel pins its
     * initial orientation toward the player's spawn-in camera and stays
     * fixed thereafter, so it reads as a stationary signpost rather than a
     * follower panel. Cleared on editor exit (empty snapshot) so re-entry
     * recaptures from the new camera.
     */
    private static volatile Vec3[] PACKAGE_BASIS = null;

    private EditorTypeMenuRenderer() {}

    /**
     * Returns the billboard basis to use for {@code menu}. For the package
     * menu this is sticky — captured on first call, reused thereafter.
     * For every other menu kind this is the live cylindrical billboard.
     *
     * <p>Shared by the renderer and {@link EditorTypeMenuRaycast} so the
     * hit-test plane matches the visible panel exactly even after the
     * camera moves.</p>
     */
    public static Vec3[] basisFor(EditorTypeMenusPacket.Menu menu, Vec3 anchor, Vec3 cam) {
        if (!menu.isPackageMenu()) {
            return EditorPlotLabelsRenderer.basis(anchor, cam);
        }
        Vec3[] cached = PACKAGE_BASIS;
        if (cached != null) return cached;
        Vec3[] fresh = EditorPlotLabelsRenderer.basis(anchor, cam);
        PACKAGE_BASIS = fresh;
        return fresh;
    }

    /**
     * Wipe the renderer cache on world quit. Without this, the static
     * {@link #CACHE} would survive across worlds in the integrated server and
     * the floating type menus would keep rendering after the player quits to
     * title. Symmetric with {@link EditorPlotLabelsRenderer#onLoggingOut}.
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        applySnapshot(EditorTypeMenusPacket.empty());
    }

    public static void applySnapshot(EditorTypeMenusPacket packet) {
        if (packet.isEmpty()) {
            CACHE = List.of();
            HOVERED = Hovered.NONE;
            PACKAGE_BASIS = null;
            LOGGER.info("[DungeonTrain] EditorTypeMenus: snapshot cleared");
            return;
        }
        List<EditorTypeMenusPacket.Menu> menus = List.copyOf(packet.menus());
        CACHE = menus;
        // Keep PACKAGE_BASIS sticky across snapshots that still carry a
        // package menu (so category switches don't reorient the panel); drop
        // it if the new snapshot has no package menu, so the next appearance
        // recaptures from the player's current camera.
        boolean hasPackageMenu = false;
        for (EditorTypeMenusPacket.Menu m : menus) {
            if (m.isPackageMenu()) { hasPackageMenu = true; break; }
        }
        if (!hasPackageMenu) PACKAGE_BASIS = null;
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
        // Accumulator: each companion sharing a per-plot anchor shifts past
        // its predecessors. Reset when the per-plot anchor changes (different
        // BlockPos in worldPos) so unrelated companions don't stack.
        BlockPos lastCompanionAnchor = null;
        double priorCompanionWidth = 0;
        for (int i = 0; i < snapshot.size(); i++) {
            EditorTypeMenusPacket.Menu menu = snapshot.get(i);
            BlockPos pos = menu.worldPos();
            Vec3 anchor = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            Hovered local = (hovered.menuIdx == i) ? hovered : Hovered.NONE;
            double shiftIn;
            if (menu.isCompanion()) {
                if (lastCompanionAnchor == null || !lastCompanionAnchor.equals(pos)) {
                    priorCompanionWidth = 0;
                    lastCompanionAnchor = pos;
                }
                shiftIn = priorCompanionWidth;
                priorCompanionWidth += halfWidth(menu, font) * 2 + COMPANION_GAP;
            } else {
                shiftIn = 0;
            }
            drawMenu(ps, buffer, font, cam, anchor, menu, local, shiftIn);
        }

        buffer.endBatch(PANEL_QUAD);
        buffer.endBatch();
    }

    /**
     * Half-width of the menu panel in world units. Branches on
     * {@link EditorTypeMenusPacket.Menu#isNavMenu()} — companion menus keep
     * the legacy single-column sizing; nav menus size the panel to fit the
     * full category bar plus the expanded column plus every collapsed tab.
     */
    public static double halfWidth(EditorTypeMenusPacket.Menu menu, Font font) {
        if (menu.isPackageMenu()) return packageHalfWidth();
        if (menu.isNavMenu()) return navHalfWidth(menu, font);
        return companionHalfWidth(menu, font);
    }

    /**
     * Package menu width. Wider than the X-menu's {@code panelWidth = 3.6}
     * because worldspace text renders at {@link #TEXT_SCALE} (0.025) — the
     * Enable cell's "Disable" label and the top row's "Open Packages" label
     * both need physical world-space width past what 3.6 affords. 6.0 blocks
     * leaves comfortable padding for every cell at the fractions below.
     */
    private static double packageHalfWidth() {
        return 6.0 / 2.0;
    }

    /**
     * Single-column variant of {@link #halfWidth} used by companion menus —
     * scales to fit the longest variant name plus its weight cell, clamped
     * to {@link #MIN_HALF_W} so short lists still read with comfortable
     * padding.
     */
    private static double companionHalfWidth(EditorTypeMenusPacket.Menu menu, Font font) {
        double headerW = font.width(menu.typeName()) * TEXT_SCALE + 2 * PAD_X;
        double newW = font.width(NEW_LABEL) * TEXT_SCALE + 2 * PAD_X;
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
        double w = Math.max(MIN_HALF_W * 2.0, Math.max(Math.max(headerW, newW), scaledForName));
        return w / 2.0;
    }

    /**
     * Width of the expanded column on a nav menu — sized to fit the longest
     * variant name (plus weight cell allowance), clamped to
     * {@link #MIN_HALF_W} so short lists still read with comfortable
     * padding. Matches the legacy single-column sizing for the expanded
     * column so a nav menu reads like the old per-row menu inside its own
     * column.
     */
    private static double expandedColumnWidth(EditorTypeMenusPacket.Menu menu, Font font) {
        double headerW = font.width(menu.typeName()) * TEXT_SCALE + 2 * PAD_X;
        double newW = font.width(NEW_LABEL) * TEXT_SCALE + 2 * PAD_X;
        double maxNameW = 0;
        boolean anyWeight = false;
        for (EditorTypeMenusPacket.Variant v : menu.variants()) {
            double w = font.width(v.name()) * TEXT_SCALE + 2 * PAD_X;
            if (w > maxNameW) maxNameW = w;
            if (v.weight() != EditorPlotLabelsPacket.NO_WEIGHT) anyWeight = true;
        }
        double nameSpaceFraction = anyWeight ? (1.0 - WEIGHT_CELL_FRACTION) : 1.0;
        double scaledForName = maxNameW / nameSpaceFraction;
        return Math.max(MIN_HALF_W * 2.0, Math.max(Math.max(headerW, newW), scaledForName));
    }

    /**
     * Width of a single collapsed tab column — fits its type name plus
     * padding, clamped to {@link #COLLAPSED_TAB_MIN_W}.
     */
    private static double collapsedTabWidth(String typeName, Font font) {
        double w = font.width(typeName) * TEXT_SCALE + 2 * PAD_X;
        return Math.max(w, COLLAPSED_TAB_MIN_W);
    }

    /**
     * Total minimum width needed for the category bar — every button
     * sized to fit its display name plus padding. Buttons are drawn at
     * equal width so the bar's actual rendered width is the larger of
     * this minimum and the rest-of-panel width.
     */
    private static double categoryBarMinWidth(EditorTypeMenusPacket.Menu menu, Font font) {
        double total = 0;
        for (EditorTypeMenusPacket.CategoryButton b : menu.categoryBar()) {
            total += font.width(b.displayName()) * TEXT_SCALE + 2 * PAD_X;
        }
        return total;
    }

    /** Maximum growth factor for accommodating wide sub-variant rows — beyond this the sub-variant row wraps to a new line. */
    private static final double NAV_WIDTH_GROWTH_CAP = 2.25;
    /** Hard cap on how many lines a single variant's sub-variants can wrap to. Overflow beyond this is truncated. */
    private static final int SUB_VARIANT_MAX_LINES = 2;

    /**
     * Half-width of a nav menu. Two-step sizing:
     *
     * <ol>
     *   <li><b>Base</b> = tabs + {@link #SUB_VARIANT_AREA_MIN_W} (or the
     *       category bar minimum, whichever is larger). This is the panel
     *       size when no variant has sub-variants beyond the reserved
     *       minimum.</li>
     *   <li><b>Needed</b> = tabs + the widest single sub-variant row (no
     *       wrap). Lets the panel grow to fit cell rows that would
     *       otherwise wrap.</li>
     * </ol>
     *
     * <p>Final width = {@code min(needed, base * NAV_WIDTH_GROWTH_CAP)}.
     * If a sub-variant row is wider than the cap allows, the renderer
     * wraps the overflow onto extra lines below the variant row (see
     * {@link #subVariantLines}).</p>
     */
    private static double navHalfWidth(EditorTypeMenusPacket.Menu menu, Font font) {
        double tabsTotal = tabStripTotalWidth(menu, font);
        double catBarMin = categoryBarMinWidth(menu, font);
        double base = Math.max(catBarMin, Math.max(tabsTotal + SUB_VARIANT_AREA_MIN_W, MIN_HALF_W * 2.0));
        double needed = Math.max(catBarMin, Math.max(tabsTotal + subVariantAreaWidth(menu, font), MIN_HALF_W * 2.0));
        double maxAllowed = base * NAV_WIDTH_GROWTH_CAP;
        double actual = Math.min(needed, maxAllowed);
        return actual / 2.0;
    }

    /**
     * Sum of every tab column's width — expanded col plus each collapsed
     * tab's width. Order-independent; used to size the panel and lay out
     * tab edges.
     */
    private static double tabStripTotalWidth(EditorTypeMenusPacket.Menu menu, Font font) {
        double total = 0;
        int expandedIdx = expandedTabIdx(menu);
        for (int i = 0; i < menu.typeStrip().size(); i++) {
            if (i == expandedIdx) {
                total += expandedColumnWidth(menu, font);
            } else {
                total += collapsedTabWidth(menu.typeStrip().get(i).typeName(), font);
            }
        }
        return total;
    }

    /**
     * Width of the right-hand sub-variant area on a nav menu. Sized to fit
     * the widest sub-variant row across every variant in the menu, clamped
     * to {@link #SUB_VARIANT_AREA_MIN_W} so the panel reads as having "room
     * for" sub-variants even when none exist.
     *
     * <p>Sub-variants come from each {@code Variant#subVariants()} — the
     * server populates this per-variant (currently CONTENTS only), so
     * CARRIAGES menus just get the minimum reserved width.</p>
     */
    private static double subVariantAreaWidth(EditorTypeMenusPacket.Menu menu, Font font) {
        double maxW = SUB_VARIANT_AREA_MIN_W;
        for (EditorTypeMenusPacket.Variant v : menu.variants()) {
            if (v.subVariants().isEmpty()) continue;
            double rowW = SUB_VARIANT_GAP;
            for (EditorTypeMenusPacket.Variant sv : v.subVariants()) {
                rowW += subVariantCellWidth(sv.name(), font);
            }
            if (rowW > maxW) maxW = rowW;
        }
        return maxW;
    }

    /**
     * Width of one sub-variant cell — fits the variant name plus padding,
     * clamped to {@link #SUB_VARIANT_CELL_MIN_W}.
     */
    private static double subVariantCellWidth(String name, Font font) {
        double w = font.width(name) * TEXT_SCALE + 2 * PAD_X;
        return Math.max(w, SUB_VARIANT_CELL_MIN_W);
    }

    /**
     * Greedy line-break of a variant's sub-variants into rows that fit
     * inside {@code available} panel-local units. Cells overflow onto a
     * new line below the variant row when the panel can't grow wider.
     * Always returns at least one line for a non-empty input.
     *
     * <p>Hard-capped at {@link #SUB_VARIANT_MAX_LINES} — once the cap is
     * reached, any further children that won't fit on the current line
     * are silently dropped. The panel is already at its growth cap by
     * the time wrap happens, so spilling onto a third line would push
     * the menu vertically beyond the design budget.</p>
     */
    private static List<List<EditorTypeMenusPacket.Variant>> subVariantLines(
        List<EditorTypeMenusPacket.Variant> children, double available, Font font
    ) {
        List<List<EditorTypeMenusPacket.Variant>> lines = new java.util.ArrayList<>();
        if (children.isEmpty()) return lines;
        List<EditorTypeMenusPacket.Variant> current = new java.util.ArrayList<>();
        double currentW = 0;
        for (EditorTypeMenusPacket.Variant c : children) {
            double cellW = subVariantCellWidth(c.name(), font);
            if (currentW + cellW > available && !current.isEmpty()) {
                // Would overflow current line — if we're already at the
                // last allowed line, drop the rest of the children.
                if (lines.size() + 1 >= SUB_VARIANT_MAX_LINES) {
                    lines.add(current);
                    return lines;
                }
                lines.add(current);
                current = new java.util.ArrayList<>();
                currentW = 0;
            }
            current.add(c);
            currentW += cellW;
        }
        if (!current.isEmpty()) lines.add(current);
        return lines;
    }

    /**
     * Panel-local horizontal space available for sub-variant cells per
     * line on a nav menu — the portion to the right of the expanded
     * column (minus the gap before the first cell).
     */
    private static double availableSubVariantWidth(EditorTypeMenusPacket.Menu menu, Font font) {
        double panelW = navHalfWidth(menu, font) * 2.0;
        return panelW - expandedColumnWidth(menu, font) - SUB_VARIANT_GAP;
    }

    /**
     * Number of {@link #ROW_H}-tall rows a variant occupies in the nav
     * menu — 1 by default, more when the variant's sub-variants wrap to
     * multiple lines.
     */
    private static int variantRowSpan(
        EditorTypeMenusPacket.Variant variant, double availableSubW, Font font
    ) {
        if (variant.subVariants().isEmpty()) return 1;
        int lines = subVariantLines(variant.subVariants(), availableSubW, font).size();
        return Math.max(1, lines);
    }

    /**
     * Children of the active variant in this menu's expanded column —
     * pulled from the sub-variants companion in the snapshot cache.
     * Returns an empty list when no companion is present, when the
     * companion's parent doesn't match {@link #activeModelId()}, or when
     * the parent has no children beyond itself.
     *
     * <p>The companion's first variant is always the parent's "default"
     * row (modelId = parent id) — skipped so the inline list only carries
     * the proper children. See
     * {@link games.brennan.dungeontrain.editor.VariantOverlayRenderer#SUB_VARIANTS_TYPE_NAME}.</p>
     */

    /**
     * Map a display position (0 = expanded leftmost) to the canonical
     * index inside {@link EditorTypeMenusPacket.Menu#typeStrip()}. Displays
     * the expanded tab first; the remaining tabs follow in their original
     * order with the expanded one skipped.
     */
    private static int displayPosToCanonical(int displayPos, int expandedIdx, int n) {
        if (displayPos <= 0) return expandedIdx;
        int canonical = displayPos - 1;
        if (canonical >= expandedIdx) canonical++;
        return canonical;
    }

    /**
     * Index of the tab whose {@code typeName} matches the menu's outer
     * {@code typeName} — the column rendered expanded. Returns 0 when the
     * menu has a non-empty strip but no match (shouldn't happen for
     * server-built menus); {@code -1} for empty strips.
     */
    static int expandedTabIdx(EditorTypeMenusPacket.Menu menu) {
        List<EditorTypeMenusPacket.TypeTab> strip = menu.typeStrip();
        if (strip.isEmpty()) return -1;
        for (int i = 0; i < strip.size(); i++) {
            if (strip.get(i).typeName().equals(menu.typeName())) return i;
        }
        return 0;
    }

    public static int rowCount(EditorTypeMenusPacket.Menu menu, Font font) {
        // Package: header + Reload/Open row + N package rows.
        if (menu.isPackageMenu()) {
            return 2 + games.brennan.dungeontrain.client.PackageListClient.entries().size();
        }
        // Companion: header + N variants + "+ New" footer (skip footer for empty menus).
        // Nav: category bar + tab strip + total variant rows (1 per variant
        // plus extra rows for wrapped sub-variants) + "+ New" footer.
        if (menu.isNavMenu()) {
            int total = 2;
            double availableSubW = availableSubVariantWidth(menu, font);
            for (EditorTypeMenusPacket.Variant v : menu.variants()) {
                total += variantRowSpan(v, availableSubW, font);
            }
            if (!menu.variants().isEmpty()) total += 1;
            return total;
        }
        return 1 + menu.variants().size() + (menu.variants().isEmpty() ? 0 : 1);
    }

    public static double halfHeight(EditorTypeMenusPacket.Menu menu, Font font) {
        return rowCount(menu, font) * ROW_H / 2.0;
    }

    /**
     * Panel-local +X shift applied to a companion menu so its left edge
     * sits just past the per-plot panel's right edge (with {@link #COMPANION_GAP}
     * of visible space). Returns 0 for non-companion menus.
     *
     * <p>When multiple companions are present (e.g. sub-variants + type
     * companion both anchored at the per-plot panel), each subsequent
     * companion shifts past its predecessor by that predecessor's full
     * width + one {@link #COMPANION_GAP}. The list iteration in
     * {@link #onRenderLevelStage} preserves insertion order so the server's
     * append order controls visual sequencing.</p>
     *
     * <p>The per-plot panel's half-width is looked up from the matching
     * inPlot label so the gap stays tight regardless of which template the
     * player is in. Falls back to {@link EditorPlotLabelsRenderer#MIN_HALF_W *
     * 2} if no inPlot entry is cached (shouldn't happen in normal flow but
     * keeps the renderer crash-free).</p>
     */
    public static double companionShiftX(EditorTypeMenusPacket.Menu menu, Font font, double priorCompanionWidth) {
        if (!menu.isCompanion()) return 0;
        double perPlotHalfW = EditorPlotLabelsRenderer.MIN_HALF_W;
        for (EditorPlotLabelsPacket.Entry e : EditorPlotLabelsRenderer.entries()) {
            if (e.inPlot()) {
                perPlotHalfW = EditorPlotLabelsRenderer.halfWidth(e, font);
                break;
            }
        }
        return perPlotHalfW + COMPANION_GAP + priorCompanionWidth + halfWidth(menu, font);
    }

    /**
     * Convenience overload for single-companion call sites that don't track
     * a running sum.
     */
    public static double companionShiftX(EditorTypeMenusPacket.Menu menu, Font font) {
        return companionShiftX(menu, font, 0);
    }

    /**
     * Map a panel-local hit at {@code (hitX, hitY)} to a {@link Hovered}
     * pair. Branches on {@link EditorTypeMenusPacket.Menu#isNavMenu()} —
     * companion menus retain their pre-nav hit logic; nav menus add the
     * category bar (row 0) and type-tab strip (row 1) hit regions and
     * restrict variant hits to the expanded column's horizontal span.
     */
    public static Hovered hitFor(int menuIdx, EditorTypeMenusPacket.Menu menu, Font font,
                                 double hitX, double hitY) {
        if (menu.isPackageMenu()) {
            return hitForPackageMenu(menuIdx, menu, font, hitX, hitY);
        }
        if (menu.isNavMenu()) {
            return hitForNav(menuIdx, menu, font, hitX, hitY);
        }
        return hitForCompanion(menuIdx, menu, font, hitX, hitY);
    }

    private static Hovered hitForCompanion(int menuIdx, EditorTypeMenusPacket.Menu menu, Font font,
                                            double hitX, double hitY) {
        double halfW = halfWidth(menu, font);
        double halfH = halfHeight(menu, font);
        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) return Hovered.NONE;

        int rows = rowCount(menu, font);
        int rowFromTop = (int) Math.floor((halfH - hitY) / ROW_H);
        if (rowFromTop < 0 || rowFromTop >= rows) return Hovered.NONE;

        // Row 0 is the header — clickable as a "teleport to first variant"
        // shortcut, with variantIdx=-1 (the dispatch resolves the actual
        // first variant from menu.variants().get(0)).
        if (rowFromTop == 0) return new Hovered(menuIdx, -1, CellKind.HEADER);

        int variantIdx = rowFromTop - 1;
        // Last row beyond the variant list is the "+ New" footer (only present
        // when the menu has variants — see rowCount).
        if (variantIdx == menu.variants().size()) {
            return new Hovered(menuIdx, -1, CellKind.NEW);
        }
        if (variantIdx > menu.variants().size()) return Hovered.NONE;
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

    private static Hovered hitForNav(int menuIdx, EditorTypeMenusPacket.Menu menu, Font font,
                                      double hitX, double hitY) {
        double halfW = halfWidth(menu, font);
        double halfH = halfHeight(menu, font);
        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) return Hovered.NONE;

        int rows = rowCount(menu, font);
        int rowFromTop = (int) Math.floor((halfH - hitY) / ROW_H);
        if (rowFromTop < 0 || rowFromTop >= rows) return Hovered.NONE;

        // Row 0 — category bar. Equal-width buttons across the full panel.
        if (rowFromTop == 0) {
            int n = menu.categoryBar().size();
            if (n == 0) return Hovered.NONE;
            double buttonW = (halfW * 2.0) / n;
            int slot = (int) Math.floor((hitX + halfW) / buttonW);
            if (slot < 0) slot = 0;
            if (slot >= n) slot = n - 1;
            return new Hovered(menuIdx, -1, CellKind.CATEGORY, slot);
        }

        // Row 1 — type tab strip. Expanded leftmost; collapsed tabs follow.
        if (rowFromTop == 1) {
            int n = menu.typeStrip().size();
            if (n == 0) return Hovered.NONE;
            int expandedIdx = expandedTabIdx(menu);
            double xCursor = -halfW;
            for (int displayPos = 0; displayPos < n; displayPos++) {
                int canonical = displayPosToCanonical(displayPos, expandedIdx, n);
                EditorTypeMenusPacket.TypeTab tab = menu.typeStrip().get(canonical);
                double w = (canonical == expandedIdx)
                    ? expandedColumnWidth(menu, font)
                    : collapsedTabWidth(tab.typeName(), font);
                if (hitX >= xCursor && hitX < xCursor + w) {
                    return new Hovered(menuIdx, -1, CellKind.TYPE_TAB, canonical);
                }
                xCursor += w;
            }
            return Hovered.NONE;
        }

        // Rows 2..end — variant rows + wrapped sub-variant lines + +New.
        // Each variant occupies {@link #variantRowSpan} consecutive rows;
        // walk through variants accumulating spans until rowFromTop lands
        // inside one (or past the last one = +New footer).
        double colLeft = -halfW;
        double colRight = -halfW + expandedColumnWidth(menu, font);
        double availableSubW = availableSubVariantWidth(menu, font);

        int rowAfterChrome = rowFromTop - 2;
        int variantIdx = -1;
        int spanOffset = 0;
        int cursor = 0;
        for (int vi = 0; vi < menu.variants().size(); vi++) {
            int span = variantRowSpan(menu.variants().get(vi), availableSubW, font);
            if (rowAfterChrome < cursor + span) {
                variantIdx = vi;
                spanOffset = rowAfterChrome - cursor;
                break;
            }
            cursor += span;
        }

        if (variantIdx < 0) {
            // Past the last variant — must be +New footer.
            if (rowAfterChrome == cursor && !menu.variants().isEmpty()) {
                if (hitX < colLeft || hitX > colRight) return Hovered.NONE;
                return new Hovered(menuIdx, -1, CellKind.NEW);
            }
            return Hovered.NONE;
        }

        EditorTypeMenusPacket.Variant variant = menu.variants().get(variantIdx);

        // Sub-variant cells — locate the line within this variant's span,
        // then the cell inside that line. Sub-variants always live to the
        // right of the expanded column, including on wrapped lines.
        if (!variant.subVariants().isEmpty() && hitX >= colRight + SUB_VARIANT_GAP) {
            List<List<EditorTypeMenusPacket.Variant>> lines =
                subVariantLines(variant.subVariants(), availableSubW, font);
            if (spanOffset < lines.size()) {
                List<EditorTypeMenusPacket.Variant> line = lines.get(spanOffset);
                int slotBase = 0;
                for (int li = 0; li < spanOffset; li++) slotBase += lines.get(li).size();
                double subCursor = colRight + SUB_VARIANT_GAP;
                for (int ci = 0; ci < line.size(); ci++) {
                    double w = subVariantCellWidth(line.get(ci).name(), font);
                    if (hitX >= subCursor && hitX < subCursor + w) {
                        return new Hovered(menuIdx, variantIdx, CellKind.SUB_VARIANT, slotBase + ci);
                    }
                    subCursor += w;
                }
            }
        }

        // NAME / WEIGHT cells only live on the variant's first row
        // (spanOffset == 0). Below that is sub-variant overflow territory.
        if (spanOffset != 0) return Hovered.NONE;
        if (hitX < colLeft || hitX > colRight) return Hovered.NONE;
        boolean hasWeight = variant.weight() != EditorPlotLabelsPacket.NO_WEIGHT;
        double colW = colRight - colLeft;
        if (hasWeight) {
            double weightCellLeft = colRight - colW * WEIGHT_CELL_FRACTION;
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
        EditorTypeMenusPacket.Menu menu, Hovered hovered,
        double priorCompanionWidth
    ) {
        Vec3[] b = basisFor(menu, anchor, cam);
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
        // column of a single extended UI. When multiple companions share an
        // anchor, each shifts past its predecessors (priorCompanionWidth
        // accumulated by the caller).
        double shiftX = companionShiftX(menu, font, priorCompanionWidth);
        if (shiftX != 0) {
            ps.translate(shiftX, 0, 0);
        }

        if (menu.isPackageMenu()) {
            drawPackageMenu(ps, buffer, font, menu, hovered);
        } else if (menu.isNavMenu()) {
            drawNavMenu(ps, buffer, font, menu, hovered);
        } else {
            drawCompanionMenu(ps, buffer, font, menu, hovered);
        }

        ps.popPose();
    }

    private static void drawCompanionMenu(
        PoseStack ps, MultiBufferSource buffer, Font font,
        EditorTypeMenusPacket.Menu menu, Hovered hovered
    ) {
        double halfW = halfWidth(menu, font);
        double halfH = halfHeight(menu, font);

        // Whole-panel backdrop.
        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, BACKDROP_COLOR);

        // Header row — yellow tint band + type name centred.
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

        String activeModelId = activeModelId();
        String activeModelName = activeModelName();

        // Variant rows.
        for (int vi = 0; vi < menu.variants().size(); vi++) {
            EditorTypeMenusPacket.Variant variant = menu.variants().get(vi);
            double rowTop = topY - (vi + 1) * ROW_H;
            double rowBottom = rowTop - ROW_H;
            double rowCY = (rowTop + rowBottom) / 2.0;

            drawQuad(ps, buffer, -halfW, rowTop - 0.005, halfW, rowTop + 0.005, ROW_SEP_COLOR);

            boolean hasWeight = variant.weight() != EditorPlotLabelsPacket.NO_WEIGHT;
            double weightCellLeft = halfW - (halfW * 2.0) * WEIGHT_CELL_FRACTION;
            double nameRight = hasWeight ? weightCellLeft : halfW;

            drawVariantRow(ps, buffer, font, variant, -halfW, rowBottom, halfW, rowTop,
                rowCY, weightCellLeft, nameRight, hasWeight,
                hovered.variantIdx == vi && hovered.cell == CellKind.NAME,
                hovered.variantIdx == vi && hovered.cell == CellKind.WEIGHT,
                activeModelId, activeModelName);
        }

        // "+ New" footer row.
        if (!menu.variants().isEmpty()) {
            double newRowTop = topY - (menu.variants().size() + 1) * ROW_H;
            double newRowBottom = newRowTop - ROW_H;
            double newRowCY = (newRowTop + newRowBottom) / 2.0;
            drawQuad(ps, buffer, -halfW, newRowTop - 0.005, halfW, newRowTop + 0.005, ROW_SEP_COLOR);
            drawQuad(ps, buffer, -halfW, newRowBottom, halfW, newRowTop, NEW_ROW_BG);
            if (hovered.cell == CellKind.NEW) {
                drawQuad(ps, buffer, -halfW + 0.005, newRowBottom + 0.005,
                    halfW - 0.005, newRowTop - 0.005, HOVER_COLOR);
            }
            drawCenteredText(ps, buffer, font, NEW_LABEL, 0, newRowCY, NEW_COLOR);
        }
    }

    private static void drawNavMenu(
        PoseStack ps, MultiBufferSource buffer, Font font,
        EditorTypeMenusPacket.Menu menu, Hovered hovered
    ) {
        double halfW = halfWidth(menu, font);
        double halfH = halfHeight(menu, font);
        double topY = halfH;
        double panelW = halfW * 2.0;

        // Whole-panel backdrop.
        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, BACKDROP_COLOR);

        // --- Row 0: category bar (equal-width buttons across the panel). ---
        double catTop = topY;
        double catBottom = topY - ROW_H;
        double catCY = (catTop + catBottom) / 2.0;
        int catN = menu.categoryBar().size();
        if (catN > 0) {
            double buttonW = panelW / catN;
            for (int i = 0; i < catN; i++) {
                EditorTypeMenusPacket.CategoryButton btn = menu.categoryBar().get(i);
                double btnLeft = -halfW + i * buttonW;
                double btnRight = btnLeft + buttonW;
                boolean isActive = btn.id().equals(menu.activeCategoryId());
                boolean isHover = hovered.cell == CellKind.CATEGORY && hovered.slotIdx == i;
                if (isActive) {
                    drawQuad(ps, buffer, btnLeft, catBottom, btnRight, catTop, ACTIVE_CATEGORY_BG);
                }
                if (isHover) {
                    drawQuad(ps, buffer, btnLeft + 0.005, catBottom + 0.005,
                        btnRight - 0.005, catTop - 0.005, HOVER_COLOR);
                }
                int color = isActive ? ACTIVE_CATEGORY_COLOR : CATEGORY_COLOR;
                drawCenteredText(ps, buffer, font, btn.displayName(),
                    (btnLeft + btnRight) / 2.0, catCY, color);
                if (i + 1 < catN) {
                    drawQuad(ps, buffer, btnRight - COLUMN_DIVIDER_W / 2.0, catBottom,
                        btnRight + COLUMN_DIVIDER_W / 2.0, catTop, COLUMN_SEP_COLOR);
                }
            }
        }
        // Row separator below the category bar.
        drawQuad(ps, buffer, -halfW, catBottom - 0.005, halfW, catBottom + 0.005, ROW_SEP_COLOR);

        // --- Row 1: type tab strip — expanded leftmost, others follow. ---
        double tabTop = catBottom;
        double tabBottom = tabTop - ROW_H;
        double tabCY = (tabTop + tabBottom) / 2.0;
        int tabN = menu.typeStrip().size();
        int expandedIdx = expandedTabIdx(menu);
        double tabCursor = -halfW;
        // Expanded column bounds — captured during the tab walk so the
        // variant body uses the exact pixels under the expanded tab.
        double expColLeft = -halfW;
        double expColRight = -halfW;
        for (int displayPos = 0; displayPos < tabN; displayPos++) {
            int canonical = displayPosToCanonical(displayPos, expandedIdx, tabN);
            EditorTypeMenusPacket.TypeTab tab = menu.typeStrip().get(canonical);
            boolean isExpanded = (canonical == expandedIdx);
            double tabW = isExpanded
                ? expandedColumnWidth(menu, font)
                : collapsedTabWidth(tab.typeName(), font);
            double tabLeft = tabCursor;
            double tabRight = tabCursor + tabW;
            boolean isHover = hovered.cell == CellKind.TYPE_TAB && hovered.slotIdx == canonical;

            if (isExpanded) {
                drawQuad(ps, buffer, tabLeft, tabBottom, tabRight, tabTop, HEADER_BG);
            } else {
                drawQuad(ps, buffer, tabLeft, tabBottom, tabRight, tabTop, COLLAPSED_TAB_BG);
            }
            if (isHover) {
                drawQuad(ps, buffer, tabLeft + 0.005, tabBottom + 0.005,
                    tabRight - 0.005, tabTop - 0.005, HOVER_COLOR);
            }
            int color = isExpanded ? HEADER_COLOR : COLLAPSED_TAB_COLOR;
            drawCenteredText(ps, buffer, font, tab.typeName(),
                (tabLeft + tabRight) / 2.0, tabCY, color);

            if (isExpanded) {
                expColLeft = tabLeft;
                expColRight = tabRight;
            }

            if (displayPos + 1 < tabN) {
                drawQuad(ps, buffer, tabRight - COLUMN_DIVIDER_W / 2.0, tabBottom,
                    tabRight + COLUMN_DIVIDER_W / 2.0, tabTop, COLUMN_SEP_COLOR);
            }
            tabCursor = tabRight;
        }
        // Row separator below the tab strip.
        drawQuad(ps, buffer, -halfW, tabBottom - 0.005, halfW, tabBottom + 0.005, ROW_SEP_COLOR);

        // Faint tint behind the entire right-of-expanded area so the
        // reserved sub-variant region reads as part of the menu instead of
        // an unused gap. Drawn under the variant rows so it doesn't
        // obscure the row separators / sub-variant cells. Height includes
        // every wrapped sub-variant line — derived from the total row
        // count below the tab strip.
        int variantBodyRows = rowCount(menu, font) - 2;
        double rightAreaLeft = expColRight;
        double rightAreaRight = halfW;
        double varBodyTop = tabBottom;
        double varBodyBottom = tabBottom - variantBodyRows * ROW_H;
        if (rightAreaRight > rightAreaLeft + 0.001) {
            drawQuad(ps, buffer, rightAreaLeft, varBodyBottom, rightAreaRight, varBodyTop, SUB_VARIANT_AREA_BG);
        }

        // --- Variant rows: drawn only within the expanded column. ---
        String activeModelId = activeModelId();
        String activeModelName = activeModelName();
        double expColW = expColRight - expColLeft;
        double weightCellLeft = expColRight - expColW * WEIGHT_CELL_FRACTION;

        double availableSubW = availableSubVariantWidth(menu, font);
        // Cumulative top of the next variant — shifts down by the variant's
        // row span (1 + wrapped sub-variant lines).
        double rowCursor = tabBottom;
        for (int vi = 0; vi < menu.variants().size(); vi++) {
            EditorTypeMenusPacket.Variant variant = menu.variants().get(vi);
            int span = variantRowSpan(variant, availableSubW, font);

            // Variant row sits on the first line of its span.
            double rowTop = rowCursor;
            double rowBottom = rowTop - ROW_H;
            double rowCY = (rowTop + rowBottom) / 2.0;

            drawQuad(ps, buffer, expColLeft, rowTop - 0.005, expColRight, rowTop + 0.005, ROW_SEP_COLOR);

            boolean hasWeight = variant.weight() != EditorPlotLabelsPacket.NO_WEIGHT;
            double nameRight = hasWeight ? weightCellLeft : expColRight;

            drawVariantRow(ps, buffer, font, variant, expColLeft, rowBottom, expColRight, rowTop,
                rowCY, weightCellLeft, nameRight, hasWeight,
                hovered.variantIdx == vi && hovered.cell == CellKind.NAME,
                hovered.variantIdx == vi && hovered.cell == CellKind.WEIGHT,
                activeModelId, activeModelName);

            // Sub-variants — horizontal cells across one or more lines.
            // Line 0 sits next to the variant row; additional lines stack
            // below within this variant's row span.
            List<EditorTypeMenusPacket.Variant> children = variant.subVariants();
            if (!children.isEmpty()) {
                List<List<EditorTypeMenusPacket.Variant>> lines =
                    subVariantLines(children, availableSubW, font);
                int slotBase = 0;
                for (int li = 0; li < lines.size(); li++) {
                    List<EditorTypeMenusPacket.Variant> line = lines.get(li);
                    double lineTop = rowTop - li * ROW_H;
                    double lineBottom = lineTop - ROW_H;
                    double lineCY = (lineTop + lineBottom) / 2.0;
                    double subCursor = expColRight + SUB_VARIANT_GAP;
                    for (int ci = 0; ci < line.size(); ci++) {
                        EditorTypeMenusPacket.Variant child = line.get(ci);
                        double cellW = subVariantCellWidth(child.name(), font);
                        double cellLeft = subCursor;
                        double cellRight = subCursor + cellW;
                        int flatIdx = slotBase + ci;
                        boolean isChildActive = !activeModelId.isEmpty()
                            && activeModelId.equals(child.modelId())
                            && activeModelName.equals(child.modelName());
                        boolean isChildHover = hovered.cell == CellKind.SUB_VARIANT
                            && hovered.variantIdx == vi && hovered.slotIdx == flatIdx;
                        drawQuad(ps, buffer, cellLeft, lineBottom, cellRight, lineTop, SUB_VARIANT_CELL_BG);
                        if (child.isImported()) {
                            drawQuad(ps, buffer, cellLeft + 0.005, lineBottom + 0.005,
                                cellRight - 0.005, lineTop - 0.005, IMPORTED_ROW_BG);
                        } else if (child.isUser()) {
                            drawQuad(ps, buffer, cellLeft + 0.005, lineBottom + 0.005,
                                cellRight - 0.005, lineTop - 0.005, USER_ROW_BG);
                        }
                        if (isChildActive) {
                            drawQuad(ps, buffer, cellLeft + 0.005, lineBottom + 0.005,
                                cellRight - 0.005, lineTop - 0.005, ACTIVE_ROW_COLOR);
                        }
                        if (isChildHover) {
                            drawQuad(ps, buffer, cellLeft + 0.005, lineBottom + 0.005,
                                cellRight - 0.005, lineTop - 0.005, HOVER_COLOR);
                        }
                        drawCenteredText(ps, buffer, font, child.name(),
                            (cellLeft + cellRight) / 2.0, lineCY, NAME_COLOR);
                        if (ci + 1 < line.size()) {
                            drawQuad(ps, buffer, cellRight - COLUMN_DIVIDER_W / 2.0, lineBottom,
                                cellRight + COLUMN_DIVIDER_W / 2.0, lineTop, COLUMN_SEP_COLOR);
                        }
                        subCursor += cellW;
                    }
                    slotBase += line.size();
                }
            }

            rowCursor -= span * ROW_H;
        }

        // "+ New" footer row — drawn only within the expanded column.
        // Position is rowCursor (the Y after every variant + its wrapped
        // sub-variant lines have shifted down).
        if (!menu.variants().isEmpty()) {
            double newRowTop = rowCursor;
            double newRowBottom = newRowTop - ROW_H;
            double newRowCY = (newRowTop + newRowBottom) / 2.0;
            drawQuad(ps, buffer, expColLeft, newRowTop - 0.005, expColRight, newRowTop + 0.005, ROW_SEP_COLOR);
            drawQuad(ps, buffer, expColLeft, newRowBottom, expColRight, newRowTop, NEW_ROW_BG);
            if (hovered.cell == CellKind.NEW) {
                drawQuad(ps, buffer, expColLeft + 0.005, newRowBottom + 0.005,
                    expColRight - 0.005, newRowTop - 0.005, HOVER_COLOR);
            }
            drawCenteredText(ps, buffer, font, NEW_LABEL,
                (expColLeft + expColRight) / 2.0, newRowCY, NEW_COLOR);
        }
    }

    /**
     * Shared variant-row drawing for both companion and nav layouts.
     * {@code rowLeft}/{@code rowRight} are the row's horizontal bounds
     * (panel-local) — companion menus pass the full panel width, nav menus
     * pass the expanded column's bounds.
     */
    private static void drawVariantRow(
        PoseStack ps, MultiBufferSource buffer, Font font,
        EditorTypeMenusPacket.Variant variant,
        double rowLeft, double rowBottom, double rowRight, double rowTop,
        double rowCY, double weightCellLeft, double nameRight,
        boolean hasWeight, boolean nameHover, boolean weightHover,
        String activeModelId, String activeModelName
    ) {
        // Provenance tint — orange for imported variants (highest priority),
        // blue for user-authored, no tint for bundled.
        if (variant.isImported()) {
            drawQuad(ps, buffer, rowLeft + 0.005, rowBottom + 0.005,
                rowRight - 0.005, rowTop - 0.005, IMPORTED_ROW_BG);
        } else if (variant.isUser()) {
            drawQuad(ps, buffer, rowLeft + 0.005, rowBottom + 0.005,
                rowRight - 0.005, rowTop - 0.005, USER_ROW_BG);
        }

        // Active-row tint for the variant matching the player's current plot.
        boolean isActive = !activeModelId.isEmpty()
            && activeModelId.equals(variant.modelId())
            && activeModelName.equals(variant.modelName());
        if (isActive) {
            drawQuad(ps, buffer, rowLeft + 0.005, rowBottom + 0.005,
                rowRight - 0.005, rowTop - 0.005, ACTIVE_ROW_COLOR);
        }

        // Hover highlight.
        if (nameHover) {
            drawQuad(ps, buffer, rowLeft + 0.005, rowBottom + 0.005,
                nameRight - 0.005, rowTop - 0.005, HOVER_COLOR);
        }
        if (weightHover) {
            drawQuad(ps, buffer, weightCellLeft + 0.005, rowBottom + 0.005,
                rowRight - 0.005, rowTop - 0.005, HOVER_COLOR);
        }

        // Name (centred within its cell).
        double nameCX = (rowLeft + nameRight) / 2.0;
        drawCenteredText(ps, buffer, font, variant.name(), nameCX, rowCY, NAME_COLOR);

        if (hasWeight) {
            double weightCX = (weightCellLeft + rowRight) / 2.0;
            drawCenteredText(ps, buffer, font, "×" + variant.weight(),
                weightCX, rowCY, WEIGHT_COLOR);
        }
    }

    /**
     * The player's currently active template's modelId — pulled from the
     * per-plot label that's flagged {@code inPlot=true}. Empty string when
     * the player is outside every plot.
     */
    private static String activeModelId() {
        for (EditorPlotLabelsPacket.Entry e : EditorPlotLabelsRenderer.entries()) {
            if (e.inPlot()) return e.modelId();
        }
        return "";
    }

    private static String activeModelName() {
        for (EditorPlotLabelsPacket.Entry e : EditorPlotLabelsRenderer.entries()) {
            if (e.inPlot()) return e.modelName();
        }
        return "";
    }

    // ---------- package menu ----------

    /**
     * Cell-boundary fractions for a per-package row: {@code Name | Save | Open | Enable}.
     * Reallocated from the X-menu's {@code (0.55, 0.72, 0.86)} so the Enable cell
     * gets ~24% of panel width — enough room for "Disable" to render without
     * truncation at the worldspace text scale.
     */
    private static final double PKG_BOUND_1 = 0.50;
    private static final double PKG_BOUND_2 = 0.63;
    private static final double PKG_BOUND_3 = 0.76;
    /** Cell-boundary fraction for the top {@code Reload | Open Packages} split row. */
    private static final double PKG_TOP_SPLIT = 0.55;

    /**
     * Render the worldspace package panel — mirror of the X-menu's flat
     * {@link games.brennan.dungeontrain.client.menu.PackageListScreen}.
     *
     * <p>Rows: {@code Packages} header, {@code Reload | Open Packages} top
     * split, then one row per package with cells
     * {@code Name | Save | Open | Enable}. Data comes from the client-side
     * {@link games.brennan.dungeontrain.client.PackageListClient} cache —
     * the {@link EditorTypeMenusPacket} carries only the anchor + a marker
     * flag, so package state changes propagate through the existing
     * {@code PackageListSyncPacket} channel rather than re-sending the type
     * menus snapshot.</p>
     */
    private static void drawPackageMenu(
        PoseStack ps, MultiBufferSource buffer, Font font,
        EditorTypeMenusPacket.Menu menu, Hovered hovered
    ) {
        // Keep the client cache warm — same throttle the X-menu uses.
        games.brennan.dungeontrain.client.PackageListClient.requestRefreshThrottled();

        java.util.List<games.brennan.dungeontrain.net.PackageListSyncPacket.Entry> entries =
            games.brennan.dungeontrain.client.PackageListClient.entries();

        double halfW = halfWidth(menu, font);
        double halfH = halfHeight(menu, font);
        double topY = halfH;
        double panelW = halfW * 2.0;

        // Whole-panel backdrop.
        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, BACKDROP_COLOR);

        // Header row.
        double headerTop = topY;
        double headerBottom = headerTop - ROW_H;
        double headerCY = (headerTop + headerBottom) / 2.0;
        drawQuad(ps, buffer, -halfW, headerBottom, halfW, headerTop, HEADER_BG);
        drawCenteredText(ps, buffer, font, "Packages", 0, headerCY, HEADER_COLOR);

        // Top split row — Reload | Open Packages.
        double topRowTop = headerBottom;
        double topRowBottom = topRowTop - ROW_H;
        double topRowCY = (topRowTop + topRowBottom) / 2.0;
        double topSplitX = -halfW + panelW * PKG_TOP_SPLIT;
        drawQuad(ps, buffer, -halfW, topRowTop - 0.005, halfW, topRowTop + 0.005, ROW_SEP_COLOR);
        if (hovered.cell == CellKind.PKG_RELOAD) {
            drawQuad(ps, buffer, -halfW + 0.005, topRowBottom + 0.005,
                topSplitX - 0.005, topRowTop - 0.005, HOVER_COLOR);
        }
        if (hovered.cell == CellKind.PKG_OPEN_FOLDER) {
            drawQuad(ps, buffer, topSplitX + 0.005, topRowBottom + 0.005,
                halfW - 0.005, topRowTop - 0.005, HOVER_COLOR);
        }
        drawCenteredText(ps, buffer, font, "Reload",
            (-halfW + topSplitX) / 2.0, topRowCY, NAME_COLOR);
        drawCenteredText(ps, buffer, font, "Open Packages",
            (topSplitX + halfW) / 2.0, topRowCY, NAME_COLOR);
        drawQuad(ps, buffer, topSplitX - COLUMN_DIVIDER_W / 2.0, topRowBottom,
            topSplitX + COLUMN_DIVIDER_W / 2.0, topRowTop, COLUMN_SEP_COLOR);

        // Per-package rows.
        double cellBound1X = -halfW + panelW * PKG_BOUND_1;
        double cellBound2X = -halfW + panelW * PKG_BOUND_2;
        double cellBound3X = -halfW + panelW * PKG_BOUND_3;

        for (int i = 0; i < entries.size(); i++) {
            games.brennan.dungeontrain.net.PackageListSyncPacket.Entry entry = entries.get(i);
            double rowTop = topRowBottom - i * ROW_H;
            double rowBottom = rowTop - ROW_H;
            double rowCY = (rowTop + rowBottom) / 2.0;
            boolean isUnsaved = games.brennan.dungeontrain.editor.PackageInfo.UNSAVED_NAME.equals(entry.name());

            drawQuad(ps, buffer, -halfW, rowTop - 0.005, halfW, rowTop + 0.005, ROW_SEP_COLOR);

            // Active row tint.
            if (entry.isActive()) {
                drawQuad(ps, buffer, -halfW + 0.005, rowBottom + 0.005,
                    halfW - 0.005, rowTop - 0.005, ACTIVE_ROW_COLOR);
            }

            // Hover highlights per cell.
            boolean nameHover = hovered.cell == CellKind.PKG_NAME && hovered.variantIdx == i;
            boolean saveHover = hovered.cell == CellKind.PKG_SAVE && hovered.variantIdx == i;
            boolean openHover = hovered.cell == CellKind.PKG_OPEN && hovered.variantIdx == i;
            boolean enableHover = hovered.cell == CellKind.PKG_ENABLE && hovered.variantIdx == i;
            if (nameHover) {
                drawQuad(ps, buffer, -halfW + 0.005, rowBottom + 0.005,
                    cellBound1X - 0.005, rowTop - 0.005, HOVER_COLOR);
            }
            if (saveHover) {
                drawQuad(ps, buffer, cellBound1X + 0.005, rowBottom + 0.005,
                    cellBound2X - 0.005, rowTop - 0.005, HOVER_COLOR);
            }
            if (openHover) {
                drawQuad(ps, buffer, cellBound2X + 0.005, rowBottom + 0.005,
                    cellBound3X - 0.005, rowTop - 0.005, HOVER_COLOR);
            }
            if (enableHover) {
                drawQuad(ps, buffer, cellBound3X + 0.005, rowBottom + 0.005,
                    halfW - 0.005, rowTop - 0.005, HOVER_COLOR);
            }

            // Column dividers.
            drawQuad(ps, buffer, cellBound1X - COLUMN_DIVIDER_W / 2.0, rowBottom,
                cellBound1X + COLUMN_DIVIDER_W / 2.0, rowTop, COLUMN_SEP_COLOR);
            drawQuad(ps, buffer, cellBound2X - COLUMN_DIVIDER_W / 2.0, rowBottom,
                cellBound2X + COLUMN_DIVIDER_W / 2.0, rowTop, COLUMN_SEP_COLOR);
            drawQuad(ps, buffer, cellBound3X - COLUMN_DIVIDER_W / 2.0, rowBottom,
                cellBound3X + COLUMN_DIVIDER_W / 2.0, rowTop, COLUMN_SEP_COLOR);

            // Cell text — name carries the active marker "●", same as PackageListScreen.
            String nameLabel = (entry.isActive() ? "● " : "  ") + entry.name();
            drawCenteredText(ps, buffer, font, nameLabel,
                (-halfW + cellBound1X) / 2.0, rowCY, NAME_COLOR);
            drawCenteredText(ps, buffer, font, "Save",
                (cellBound1X + cellBound2X) / 2.0, rowCY, NAME_COLOR);
            drawCenteredText(ps, buffer, font, "Open",
                (cellBound2X + cellBound3X) / 2.0, rowCY, NAME_COLOR);
            String enableLabel;
            if (isUnsaved) {
                enableLabel = "—";
            } else {
                enableLabel = entry.enabled() ? "Disable" : "Enable";
            }
            drawCenteredText(ps, buffer, font, enableLabel,
                (cellBound3X + halfW) / 2.0, rowCY, NAME_COLOR);
        }
    }

    /**
     * Map a panel-local hit on a package menu to its cell. Mirrors the
     * row layout from {@link #drawPackageMenu} — header / top-split /
     * per-package rows with cell boundaries at {@link #PKG_BOUND_1} /
     * {@link #PKG_BOUND_2} / {@link #PKG_BOUND_3} / {@link #PKG_TOP_SPLIT}.
     *
     * <p>{@code variantIdx} carries the entry index for per-package cells
     * (PKG_NAME / PKG_SAVE / PKG_OPEN / PKG_ENABLE); {@code -1} for the
     * top-row cells. The Enable cell on the unsaved pseudo-package returns
     * {@link Hovered#NONE} so the inert "—" placeholder is non-clickable.</p>
     */
    private static Hovered hitForPackageMenu(int menuIdx, EditorTypeMenusPacket.Menu menu, Font font,
                                              double hitX, double hitY) {
        double halfW = halfWidth(menu, font);
        double halfH = halfHeight(menu, font);
        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) return Hovered.NONE;

        int rows = rowCount(menu, font);
        int rowFromTop = (int) Math.floor((halfH - hitY) / ROW_H);
        if (rowFromTop < 0 || rowFromTop >= rows) return Hovered.NONE;

        double panelW = halfW * 2.0;

        // Row 0 — header (inert).
        if (rowFromTop == 0) return Hovered.NONE;

        // Row 1 — Reload | Open Packages.
        if (rowFromTop == 1) {
            double splitX = -halfW + panelW * PKG_TOP_SPLIT;
            return hitX < splitX
                ? new Hovered(menuIdx, -1, CellKind.PKG_RELOAD)
                : new Hovered(menuIdx, -1, CellKind.PKG_OPEN_FOLDER);
        }

        // Rows 2..end — per-package quad cells.
        int pkgIdx = rowFromTop - 2;
        java.util.List<games.brennan.dungeontrain.net.PackageListSyncPacket.Entry> entries =
            games.brennan.dungeontrain.client.PackageListClient.entries();
        if (pkgIdx < 0 || pkgIdx >= entries.size()) return Hovered.NONE;
        games.brennan.dungeontrain.net.PackageListSyncPacket.Entry entry = entries.get(pkgIdx);
        boolean isUnsaved = games.brennan.dungeontrain.editor.PackageInfo.UNSAVED_NAME.equals(entry.name());

        double cellBound1X = -halfW + panelW * PKG_BOUND_1;
        double cellBound2X = -halfW + panelW * PKG_BOUND_2;
        double cellBound3X = -halfW + panelW * PKG_BOUND_3;
        if (hitX < cellBound1X) return new Hovered(menuIdx, pkgIdx, CellKind.PKG_NAME);
        if (hitX < cellBound2X) return new Hovered(menuIdx, pkgIdx, CellKind.PKG_SAVE);
        if (hitX < cellBound3X) return new Hovered(menuIdx, pkgIdx, CellKind.PKG_OPEN);
        // Enable cell on the unsaved pseudo-package is rendered as inert "—".
        if (isUnsaved) return Hovered.NONE;
        return new Hovered(menuIdx, pkgIdx, CellKind.PKG_ENABLE);
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
