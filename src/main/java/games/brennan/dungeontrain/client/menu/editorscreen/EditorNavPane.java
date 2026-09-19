package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.client.builder.BuilderTileArt;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.editor.BuilderModeCategory;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.PlotCategory;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Nav tab: the title-screen picker's four tiles, indoors.
 *
 * <p>Left, the four areas of the editor as the same image tiles {@code TrainBuilderScreen} shows,
 * in the same order. Right, in the slots the detail pane uses for a template, the picked area's
 * name, its picture where the rotating model would be, the sentence or two that says what the
 * area is for, and a red <b>Go here</b> along the bottom. Red because going there re-stamps every
 * plot and takes unsaved edits with it — the screen asks before it does.</p>
 *
 * <p>The same shape as {@link EditorSettingsPane}: lay out, render, hit-test. The tile grid is its
 * own arithmetic rather than {@code BuilderPickerLayout}'s because that one centres a whole body in the screen
 * with its own margins, and here the tiles fill a column.</p>
 */
final class EditorNavPane {

    /** What a click landed on. {@code mode} is set for TILE only. */
    record Hit(Kind kind, BuilderMode mode) {
        static final Hit NONE = new Hit(Kind.NONE, null);
    }

    enum Kind { NONE, TILE, GO_HERE }

    static final int TILE_GAP = 6;
    static final int LINE_H = 10;
    static final int TEXT_PAD = 3;
    /** The button: a red the pane's danger hover already uses, and a quieter red when idle. */
    static final int GO_IDLE = 0xA0CC3333;
    static final int GO_HOT = 0xC0FF5544;
    static final int GO_TEXT = 0xFFFFFFFF;
    static final int GO_TEXT_DISABLED = 0x80FFFFFF;
    static final int DESCRIPTION = 0xFFDDDDDD;

    private final Map<BuilderMode, InventoryEditorLayout.Rect> tileRects = new EnumMap<>(BuilderMode.class);
    private final Map<BuilderMode, Boolean> artAvailable = new EnumMap<>(BuilderMode.class);
    private InventoryEditorLayout.Rect goRect;
    private boolean goEnabled;

    /** The tiles' column: the same rectangle the Settings rows fill. */
    static InventoryEditorLayout.Rect rect(InventoryEditorLayout layout) {
        return EditorSettingsPane.rect(layout);
    }

    /**
     * Four 16:9 tiles, two per row, as large as the column allows and centred in it. Pure so the
     * fit can be tested at the sizes that matter without a client.
     */
    static List<InventoryEditorLayout.Rect> tiles(InventoryEditorLayout.Rect area) {
        int cols = 3;
        int rows = 2;
        int tileW = Math.max(1, (area.w() - TILE_GAP * (cols - 1)) / cols);
        int tileH = tileW * 9 / 16;
        int maxTileH = Math.max(1, (area.h() - TILE_GAP * (rows - 1)) / rows);
        if (tileH > maxTileH) {
            tileH = maxTileH;
            tileW = Math.max(1, tileH * 16 / 9);
        }
        int gridW = cols * tileW + TILE_GAP * (cols - 1);
        int gridH = rows * tileH + TILE_GAP * (rows - 1);
        int x0 = area.x() + Math.max(0, (area.w() - gridW) / 2);
        int y0 = area.y() + Math.max(0, (area.h() - gridH) / 2);
        List<InventoryEditorLayout.Rect> out = new ArrayList<>(cols * rows);
        for (int i = 0; i < cols * rows; i++) {
            out.add(new InventoryEditorLayout.Rect(
                x0 + (i % cols) * (tileW + TILE_GAP), y0 + (i / cols) * (tileH + TILE_GAP), tileW, tileH));
        }
        return out;
    }

    /** The tile the plots are stamped with, or null between worlds. */
    static BuilderMode hereMode(EditorRosterIndex index) {
        PlotCategory stamped = index == null ? null : index.stampedCategory();
        if (stamped == null) return null;
        return BuilderModeCategory.modeOf(stamped.owner()).orElse(null);
    }

    /** The picked tile: what was clicked, else where the player is, else the first. */
    static BuilderMode selected(EditorRosterIndex index) {
        BuilderMode picked = EditorScreenState.navMode();
        if (picked != null) return picked;
        BuilderMode here = hereMode(index);
        return here != null ? here : BuilderMode.values()[0];
    }

    /** Whether Go here may be pressed: only somewhere other than where the player already is. */
    static boolean canGo(BuilderMode selected, EditorRosterIndex index) {
        return selected != null && selected != hereMode(index);
    }

    /** The command Go here runs once confirmed — the same in-world switch Enter makes. */
    static String switchCommand(BuilderMode mode) {
        EditorCategory category = BuilderModeCategory.of(mode);
        return "dungeontrain editor " + category.id();
    }

    void render(GuiGraphics g, Font font, EditorScreenTheme theme, InventoryEditorLayout layout,
                EditorRosterIndex index, int mouseX, int mouseY) {
        BuilderMode selected = selected(index);
        BuilderMode here = hereMode(index);
        drawTiles(g, theme, layout, selected, here, mouseX, mouseY);
        drawDetail(g, font, theme, layout, selected, index, mouseX, mouseY);
    }

