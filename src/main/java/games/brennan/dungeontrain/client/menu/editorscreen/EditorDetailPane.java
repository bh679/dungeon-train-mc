package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.builder.TemplateSummary;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.EditorMenuScreen;
import games.brennan.dungeontrain.client.menu.MenuHeaderAction;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.config.EditorScreenTheme;
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
    static final int HERE_TEXT = 0xFF55FF55;
    static final int DIM_TEXT = 0xB0FFFFFF;
    static final int DISABLED = 0x30FFFFFF;
    static final int DISABLED_ICON = 0x60FFFFFF;

    /** What a click landed on. */
    public enum HitKind { NONE, SAVE_ALL, ICON, ROW, TEST, PREVIEW }

    public record Hit(HitKind kind, int index, int sub) {
        public static final Hit NONE = new Hit(HitKind.NONE, -1, 0);
    }

    private InventoryEditorLayout layout;
    private EditorScreenActions.Ctx ctx;
    private List<EditorScreenActions.Icon> icons = List.of();
    private List<CommandMenuEntry> rows = List.of();
    private CommandMenuEntry test;
    private MenuHeaderAction saveAll;
    private int scroll;
    private int visibleRows;
    private Hit hovered = Hit.NONE;
    private int[] iconX = new int[0];

    public Hit hovered() { return hovered; }
    public List<EditorScreenActions.Icon> icons() { return icons; }
    public List<CommandMenuEntry> rows() { return rows; }
    public CommandMenuEntry testEntry() { return test; }
    public MenuHeaderAction saveAll() { return saveAll; }
    public EditorScreenActions.Ctx ctx() { return ctx; }

    /** Build this frame's rows and icons from the selection. */
    public void layout(InventoryEditorLayout layout, EditorScreenActions.Ctx ctx, long nowMillis) {
        this.layout = layout;
        this.ctx = ctx;
        icons = EditorScreenActions.icons(ctx, DungeonTrainNet::sendToServer);
        rows = EditorScreenActions.settingRows(ctx, EditorMenuScreen::portalRows, EditorStatusHudOverlay::roomMode);
        test = EditorScreenActions.testEntry(ctx);
        saveAll = EditorScreenActions.saveAll(ctx, nowMillis);
        visibleRows = Math.max(0, layout.settings().h() / ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visibleRows)));

        int[] xs = new int[icons.size()];
        int x = layout.icons().x();
        for (int i = 0; i < icons.size(); i++) {
            if (i == 3 || i == 5 || i == 7) x += ICON_GROUP_GAP;
            xs[i] = x;
            x += ICON_CELL + ICON_GAP;
        }
        iconX = xs;
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
        PreviewPane.draw(g, font, layout.preview(), art, name, yaw, theme);
        TemplateDataSheet.draw(g, font, layout.sheet(), TemplateDataSheet.lines(tile, pathLabel, summary,
            tile == null ? EditorRosterIndex.Provenance.BUILTIN : EditorRosterIndex.provenanceOf(tile.variant())));
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
        String status;
        int statusColour;
        if (ctx.standingInSelection()) {
            status = "● " + EditorScreenLang.text(EditorScreenLang.YOU_ARE_HERE);
            statusColour = HERE_TEXT;
        } else if (ctx.standing() != null) {
            status = EditorScreenLang.text(EditorScreenLang.STANDING_IN, ctx.standing().displayName());
            statusColour = theme.isLight() ? 0xFF3F3F3F : DIM_TEXT;
        } else {
            status = "";
            statusColour = DIM_TEXT;
        }
        int saveX = h.right() - ICON_SIZE - 1;
        if (!status.isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(status, Math.max(0, saveX - x - 4)), x, ty, statusColour, false);
        }
        // Save-all icon: the same breathing tint as the header icon of the old menu.
        boolean hov = hovered.kind() == HitKind.SAVE_ALL;
        g.fill(saveX - 2, h.y(), h.right(), h.bottom(), hov ? MenuRowPainter.CELL_HOVER : 0x30000000);
        tint(g, saveAll.tint());
        g.blitSprite(saveAll.icon(), saveX, h.y() + (h.h() - ICON_SIZE) / 2 + 1, ICON_SIZE, ICON_SIZE);
        g.setColor(1f, 1f, 1f, 1f);
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
            g.fill(x, r.y(), x + ICON_CELL, r.y() + ICON_CELL, fill);
            if (!icon.enabled()) tint(g, DISABLED_ICON);
            else if (hov) tint(g, 0xFF000000);
            g.blitSprite(EditorIcons.forAction(icon.id()), x + (ICON_CELL - ICON_SIZE) / 2,
                r.y() + (ICON_CELL - ICON_SIZE) / 2, ICON_SIZE, ICON_SIZE);
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
        InventoryEditorLayout.Rect h = layout.header();
        if (h.contains(mx, my) && mx >= h.right() - ICON_SIZE - 3) return new Hit(HitKind.SAVE_ALL, 0, 0);
        if (layout.preview().contains(mx, my)) return new Hit(HitKind.PREVIEW, 0, 0);
        InventoryEditorLayout.Rect ir = layout.icons();
        if (my >= ir.y() && my < ir.y() + ICON_CELL) {
            for (int i = 0; i < iconX.length; i++) {
                if (mx >= iconX[i] && mx < iconX[i] + ICON_CELL) return new Hit(HitKind.ICON, i, 0);
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

    /** The tooltip for the hovered control, or null. */
    public String tooltipAt(Hit hit) {
        return switch (hit.kind()) {
            case SAVE_ALL -> saveAll == null ? null : saveAll.label();
            case ICON -> {
                if (hit.index() < 0 || hit.index() >= icons.size()) yield null;
                EditorScreenActions.Icon icon = icons.get(hit.index());
                String label = EditorScreenLang.text(icon.labelKey());
                yield icon.enabled() || icon.disabledKey() == null ? label
                    : label + " — " + EditorScreenLang.text(icon.disabledKey());
            }
            case TEST -> test == null ? EditorScreenLang.text(EditorScreenLang.DISABLED_STAND_HERE) : null;
            default -> null;
        };
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
