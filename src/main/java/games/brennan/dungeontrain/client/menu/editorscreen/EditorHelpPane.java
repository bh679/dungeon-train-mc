package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The Help tab: one screen per editor system, read without leaving the game.
 *
 * <p>Left, a row per {@link EditorHelpTopic} in the column the Settings rows fill. Right, in the
 * slots the detail pane uses for a template, the picked topic's title where the name would be and
 * its paragraphs wrapped to the column from where the model would start down to the foot. When the
 * words run past the foot the last row becomes the {@code <  n / N  >} pager the stage detail
 * pages with; when they fit, there is no pager.</p>
 *
 * <p>The same shape as {@link EditorNavPane}: lay out, render, hit-test. The page arithmetic is
 * pure so the fit can be pinned at the sizes that matter without a client.</p>
 */
final class EditorHelpPane {

    /** What a click landed on. {@code topic} is set for TOPIC only. */
    record Hit(Kind kind, EditorHelpTopic topic) {
        static final Hit NONE = new Hit(Kind.NONE, null);
    }

    enum Kind { NONE, TOPIC, PAGE_PREV, PAGE_NEXT }

    static final int ROW_H = EditorDetailPane.ROW_H;
    static final int LINE_H = EditorNavPane.LINE_H;
    static final int TEXT_PAD = EditorNavPane.TEXT_PAD;
    static final int BODY_TEXT = EditorNavPane.DESCRIPTION;
    static final int ROW_SELECTED = 0x60FFFFFF;

    private InventoryEditorLayout.Rect rowsRect;
    private InventoryEditorLayout.Rect bodyRect;
    private InventoryEditorLayout.Rect pagerRect;
    private int pageCount = 1;

    /** The rows' column: the same rectangle the Settings rows fill. */
    static InventoryEditorLayout.Rect rect(InventoryEditorLayout layout) {
        return EditorSettingsPane.rect(layout);
    }

    /**
     * The right column from just under the header to the foot: the toolbar's row, the model's, the
     * sheet's and the test button's, all given to the words — none of those controls apply to a topic.
     */
    static InventoryEditorLayout.Rect body(InventoryEditorLayout layout) {
        InventoryEditorLayout.Rect h = layout.header();
        InventoryEditorLayout.Rect t = layout.test();
        int top = h.bottom() + 2;
        return new InventoryEditorLayout.Rect(h.x(), top, h.w(), Math.max(0, t.bottom() - top));
    }

    /** The body with its last row given up to the pager. */
    static InventoryEditorLayout.Rect bodyAbovePager(InventoryEditorLayout.Rect body) {
        return new InventoryEditorLayout.Rect(body.x(), body.y(), body.w(),
            Math.max(0, body.h() - InventoryEditorLayout.PAGER_H));
    }

    /** The pager row at the foot of the body. */
    static InventoryEditorLayout.Rect pager(InventoryEditorLayout.Rect body) {
        return new InventoryEditorLayout.Rect(body.x(), body.bottom() - InventoryEditorLayout.PAGER_H,
            body.w(), InventoryEditorLayout.PAGER_H);
    }

    /** How many wrapped lines a body this tall shows, padding taken. */
    static int linesPerPage(int bodyH) {
        return Math.max(1, (bodyH - TEXT_PAD * 2) / LINE_H);
    }

    static int pageCount(int lines, int perPage) {
        return Math.max(1, (lines + perPage - 1) / perPage);
    }

    /** Whether the topic list fits its column without scrolling — it always should, and a test says so. */
    static boolean rowsFit(InventoryEditorLayout.Rect rows) {
        return rows.h() / ROW_H >= EditorHelpTopic.values().length;
    }