    private void drawTiles(GuiGraphics g, EditorScreenTheme theme, InventoryEditorLayout layout,
                           BuilderMode selected, BuilderMode here, int mouseX, int mouseY) {
        InventoryEditorLayout.Rect r = rect(layout);
        g.fill(r.x() - 1, r.y() - 1, r.right() + 1, r.bottom() + 1, theme.subPanel());
        List<InventoryEditorLayout.Rect> cells = tiles(r);
        BuilderMode[] modes = BuilderMode.values();
        tileRects.clear();
        for (int i = 0; i < modes.length && i < cells.size(); i++) {
            BuilderMode mode = modes[i];
            InventoryEditorLayout.Rect cell = cells.get(i);
            tileRects.put(mode, cell);
            boolean lit = mode == selected || cell.contains(mouseX, mouseY);
            BuilderTileArt.renderTile(g, mode, available(mode), cell.x(), cell.y(), cell.w(), cell.h(),
                true, lit, 1.0F);
            // The same green dot the tab strip wears for the page the player stands in.
            if (mode == here) {
                g.fill(cell.right() - 7, cell.y() + 3, cell.right() - 3, cell.y() + 7, EditorTabBar.HERE);
            }
        }
    }

    private void drawDetail(GuiGraphics g, Font font, EditorScreenTheme theme, InventoryEditorLayout layout,
                            BuilderMode selected, EditorRosterIndex index, int mouseX, int mouseY) {
        // Header: the area's name where a template's name would be.
        InventoryEditorLayout.Rect h = layout.header();
        String name = Component.translatable(selected.labelKey()).getString();
        g.drawString(font, font.plainSubstrByWidth(name, h.w() - 4), h.x() + 2,
            h.y() + (h.h() - font.lineHeight) / 2, theme.panelText(), !theme.isLight());

        // Preview: the picture, cover-cropped, where the rotating model would be.
        InventoryEditorLayout.Rect p = layout.preview();
        g.fill(p.x(), p.y(), p.right(), p.bottom(), PreviewPane.BACKDROP);
        BuilderTileArt.render(g, selected, available(selected), p.x(), p.y(), p.w(), p.h(), 1.0F);
        g.renderOutline(p.x(), p.y(), p.w(), p.h(), theme.outline());

        // Description: from the top of the sheet down to the Go button, wrapped to the column.
        InventoryEditorLayout.Rect s = layout.sheet();
        InventoryEditorLayout.Rect t = layout.test();
        int textTop = s.y() + TEXT_PAD;
        int textBottom = t.y() - TEXT_PAD;
        int textW = Math.max(0, s.w() - TEXT_PAD * 2);
        List<FormattedCharSequence> lines = font.split(Component.translatable(selected.descriptionKey()), textW);
        int y = textTop;
        for (FormattedCharSequence line : lines) {
            if (y + LINE_H > textBottom) break;
            g.drawString(font, line, s.x() + TEXT_PAD, y, DESCRIPTION, false);
            y += LINE_H;
        }

        // Go here, along the bottom, the width of the column. Greyed with "You're here" when the
        // picked area is the one already stamped: the answer to "you are there" is nothing.
        goEnabled = canGo(selected, index);
        goRect = t;
        boolean hot = goEnabled && t.contains(mouseX, mouseY);
        g.fill(t.x(), t.y(), t.right(), t.bottom(), !goEnabled ? EditorDetailPane.DISABLED : hot ? GO_HOT : GO_IDLE);
        String label = EditorScreenLang.text(goEnabled ? EditorScreenLang.NAV_GO_HERE : EditorScreenLang.NAV_HERE);
        String shown = font.plainSubstrByWidth(label, t.w() - 6);
        g.drawString(font, shown, t.x() + (t.w() - font.width(shown)) / 2,
            t.y() + (t.h() - font.lineHeight) / 2 + 1,
            !goEnabled ? GO_TEXT_DISABLED : hot ? MenuRowPainter.TEXT_ON_HOVER : GO_TEXT, false);
    }

    /** Probed once per mode per pane rather than per frame; a resource reload rebuilds the screen. */
    private boolean available(BuilderMode mode) {
        return artAvailable.computeIfAbsent(mode, BuilderTileArt::isAvailable);
    }

    /** What is under the point, from the rects the last frame drew. */
    Hit hitTest(double mouseX, double mouseY) {
        for (Map.Entry<BuilderMode, InventoryEditorLayout.Rect> e : tileRects.entrySet()) {
            if (e.getValue().contains(mouseX, mouseY)) return new Hit(Kind.TILE, e.getKey());
        }
        if (goEnabled && goRect != null && goRect.contains(mouseX, mouseY)) return new Hit(Kind.GO_HERE, null);
        return Hit.NONE;
    }

    /** Tooltip lines for the point, or empty. */
    List<String> tooltipAt(double mouseX, double mouseY, EditorRosterIndex index) {
        if (goRect == null || !goRect.contains(mouseX, mouseY)) return List.of();
        BuilderMode selected = selected(index);
        String name = Component.translatable(selected.labelKey()).getString();
        return goEnabled
            ? List.of(EditorScreenLang.text(EditorScreenLang.NAV_GO_HERE_TIP, name))
            : List.of(EditorScreenLang.text(EditorScreenLang.NAV_HERE));
    }
}
