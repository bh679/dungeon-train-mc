package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
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
    private static final int REVIEW_GAP = 2;

    /**
     * Where the page's buttons were drawn, for the pane's hit test — each null when not on screen. The
     * verdict row (Accept / Feedback / Decline) is the developer's alone; Edit answers is the owner's
     * or the developer's.
     */
    record Buttons(InventoryEditorLayout.Rect edit, InventoryEditorLayout.Rect accept,
                   InventoryEditorLayout.Rect feedback, InventoryEditorLayout.Rect decline) {
        static final Buttons NONE = new Buttons(null, null, null, null);
    }

    /** The three verdicts a reviewer can press, in the order the row shows them. */
    private record Verdict(String review, String labelKey, int colour) {}

    private static final List<Verdict> VERDICTS = List.of(
            new Verdict(BuilderReviewState.ACCEPTED, "gui.dungeontrain.builder.profile.review.accept_button",
                    BuilderReviewState.BORDER_ACCEPTED),
            new Verdict(BuilderReviewState.FEEDBACK, "gui.dungeontrain.builder.profile.review.feedback_button",
                    BuilderReviewState.BORDER_FEEDBACK),
            new Verdict(BuilderReviewState.DECLINED, "gui.dungeontrain.builder.profile.review.decline_button",
                    BuilderReviewState.BORDER_DECLINED));

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
        return sections(note, hints, "");
    }

    /**
     * As {@link #sections(SubmitNote, SubmitHints.Hints)}, with the reviewer's comment as a last section
     * when there is one — what the author reads back, under their own answers, and what the reviewer
     * sees they said last time.
     */
    static List<Section> sections(SubmitNote note, SubmitHints.Hints hints, String reviewComment) {
        SubmitNote n = note == null ? SubmitNote.EMPTY : note;
        SubmitHints.Hints h = hints == null ? SubmitHints.Hints.NONE : hints;
        List<Section> out = new ArrayList<>(4);
        if (h.hasRedstone() || !n.redstone().isBlank()) {
            out.add(new Section("gui.dungeontrain.builder.profile.note.redstone.label", n.redstone().strip()));
        }
        if (h.hasLoot() || !n.loot().isBlank()) {
            out.add(new Section("gui.dungeontrain.builder.profile.note.loot.label", n.loot().strip()));
        }
        out.add(new Section("gui.dungeontrain.builder.profile.note.notes.label", n.notes().strip()));
        if (reviewComment != null && !reviewComment.isBlank()) {
            out.add(new Section("gui.dungeontrain.builder.profile.review.comment_label", reviewComment.strip()));
        }
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
        return draw(g, font, r, note, hints, canEdit, false, BuilderReviewState.NONE, "", mouseX, mouseY).edit();
    }

    /**
     * As {@link #draw(GuiGraphics, Font, InventoryEditorLayout.Rect, SubmitNote, SubmitHints.Hints, boolean,
     * int, int)}, plus the reviewer's side: the comment already on the build as a last section, and — when
     * {@code canReview} — a row of Accept / Feedback / Decline above the Edit button, the current verdict
     * drawn pressed in its colour.
     */
    static Buttons draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect r, SubmitNote note,
                        SubmitHints.Hints hints, boolean canEdit, boolean canReview, String review,
                        String reviewComment, int mouseX, int mouseY) {
        int width = Math.max(10, r.w() - 4);
        InventoryEditorLayout.Rect button = canEdit
            ? new InventoryEditorLayout.Rect(r.x() + 2, r.bottom() - BUTTON_H - 1, width, BUTTON_H)
            : null;
        int rowBottom = button == null ? r.bottom() - 1 : button.y() - REVIEW_GAP;
        InventoryEditorLayout.Rect[] verdicts = canReview ? verdictRects(r.x() + 2, rowBottom - BUTTON_H, width) : null;
        int bottom = verdicts != null ? verdicts[0].y() - 2 : button == null ? r.bottom() : button.y() - 2;
        int x = r.x() + 2;
        int y = r.y() + 2;
        g.drawString(font, font.plainSubstrByWidth(
                EditorScreenLang.text(EditorScreenLang.SUBMISSION_PAGE_HEADER), width), x, y,
                TemplateDataSheet.LABEL, false);
        y += LINE_H + SECTION_GAP;
        questions:
        for (Section section : sections(note, hints, reviewComment)) {
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
            drawButton(g, font, button, Component.translatable("gui.dungeontrain.builder.profile.note.edit_button").getString(),
                    0, mouseX, mouseY);
        }
        if (verdicts == null) return new Buttons(button, null, null, null);
        String current = BuilderReviewState.of(review);
        for (int i = 0; i < VERDICTS.size(); i++) {
            Verdict v = VERDICTS.get(i);
            drawButton(g, font, verdicts[i], Component.translatable(v.labelKey()).getString(),
                    current.equals(v.review()) ? v.colour() : 0, mouseX, mouseY);
        }
        return new Buttons(button, verdicts[0], verdicts[1], verdicts[2]);
    }

    /** Three equal cells across {@code width}, the gaps between them taken out of the last two. */
    private static InventoryEditorLayout.Rect[] verdictRects(int x, int y, int width) {
        int n = VERDICTS.size();
        int cell = Math.max(10, (width - REVIEW_GAP * (n - 1)) / n);
        InventoryEditorLayout.Rect[] out = new InventoryEditorLayout.Rect[n];
        for (int i = 0; i < n; i++) {
            int left = x + i * (cell + REVIEW_GAP);
            int w = i == n - 1 ? Math.max(10, x + width - left) : cell;
            out[i] = new InventoryEditorLayout.Rect(left, y, w, BUTTON_H);
        }
        return out;
    }

    /**
     * One cell button: hover-lit, its label centred and cut to fit. A non-zero {@code ring} outlines it
     * in that colour — the verdict already on the build, so the reviewer sees what they said last time.
     */
    private static void drawButton(GuiGraphics g, Font font, InventoryEditorLayout.Rect b, String label, int ring,
                                   int mouseX, int mouseY) {
        boolean hov = b.contains(mouseX, mouseY);
        g.fill(b.x(), b.y(), b.right(), b.bottom(), hov ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
        if (ring != 0) g.renderOutline(b.x(), b.y(), b.w(), b.h(), ring);
        String shown = font.plainSubstrByWidth(label, b.w() - 4);
        g.drawString(font, shown, b.x() + (b.w() - Math.min(font.width(shown), b.w() - 4)) / 2,
                b.y() + (BUTTON_H - font.lineHeight) / 2 + 1, hov ? MenuRowPainter.TEXT_ON_HOVER : TEXT, false);
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