    /**
     * The topic's paragraphs wrapped to the width, a blank line between paragraphs. Trailing blank
     * lines are not emitted so a page never ends on one.
     */
    static List<FormattedCharSequence> wrap(Font font, EditorHelpTopic topic, int width) {
        List<FormattedCharSequence> out = new ArrayList<>();
        List<String> keys = topic.paragraphKeys();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) out.add(FormattedCharSequence.EMPTY);
            out.addAll(font.split(Component.translatable(keys.get(i)), Math.max(1, width)));
        }
        return out;
    }

    void render(GuiGraphics g, Font font, EditorScreenTheme theme, InventoryEditorLayout layout,
                int mouseX, int mouseY) {
        EditorHelpTopic selected = EditorScreenState.helpTopic();
        drawRows(g, font, theme, layout, selected, mouseX, mouseY);
        drawBody(g, font, theme, layout, selected, mouseX, mouseY);
    }

    private void drawRows(GuiGraphics g, Font font, EditorScreenTheme theme, InventoryEditorLayout layout,
                          EditorHelpTopic selected, int mouseX, int mouseY) {
        InventoryEditorLayout.Rect r = rect(layout);
        rowsRect = r;
        g.fill(r.x() - 1, r.y() - 1, r.right() + 1, r.bottom() + 1, theme.subPanel());
        EditorHelpTopic[] topics = EditorHelpTopic.values();
        int visible = Math.max(1, r.h() / ROW_H);
        for (int k = 0; k < visible && k < topics.length; k++) {
            EditorHelpTopic topic = topics[k];
            int y = r.y() + k * ROW_H;
            boolean hovered = r.contains(mouseX, mouseY) && (mouseY - r.y()) / ROW_H == k;
            boolean isSelected = topic == selected;
            int fill = hovered ? MenuRowPainter.CELL_HOVER : isSelected ? ROW_SELECTED : MenuRowPainter.CELL_IDLE;
            g.fill(r.x(), y, r.right(), y + ROW_H - 1, fill);
            String label = EditorScreenLang.text(topic.titleKey());
            g.drawString(font, font.plainSubstrByWidth(label, r.w() - TEXT_PAD * 2),
                r.x() + TEXT_PAD, y + (ROW_H - 1 - font.lineHeight) / 2 + 1,
                hovered ? MenuRowPainter.TEXT_ON_HOVER : MenuRowPainter.TEXT_NORMAL, false);
        }
    }

    private void drawBody(GuiGraphics g, Font font, EditorScreenTheme theme, InventoryEditorLayout layout,
                          EditorHelpTopic selected, int mouseX, int mouseY) {
        // Header: the topic's title where a template's name would be.
        InventoryEditorLayout.Rect h = layout.header();
        String title = EditorScreenLang.text(selected.titleKey());
        g.drawString(font, font.plainSubstrByWidth(title, h.w() - 4), h.x() + 2,
            h.y() + (h.h() - font.lineHeight) / 2, MenuRowPainter.TEXT_HEADER, false);

        InventoryEditorLayout.Rect full = body(layout);
        int textW = Math.max(0, full.w() - TEXT_PAD * 2);
        List<FormattedCharSequence> lines = wrap(font, selected, textW);

        // Paged only when the words run past the foot; then the foot row is the pager's.
        boolean paged = lines.size() > linesPerPage(full.h());
        InventoryEditorLayout.Rect text = paged ? bodyAbovePager(full) : full;
        int perPage = linesPerPage(text.h());
        pageCount = pageCount(lines.size(), perPage);
        int page = Math.min(EditorScreenState.helpPage(), pageCount - 1);
        bodyRect = full;
        pagerRect = paged ? pager(full) : null;

        g.fill(text.x(), text.y(), text.right(), text.bottom(), theme.subPanel());
        int y = text.y() + TEXT_PAD;
        for (int i = page * perPage; i < lines.size() && i < (page + 1) * perPage; i++) {
            g.drawString(font, lines.get(i), text.x() + TEXT_PAD, y, BODY_TEXT, false);
            y += LINE_H;
        }

        if (paged) {
            EditorPager.Hit hovered = EditorPager.hit(pagerRect, page, pageCount, mouseX, mouseY);
            EditorPager.draw(g, font, pagerRect, page, pageCount, hovered);
        }
    }

    /** What is under the point, from the rects the last frame drew. */
    Hit hitTest(double mouseX, double mouseY) {
        if (rowsRect != null && rowsRect.contains(mouseX, mouseY)) {
            int k = (int) ((mouseY - rowsRect.y()) / ROW_H);
            EditorHelpTopic[] topics = EditorHelpTopic.values();
            if (k >= 0 && k < topics.length) return new Hit(Kind.TOPIC, topics[k]);
            return Hit.NONE;
        }
        if (pagerRect != null) {
            int page = Math.min(EditorScreenState.helpPage(), pageCount - 1);
            switch (EditorPager.hit(pagerRect, page, pageCount, mouseX, mouseY)) {
                case PREV -> { return new Hit(Kind.PAGE_PREV, null); }
                case NEXT -> { return new Hit(Kind.PAGE_NEXT, null); }
                default -> { }
            }
        }
        return Hit.NONE;
    }

    /** Whether the point is over the body — where the wheel pages. */
    boolean overBody(double mouseX, double mouseY) {
        return bodyRect != null && bodyRect.contains(mouseX, mouseY);
    }

    /** Wheel or arrow: one page per notch, held within the topic. */
    boolean scrollBy(int dir) {
        int page = Math.min(EditorScreenState.helpPage(), pageCount - 1);
        int next = Math.max(0, Math.min(pageCount - 1, page + dir));
        if (next == page) return false;
        EditorScreenState.setHelpPage(next);
        return true;
    }
}
