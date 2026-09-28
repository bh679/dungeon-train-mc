package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.SubmitNote;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.editor.SubmitHints;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The Submitted answers page: the questions Submit for Review asks of a build — how its redstone
 * works, what its loot is, anything else — and what its author answered. Drawn by the detail pane (the
 * player's own templates) and the creator pane (someone's build being browsed) alike, so the answers
 * read the same wherever they are looked at.
 *
 * <p>The questions are there whether or not they have been answered: the general one always, Redstone
 * and Loot when the build earns them ({@link SubmitHints}), and any question that already has an answer
 * even if the build no longer earns it — an answer is never hidden. An unanswered one says so. Whoever
 * may change the answers (the owner, or the developer) gets an Edit answers button at the foot.</p>
 */
final class SubmissionPage {

    private static final int LINE_H = 10;
    private static final int SECTION_GAP = 4;
    private static final int BUTTON_H = 14;
    private static final int TEXT = 0xFFFFFFFF;
    private static final String ELLIPSIS = "…";

    /** One question: its heading's lang key, and the answer — empty when there is none yet. */
    record Section(String labelKey, String text) {
        boolean answered() {
            return !text.isEmpty();
        }
    }

    private SubmissionPage() {}

    /** True when any question has an answer. */
    static boolean hasAnswers(SubmitNote note) {
        if (note == null) return false;
        return !note.redstone().isBlank() || !note.loot().isBlank() || !note.notes().isBlank();
    }

    /** The questions this build asks, in the order they are asked, each with its answer or none. */
    static List<Section> sections(SubmitNote note, SubmitHints.Hints hints) {
        SubmitNote n = note == null ? SubmitNote.EMPTY : note;
        SubmitHints.Hints h = hints == null ? SubmitHints.Hints.NONE : hints;
        List<Section> out = new ArrayList<>(3);
        if (h.hasRedstone() || !n.redstone().isBlank()) {
            out.add(new Section("gui.dungeontrain.builder.profile.note.redstone.label", n.redstone().strip()));
        }
        if (h.hasLoot() || !n.loot().isBlank()) {
            out.add(new Section("gui.dungeontrain.builder.profile.note.loot.label", n.loot().strip()));
        }
        out.add(new Section("gui.dungeontrain.builder.profile.note.notes.label", n.notes().strip()));
        return out;
    }

    /**
     * Draw the page into {@code r}: a header, each question with its answer (wrapped) or a dim "not
     * answered yet", and — when {@code canEdit} — an Edit answers button along the bottom. What runs past
     * the space above the button is cut, the last line that fits ending in an ellipsis.
     *
     * @return where the Edit button was drawn, for the pane's hit test; null when there is none
     */
    static InventoryEditorLayout.Rect draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect r, SubmitNote note,
                                           SubmitHints.Hints hints, boolean canEdit, int mouseX, int mouseY) {
        InventoryEditorLayout.Rect button = canEdit
            ? new InventoryEditorLayout.Rect(r.x() + 2, r.bottom() - BUTTON_H - 1, Math.max(10, r.w() - 4), BUTTON_H)
            : null;
        int bottom = button == null ? r.bottom() : button.y() - 2;
        int x = r.x() + 2;
        int width = Math.max(10, r.w() - 4);
        int y = r.y() + 2;
        g.drawString(font, font.plainSubstrByWidth(
                EditorScreenLang.text(EditorScreenLang.SUBMISSION_PAGE_HEADER), width), x, y,
                TemplateDataSheet.LABEL, false);
        y += LINE_H + SECTION_GAP;
        questions:
        for (Section section : sections(note, hints)) {
            if (y + LINE_H > bottom) break;
            g.drawString(font, font.plainSubstrByWidth(Component.translatable(section.labelKey()).getString(),
                    width), x, y, MenuRowPainter.TEXT_HEADER, false);
            y += LINE_H;
            if (!section.answered()) {
                if (y + LINE_H > bottom) break;
                g.drawString(font, font.plainSubstrByWidth(Component.translatable(
                        "gui.dungeontrain.builder.profile.note.not_answered").getString(), width),
                    x, y, EditorDetailPane.DIM_TEXT, false);
                y += LINE_H + SECTION_GAP;
                continue;
            }
            List<FormattedCharSequence> rows = font.split(Component.literal(section.text()), width);
            for (int i = 0; i < rows.size(); i++) {
                if (y + 2 * LINE_H > bottom && i < rows.size() - 1) {
                    // The final line that fits: cut here and say there is more.
                    g.drawString(font, cut(font, rows.get(i), width), x, y, TEXT, false);
                    break questions;
                }
                if (y + LINE_H > bottom) break questions;
                g.drawString(font, rows.get(i), x, y, TEXT, false);
                y += LINE_H;
            }
            y += SECTION_GAP;
        }
        if (button != null) {
            boolean hov = button.contains(mouseX, mouseY);
            g.fill(button.x(), button.y(), button.right(), button.bottom(),
                hov ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
            String label = Component.translatable("gui.dungeontrain.builder.profile.note.edit_button").getString();
            g.drawString(font, font.plainSubstrByWidth(label, button.w() - 4),
                button.x() + (button.w() - Math.min(font.width(label), button.w() - 4)) / 2,
                button.y() + (BUTTON_H - font.lineHeight) / 2 + 1,
                hov ? MenuRowPainter.TEXT_ON_HOVER : TEXT, false);
        }
        return button;
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
