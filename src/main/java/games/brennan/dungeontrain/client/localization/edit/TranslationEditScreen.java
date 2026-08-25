package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Edits one translation unit: the English above, the translation below, and nothing else.
 *
 * <p>Saving applies immediately — {@link TranslationOverrides#setLang} reinstalls the language
 * overlay before this screen closes, so a player editing a title-screen string watches it change
 * behind them. Book fields save the same way but only take effect on an integrated server, which
 * the screen says outright rather than leaving the player to wonder why the prose is unchanged.
 * </p>
 */
public final class TranslationEditScreen extends Screen {

    private static final int MARGIN = 16;
    private static final int ROW_H = 20;
    private static final int GAP = 4;
    private static final int TOP = 30;
    private static final int LABEL_COLOUR = 0xFFA0A0A0;
    private static final int SOURCE_COLOUR = 0xFFFFFFFF;
    private static final int NOTE_COLOUR = 0xFFD8A657;
    private static final int AI_COLOUR = 0xFF5B9BD5;
    /** The reviewer's reply — the explorer's own note colour, and the list widget's ● tag. */
    private static final int REPLY_COLOUR = 0xFFE8A33D;
    /** Lines of English shown before it is truncated; the longest shipped string is ~314 chars. */
    private static final int MAX_SOURCE_LINES = 6;
    /** Lines of a reviewer's reply shown before it is truncated; the relay caps one at 1000 chars. */
    private static final int MAX_REPLY_LINES = 4;

    private final TranslationScreen parent;
    private final String locale;
    private final TranslationUnit unit;

    private MultiLineEditBox editor;
    private List<FormattedCharSequence> sourceLines = List.of();
    /** The reviewer's reply about this string, wrapped; empty when nobody has written one. */
    private List<FormattedCharSequence> replyLines = List.of();
    private Component replyBy = CommonComponents.EMPTY;
    /** Flips between "good as is" and "put it back"; relabelled in place, never rebuilt. */
    private Button dismissButton;

    public TranslationEditScreen(TranslationScreen parent, String locale, TranslationUnit unit) {
        super(Component.literal(unit.label()));
        this.parent = parent;
        this.locale = locale;
        this.unit = unit;
    }

    @Override
    protected void init() {
        int contentWidth = width - MARGIN * 2;
        // The English is a styled component, not flat text: every %s in it is underlined and
        // carries a tooltip of what the game actually puts there (TranslationVariableText). A
        // translator who cannot tell a placeholder from prose moves it into a slot the real value
        // does not fit — which is the commonest way a translation breaks.
        sourceLines = font.split(unit.source().isEmpty()
            ? FormattedText.of(Component.translatable("gui.dungeontrain.translate.no_source")
                .getString())
            : TranslationVariableText.decorate(unit.id(), unit.source(), locale), contentWidth);
        if (sourceLines.size() > MAX_SOURCE_LINES) {
            sourceLines = sourceLines.subList(0, MAX_SOURCE_LINES);
        }

        // A reviewer's reply about this exact string, if there is one. It goes ABOVE the box the
        // player is about to type in, because it is the reason they are here — a rejection they
        // could not otherwise explain (see TranslationReviewNotes).
        TranslationSubmissionsClient.ReviewNote reply = TranslationReviewNotes.forUnit(unit);
        if (reply != null) {
            replyLines = font.split(FormattedText.of(reply.note()), contentWidth);
            if (replyLines.size() > MAX_REPLY_LINES) {
                replyLines = replyLines.subList(0, MAX_REPLY_LINES);
            }
            replyBy = Component.translatable("gui.dungeontrain.translate.edit.reply",
                reply.noteBy() == null || reply.noteBy().isBlank() ? "admin" : reply.noteBy());
        } else {
            replyLines = List.of();
            replyBy = CommonComponents.EMPTY;
        }

        int replyHeight = replyLines.isEmpty() ? 0
            : font.lineHeight * (replyLines.size() + 1) + GAP * 2;
        int editorTop = TOP + font.lineHeight * (sourceLines.size() + 2) + GAP * 3 + replyHeight;
        int bottomRow = height - MARGIN - ROW_H;
        int noteHeight = unit.type() == TranslationUnit.Type.BOOK
            || !TranslationOverrides.isLive(locale) ? font.lineHeight + GAP : 0;
        int editorHeight = Math.max(ROW_H * 2, bottomRow - GAP - noteHeight - editorTop);

        editor = new MultiLineEditBox(font, MARGIN, editorTop, contentWidth, editorHeight,
            Component.translatable("gui.dungeontrain.translate.edit.hint"),
            Component.translatable("gui.dungeontrain.translate.edit.label"));
        editor.setCharacterLimit(TranslationEdits.MAX_VALUE_CHARS);
        editor.setValue(currentValue());
        addRenderableWidget(editor);
        setInitialFocus(editor);

        // Four now: the fourth is the answer this screen never had for the commonest case, which
        // is a machine translation that is already correct. Without it the only way out of the
        // AI queue was to rewrite a line that needed nothing.
        int buttons = 4;
        int buttonWidth = (contentWidth - GAP * (buttons - 1)) / buttons;
        int x = MARGIN;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.edit.save"), b -> save())
            .bounds(x, bottomRow, buttonWidth, ROW_H).build());
        x += buttonWidth + GAP;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.edit.revert"), b -> revert())
            .bounds(x, bottomRow, buttonWidth, ROW_H).build());
        x += buttonWidth + GAP;
        dismissButton = addRenderableWidget(Button.builder(dismissLabel(), b -> toggleDismissed())
            .bounds(x, bottomRow, buttonWidth, ROW_H).build());
        dismissButton.setTooltip(Tooltip.create(
            Component.translatable("gui.dungeontrain.translate.edit.good_as_is.tip")));
        x += buttonWidth + GAP;
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
            .bounds(x, bottomRow, buttonWidth, ROW_H).build());
    }

    private boolean isDismissed() {
        return TranslationDismissals.isDismissed(locale, unit);
    }

    private Component dismissLabel() {
        return Component.translatable(isDismissed()
            ? "gui.dungeontrain.translate.edit.good_as_is.undo"
            : "gui.dungeontrain.translate.edit.good_as_is");
    }

    /**
     * Mark this string good as is, or put it back in the queue.
     *
     * <p>Stays on the screen rather than closing: dismissing is not a decision about the text in
     * the box, and a player who mis-clicks has to be able to undo it where they did it. The relay
     * is told only when the stored state actually changed, and only for a dismissal — un-dismissing
     * is this client's business alone.</p>
     */
    private void toggleDismissed() {
        boolean next = !isDismissed();
        if (TranslationDismissals.set(locale, unit, next) && next) {
            TranslationDismissClient.send(locale, unit);
        }
        dismissButton.setMessage(dismissLabel());
        parent.onEditsChanged();
    }

    /** What the box opens with: this player's override if any, else the shipped translation. */
    private String currentValue() {
        String override = TranslationScreen.overrideOf(unit, TranslationOverrides.mergedFor(locale));
        return override != null ? override : unit.shipped();
    }

    private void save() {
        String value = editor.getValue();
        // Saving text identical to the shipped translation is a revert, not an override: storing
        // it would put the key in the submission and in the "edited" filter for no reason.
        String toStore = value.equals(unit.shipped()) ? "" : value;
        if (unit.type() == TranslationUnit.Type.BOOK) {
            TranslationOverrides.setBookFor(locale, unit.id(), toStore);
        } else {
            TranslationOverrides.setLangFor(locale, unit.id(), toStore);
        }
        close();
    }

    private void revert() {
        if (unit.type() == TranslationUnit.Type.BOOK) {
            TranslationOverrides.setBookFor(locale, unit.id(), "");
        } else {
            TranslationOverrides.setLangFor(locale, unit.id(), "");
        }
        close();
    }

    private void close() {
        parent.onEditsChanged();
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);

        int y = TOP;
        Component heading = unit.aiUnreviewed()
            ? Component.translatable("gui.dungeontrain.translate.edit.source_ai")
            : Component.translatable("gui.dungeontrain.translate.edit.source");
        g.drawString(font, heading, MARGIN, y, unit.aiUnreviewed() ? AI_COLOUR : LABEL_COLOUR, false);
        y += font.lineHeight + GAP;
        for (FormattedCharSequence line : sourceLines) {
            g.drawString(font, line, MARGIN, y, SOURCE_COLOUR, false);
            y += font.lineHeight;
        }

        if (!replyLines.isEmpty()) {
            y += GAP;
            g.drawString(font, replyBy, MARGIN, y, REPLY_COLOUR, false);
            y += font.lineHeight;
            for (FormattedCharSequence line : replyLines) {
                g.drawString(font, line, MARGIN, y, SOURCE_COLOUR, false);
                y += font.lineHeight;
            }
        }

        Component note = null;
        if (!TranslationOverrides.isLive(locale)) {
            // Nothing on screen will change when this saves — the game is rendering another
            // language entirely.
            note = Component.literal("Dev: editing " + locale
                + " while the game runs in English — saving won't change anything on screen.");
        } else if (unit.type() == TranslationUnit.Type.BOOK) {
            note = Component.translatable("gui.dungeontrain.translate.edit.book_note");
        }
        if (note != null) {
            g.drawString(font, note, MARGIN, height - MARGIN - ROW_H - GAP - font.lineHeight,
                NOTE_COLOUR, false);
        }

        // Last, so the tooltip sits over everything: what the underlined placeholder under the
        // mouse actually holds at runtime.
        Style hovered = variableStyleAt(mouseX, mouseY);
        if (hovered != null && hovered.getHoverEvent() != null) {
            g.renderComponentHoverEffect(font, hovered, mouseX, mouseY);
        }
    }

    /** Where the English starts — the heading, then the gap {@link #render} leaves after it. */
    private int sourceTop() {
        return TOP + font.lineHeight + GAP;
    }

    /**
     * The {@link Style} under the mouse within the English lines, or null. The same shape as
     * {@code SupportScreen.styleAt} and {@code CreditsScreen}: find the line by height, reject
     * anything past the end of the drawn text, then ask the splitter which style is at that width.
     */
    private Style variableStyleAt(double mouseX, double mouseY) {
        int top = sourceTop();
        for (int i = 0; i < sourceLines.size(); i++) {
            int lineY = top + i * font.lineHeight;
            if (mouseY < lineY || mouseY >= lineY + font.lineHeight) {
                continue;
            }
            FormattedCharSequence line = sourceLines.get(i);
            if (mouseX < MARGIN || mouseX >= MARGIN + font.width(line)) {
                return null;
            }
            return font.getSplitter().componentStyleAtWidth(line, (int) (mouseX - MARGIN));
        }
        return null;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
