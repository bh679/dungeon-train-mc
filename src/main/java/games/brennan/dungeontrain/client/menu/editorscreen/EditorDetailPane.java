package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.builder.TemplateSummary;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.EditorMenuScreen;
import games.brennan.dungeontrain.client.menu.EditorSaveStatus;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * The right pane: header, preview, data sheet, the icon row, the per-plot settings rows, and
 * the pinned Test button. Reads what to show from {@link EditorScreenActions}; draws with the
 * shared row painter so rows here look like rows everywhere else.
 */
public final class EditorDetailPane {

    static final int ROW_H = 14;
    static final int ICON_SIZE = 16;
    static final int ICON_CELL = 20;
    static final int ICON_GAP = 2;
    static final int ICON_GROUP_GAP = 6;
    /** Below this a button stops reading as one, so the spacing goes before the size does. */
    static final int MIN_ICON_CELL = 12;
    static final int HERE_TEXT = 0xFF55FF55;
    static final int DIM_TEXT = 0xB0FFFFFF;
    static final int DISABLED = 0x30FFFFFF;
    static final int DISABLED_ICON = 0x60FFFFFF;

    /** What a click landed on. */
    public enum HitKind { NONE, ICON, ROW, TEST, PREVIEW, SHEET, GO_HERE, OLDER, NEWER }

    private final VersionStrip versions = new VersionStrip();
    /** The relay row of the selected template, and the version of it being shown (0 = as it is now). */
    private int relayId;
    private int seq;

    /** Which version of the selection the preview shows. Set by the screen before each render. */
    public void showVersion(int relayId, int seq) {
        this.relayId = relayId;
        this.seq = seq;
    }

    public record Hit(HitKind kind, int index, int sub) {
        public static final Hit NONE = new Hit(HitKind.NONE, -1, 0);
    }

    private InventoryEditorLayout layout;
    private EditorScreenActions.Ctx ctx;
    private List<EditorScreenActions.Icon> icons = List.of();
    private List<CommandMenuEntry> rows = List.of();
    private List<CommandMenuEntry> roomRows = List.of();
    private List<TemplateDataSheet.Line> sheetLines = List.of();
    private List<TemplateDataSheet.Placed> sheetCells = List.of();
    private CommandMenuEntry test;
    private int scroll;
    private int visibleRows;
    private Hit hovered = Hit.NONE;
    private int[] iconX = new int[0];
    private int iconCell = ICON_CELL;
    private InventoryEditorLayout.Rect goHereRect;
    private CommandMenuEntry goHere;

    public Hit hovered() { return hovered; }
    public List<EditorScreenActions.Icon> icons() { return icons; }
    public List<CommandMenuEntry> rows() { return rows; }
    public CommandMenuEntry testEntry() { return test; }
    /** The teleport button in the header, or null when the author is already standing there. */
    public CommandMenuEntry goHereEntry() { return goHere; }
    public EditorScreenActions.Ctx ctx() { return ctx; }

