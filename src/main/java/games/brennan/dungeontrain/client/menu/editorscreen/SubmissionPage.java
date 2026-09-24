package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.SubmitNote;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The Submitted answers page: what a build's author told the reviewer when they submitted it — how its
 * redstone works, what its loot is, and anything else. Drawn by the detail pane (the player's own
 * templates) and the creator pane (someone's build being browsed) alike, so the answers read the same
 * wherever they are looked at.
 *
 * <p>Only the questions that were answered: an empty answer is a question the author was not asked
 * or chose to skip, and a heading over nothing says neither. A page with no answers at all is not
 * offered — the panes check {@link #hasAnswers} before counting it.</p>
 */
final class SubmissionPage {

    private static final int LINE_H = 10;
    private static final int SECTION_GAP = 4;
    private static final int TEXT = 0xFFFFFFFF;
    private static final String ELLIPSIS = "…";

    /** One answered question: its heading's lang key and what the author wrote. */
    record Section(String labelKey, String text) {}

    private SubmissionPage() {}

    /** True when there is anything to show — the test for whether the page exists at all. */
    static boolean hasAnswers(SubmitNote note) {
        return note != null && !sections(note).isEmpty();
    }

    /** The answered questions, in the order they were asked. Blank answers are left out. */
    static List<Section> sections(SubmitNote note) {
        List<Section> out = new ArrayList<>(3);
        if (note == null) return out;
        add(out, "gui.dungeontrain.builder.profile.note.redstone.label", note.redstone());
        add(out, "gui.dungeontrain.builder.profile.note.loot.label", note.loot());
        add(out, "gui.dungeontrain.builder.profile.note.notes.label", note.notes());
        return out;
    }

    private static void add(List<Section> out, String labelKey, String text) {
        if (text != null && !text.isBlank()) out.add(new Section(labelKey, text.strip()));
    }

    /**
     * Draw the page into {@code r}: a header, then each answer under its heading, wrapped. What runs
     * past the bottom is cut, and the last line that fits says so with an ellipsis.
     */
    static void draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect r, SubmitNote note) {
        int x = r.x() + 2;
        int width = Math.max(10, r.w() - 4);
        int y = r.y() + 2;
        g.drawString(font, font.plainSubstrByWidth(
                EditorScreenLang.text(EditorScreenLang.SUBMISSION_PAGE_HEADER), width), x, y,
                TemplateDataSheet.LABEL, false);
        y += LINE_H + SECTION_GAP;
        for (Section section : sections(note)) {
            if (y + LINE_H > r.bottom()) return;
            g.drawString(font, font.plainSubstrByWidth(Component.translatable(section.labelKey()).getString(),
                    width), x, y, MenuRowPainter.TEXT_HEADER, false);
            y += LINE_H;
            List<FormattedCharSequence> rows = font.split(Component.literal(section.text()), width);
            for (int i = 0; i < rows.size(); i++) {
                boolean lastFits = y + 2 * LINE_H > r.bottom();
                if (lastFits && i < rows.size() - 1) {
                    // The final line that fits: cut here and say there is more.
                    g.drawString(font, cut(font, rows.get(i), width), x, y, TEXT, false);
                    return;
                }
                if (y + LINE_H > r.bottom()) return;
                g.drawString(font, rows.get(i), x, y, TEXT, false);
                y += LINE_H;
            }
            y += SECTION_GAP;
        }
    }

    /** A wrapped row shortened to leave room for an ellipsis after it. */
    private static String cut(Font font, FormattedCharSequence row, int width) {
        StringBuilder sb = new StringBuilder();
        row.accept((index, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });
        return font.plainSubstrByWidth(sb.toString(), width - font.width(ELLIPSIS)) + ELLIPSIS;
    }
}