    /** Build this frame's rows and icons from the selection. */
    public void layout(InventoryEditorLayout layout, EditorScreenActions.Ctx ctx, long nowMillis) {
        this.layout = layout;
        this.ctx = ctx;
        // The relay row of what is selected, which showVersion has already handed this pane for the
        // version strip — it is what the Submit icon acts on.
        icons = EditorScreenActions.icons(ctx, DungeonTrainNet::sendToServer, relayId);
        // Read once: the Size line takes the room's own dimensions from these, and the rows below
        // take everything else. Reading them twice could show two different numbers for one axis.
        roomRows = ctx.standingInSelection() && ctx.category() == PlotCategory.PORTALS
            ? EditorMenuScreen.portalRows() : List.of();
        rows = EditorScreenActions.settingRows(ctx, () -> roomRows, EditorStatusHudOverlay::roomMode);
        test = EditorScreenActions.testEntry(ctx);
        // Standing somewhere else is not just a fact to report — it is the one thing in the way of
        // half these controls, so the header offers the walk rather than only naming it.
        goHere = ctx.hasSelection() && !ctx.standingInSelection()
            ? EditorScreenActions.enterEntry(ctx) : null;
        visibleRows = Math.max(0, layout.settings().h() / ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visibleRows)));

        IconRow row = layoutIcons(icons.size(), layout.icons().x(), layout.icons().w());
        iconX = row.x();
        iconCell = row.cell();
    }

    /** Icon-row geometry: how big each button is, and where each one starts. */
    record IconRow(int cell, int[] x) {}

    /**
     * Fit {@code count} buttons across {@code width}.
     *
     * <p>The row is the pane's toolbar and every button in it matters, so it gives up looks before
     * it gives up buttons. Size goes first: a button two pixels narrower still reads as one,
     * whereas the wider breaks are what hold Remove and Clear apart from the buttons either side of
     * them, and that is a safety cue rather than a decoration. Only once the buttons would shrink
     * past legible does the grouping go, and then the spacing between them.</p>
     */
    static IconRow layoutIcons(int count, int x0, int width) {
        if (count <= 0) return new IconRow(0, new int[0]);
        int gap = ICON_GAP;
        int groupGap = ICON_GROUP_GAP;
        int cell = fit(count, width, gap, groupGap * groupBreaks(count));
        if (cell < MIN_ICON_CELL) {
            // Out of room to shrink: the grouping goes, the buttons stay.
            groupGap = 0;
            cell = fit(count, width, gap, 0);
        }
        if (cell < MIN_ICON_CELL) {
            gap = 1;
            cell = Math.max(1, fit(count, width, gap, 0));
        }

        int[] xs = new int[count];
        int x = x0;
        for (int i = 0; i < count; i++) {
            if (isGroupBreak(i)) x += groupGap;
            xs[i] = x;
            x += cell + gap;
        }
        return new IconRow(cell, xs);
    }

    /** The widest cell that fits, never larger than a button wants to be. */
    private static int fit(int count, int width, int gap, int groupGaps) {
        return Math.min(ICON_CELL, (width - (count - 1) * gap - groupGaps) / count);
    }

    /** Save · Rename · Remove | Undo · Redo | Reset · Clear | Package — the breaks between groups. */
    private static boolean isGroupBreak(int i) {
        return i == 3 || i == 5 || i == 7;
    }

    private static int groupBreaks(int count) {
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (isGroupBreak(i)) n++;
        }
        return n;
    }

    public boolean overSettings(double mx, double my) {
        return layout != null && layout.settings().contains(mx, my);
    }

    public boolean overPreview(double mx, double my) {
        return layout != null && layout.preview().contains(mx, my);
    }

    public boolean scrollBy(int dir) {
        int max = Math.max(0, rows.size() - visibleRows);
        int next = Math.max(0, Math.min(scroll + dir, max));
        boolean moved = next != scroll;
        scroll = next;
        return moved || max > 0;
    }

    public void render(GuiGraphics g, Font font, EditorScreenTheme theme, TemplateArt art,
                       TemplateSummary summary, EditorRosterIndex.Tile tile, String pathLabel,
                       float yaw, int mouseX, int mouseY) {
        hovered = hitTest(mouseX, mouseY);
        drawHeader(g, font, theme);
        String name = tile == null ? "" : tile.variant().name();
        PreviewPane.draw(g, font, layout.preview(), art, name, yaw, theme, seq == 0 ? 0 : relayId, seq);
        versions.draw(g, font, layout.preview(), relayId, seq, mouseX, mouseY);
        sheetLines = TemplateDataSheet.lines(tile, pathLabel, summary,
            tile == null ? EditorRosterIndex.Provenance.BUILTIN : EditorRosterIndex.provenanceOf(tile.variant()),
            ctx.selection(), roomRows);
        sheetCells = TemplateDataSheet.place(sheetLines, layout.sheet(), font);
        TemplateDataSheet.draw(g, font, layout.sheet(), sheetLines, sheetCells,
            hovered.kind() == HitKind.SHEET ? hovered.index() : -1);
        drawIcons(g);
        drawRows(g, font, theme);
        drawTest(g, font);
    }

    private void drawHeader(GuiGraphics g, Font font, EditorScreenTheme theme) {
        InventoryEditorLayout.Rect h = layout.header();
        int ty = h.y() + (h.h() - font.lineHeight) / 2;
        String name = ctx.hasSelection() ? ctx.selection().displayName()
            : EditorScreenLang.text(EditorScreenLang.NOTHING_SELECTED);
        int x = h.x() + 2;
        g.drawString(font, name, x, ty, theme.panelText(), theme.isLight() ? false : true);
        x += font.width(name) + 6;
        if (ctx.dirty()) {
            g.fill(x, ty + 2, x + 4, ty + 6, TemplateTilePainter.DIRTY);
            x += 8;
        }
        int saveX = h.right() - 1;
        goHereRect = null;
        if (ctx.standingInSelection()) {
            String status = "● " + EditorScreenLang.text(EditorScreenLang.YOU_ARE_HERE);
            g.drawString(font, font.plainSubstrByWidth(status, Math.max(0, saveX - x - 4)),
                x, ty, HERE_TEXT, false);
        } else if (goHere != null) {
            // A button, not a sentence: the answer to "you are not there" is to go.
            String label = EditorScreenLang.text(EditorScreenLang.GO_HERE);
            int w = font.width(label) + 8;
            int bx = Math.min(x, Math.max(h.x(), saveX - w - 2));
            goHereRect = new InventoryEditorLayout.Rect(bx, h.y() + 1, w, h.h() - 2);
            boolean hot = hovered.kind() == HitKind.GO_HERE;
            g.fill(goHereRect.x(), goHereRect.y(), goHereRect.right(), goHereRect.bottom(),
                hot ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
            g.drawString(font, label, bx + 4, ty, hot ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
        }
    }

    private void drawIcons(GuiGraphics g) {
        InventoryEditorLayout.Rect r = layout.icons();
        for (int i = 0; i < icons.size(); i++) {
            EditorScreenActions.Icon icon = icons.get(i);
            int x = iconX[i];
            boolean hov = hovered.kind() == HitKind.ICON && hovered.index() == i;
            boolean danger = "remove".equals(icon.id()) || "clear".equals(icon.id());
            int fill = !icon.enabled() ? DISABLED
                : hov ? (danger ? 0xC0FF5544 : MenuRowPainter.CELL_HOVER) : MenuRowPainter.CELL_IDLE;
            g.fill(x, r.y(), x + iconCell, r.y() + iconCell, fill);
            if (!icon.enabled()) {
                tint(g, DISABLED_ICON);
            } else if ("save".equals(icon.id())) {
                // Save says the state as well as offering the action: breathing green while this
                // build has unsaved work, steady blue once it matches what is on disk. It is the
                // only status the pane cannot show as a number, so it lives on its own button
                // rather than on a second one beside it.
                tint(g, EditorSaveStatus.tint(ctx.dirty(), System.currentTimeMillis()));
            } else if ("submit".equals(icon.id()) || "withdraw".equals(icon.id())) {
                // The same trick, for the other thing a build's state cannot be read off this pane:
                // whether it has been offered to the train. Pulsing blue while it is only saved,
                // steady green once submitted — and kept through the hover, like Save, because the
                // state is the reason to press it.
                tint(g, SubmitTint.of("withdraw".equals(icon.id()), System.currentTimeMillis()));
            } else if (hov) {
                tint(g, 0xFF000000);
            }
            int sprite = Math.min(ICON_SIZE, iconCell);
            g.blitSprite(EditorIcons.forAction(icon.id()), x + (iconCell - sprite) / 2,
                r.y() + (iconCell - sprite) / 2, sprite, sprite);
            g.setColor(1f, 1f, 1f, 1f);
        }
    }

    private void drawRows(GuiGraphics g, Font font, EditorScreenTheme theme) {
        InventoryEditorLayout.Rect r = layout.settings();
        g.enableScissor(r.x(), r.y(), r.right(), r.bottom());
        for (int k = 0; k < visibleRows && scroll + k < rows.size(); k++) {
            int idx = scroll + k;
            int top = r.y() + k * ROW_H;
            boolean hov = hovered.kind() == HitKind.ROW && hovered.index() == idx;
            MenuRowPainter.drawRow(g, font, rows.get(idx), r.x(), top, r.right(), ROW_H - 1,
                idx, hov, hovered.sub(), null);
        }
        g.disableScissor();
        if (rows.size() > visibleRows && visibleRows > 0) {
            int trackH = r.h();
            int thumbH = Math.max(6, trackH * visibleRows / rows.size());
            int thumbY = r.y() + (trackH - thumbH) * scroll / Math.max(1, rows.size() - visibleRows);
            g.fill(r.right() - 2, r.y(), r.right(), r.bottom(), 0x40FFFFFF);
            g.fill(r.right() - 2, thumbY, r.right(), thumbY + thumbH, 0xC0FFEEBB);
        }
    }

    private void drawTest(GuiGraphics g, Font font) {
        InventoryEditorLayout.Rect r = layout.test();
        boolean enabled = test != null;
        boolean hov = enabled && hovered.kind() == HitKind.TEST;
        g.fill(r.x(), r.y(), r.right(), r.bottom(), !enabled ? DISABLED : hov ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
        String label = EditorScreenLang.text(EditorScreenLang.TEST_CARRIAGE);
        int tw = font.width(label) + 12;
        int x = r.x() + (r.w() - tw) / 2;
        if (!enabled) tint(g, DISABLED_ICON);
        else if (hov) tint(g, 0xFF000000);
        g.blitSprite(EditorIcons.PLAY, x, r.y() + (r.h() - 10) / 2, 10, 10);
        g.setColor(1f, 1f, 1f, 1f);
        g.drawString(font, label, x + 12, r.y() + (r.h() - font.lineHeight) / 2 + 1,
            !enabled ? 0x80FFFFFF : hov ? 0xFF000000 : 0xFFFFFFFF, false);
    }

    private static void tint(GuiGraphics g, int argb) {
        g.setColor(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
            (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
    }

    public Hit hitTest(double mx, double my) {
        if (layout == null) return Hit.NONE;
        switch (versions.hit(mx, my)) {
            case OLDER -> { return new Hit(HitKind.OLDER, 0, 0); }
            case NEWER -> { return new Hit(HitKind.NEWER, 0, 0); }
            case NONE -> { }
        }
        if (goHereRect != null && goHereRect.contains(mx, my)) return new Hit(HitKind.GO_HERE, 0, 0);
        if (layout.preview().contains(mx, my)) return new Hit(HitKind.PREVIEW, 0, 0);
        int sheetCell = TemplateDataSheet.hit(sheetCells, mx, my);
        if (sheetCell >= 0) return new Hit(HitKind.SHEET, sheetCell, 0);
        InventoryEditorLayout.Rect ir = layout.icons();
        if (my >= ir.y() && my < ir.y() + iconCell) {
            for (int i = 0; i < iconX.length; i++) {
                if (mx >= iconX[i] && mx < iconX[i] + iconCell) return new Hit(HitKind.ICON, i, 0);
            }
        }
        InventoryEditorLayout.Rect r = layout.settings();
        if (r.contains(mx, my)) {
            int k = (int) ((my - r.y()) / ROW_H);
            int idx = scroll + k;
            if (k < visibleRows && idx < rows.size()) {
                int sub = MenuRowPainter.hitCell(rows.get(idx), (int) mx, r.x(), r.right());
                if (sub >= 0) return new Hit(HitKind.ROW, idx, sub);
            }
            return Hit.NONE;
        }
        if (layout.test().contains(mx, my)) return new Hit(HitKind.TEST, 0, 0);
        return Hit.NONE;
    }

    /**
     * The tooltip for the hovered control: its name, and why it is off when it is.
     *
     * <p>Two lines rather than one long one — a name joined to a sentence ran off the edge of the
     * screen, and the name is what the pointer is asking about.</p>
     */
    public List<String> tooltipAt(Hit hit) {
        return switch (hit.kind()) {
            case ICON -> {
                if (hit.index() < 0 || hit.index() >= icons.size()) yield List.of();
                EditorScreenActions.Icon icon = icons.get(hit.index());
                String label = EditorScreenLang.text(icon.labelKey());
                if (!icon.enabled()) {
                    yield icon.disabledKey() == null ? List.of(label)
                        : List.of(label, EditorScreenLang.text(icon.disabledKey()));
                }
                // Undo and Redo name the step they would apply; the rest speak for themselves.
                yield icon.detail() == null ? List.of(label) : List.of(label, icon.detail());
            }
            case SHEET -> {
                TemplateDataSheet.Placed placed = sheetCell(hit.index());
                yield placed == null || placed.cell().tooltip() == null
                    ? List.of() : List.of(placed.cell().tooltip());
            }
            case GO_HERE -> goHere == null || ctx.selection() == null ? List.of()
                : List.of(EditorScreenLang.text(EditorScreenLang.GO_HERE),
                          EditorScreenLang.text(EditorScreenLang.STANDING_IN, ctx.selection().displayName()));
            // Only dimensions can be stood up, and that is the whole of why the button is off —
            // it no longer asks the author to stand anywhere.
            case TEST -> test == null
                ? List.of(EditorScreenLang.text(EditorScreenLang.TEST_CARRIAGE),
                          EditorScreenLang.text(EditorScreenLang.DISABLED_DIMENSIONS_ONLY))
                : List.of();
            default -> List.of();
        };
    }

    /** The sheet cell a click landed on, or null. */
    public TemplateDataSheet.Placed sheetCell(int index) {
        return index >= 0 && index < sheetCells.size() ? sheetCells.get(index) : null;
    }

    /** The roster group label for the sheet's Path line. */
    public static String pathLabel(EditorRosterIndex index, VariantKey key) {
        if (key == null) return "";
        EditorRosterPacket.Group g = index.groupOf(key);
        EditorScreenPage page = EditorScreenPage.forCategory(key.category());
        String pageName = page == null ? key.category().displayName() : EditorScreenLang.text(page.langKey());
        String type = g == null ? "" : " › " + g.typeName();
        String parent = key.isSubVariant() ? " › " + key.parentId() : "";
        return pageName + type + parent;
    }
}
