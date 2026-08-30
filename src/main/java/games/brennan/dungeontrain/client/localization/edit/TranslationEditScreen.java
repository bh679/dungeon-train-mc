package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.Util;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.Mth;

import java.net.URI;
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
    private static final int NOTE_COLOUR = 0xFFD8A657;
    /** The soft red this package already uses for errors (TranslationScreen.STATUS_ERROR). */
    private static final int ERROR_COLOUR = 0xFFDD7F7F;
    private static final int AI_COLOUR = 0xFF5B9BD5;
    /** The gap between the English and the edit box, which is also the divider's grab area. */
    private static final int SPLITTER_H = GAP * 3;
    /** The set readout's colour — the same blue the AI badge and the AI heading use. */
    private static final int GROUP_COLOUR = AI_COLOUR;

    /**
     * The height the translator last dragged the English to, or null while they never have.
     *
     * <p>Static on purpose, and only for as long as the game is running: having to re-drag the
     * divider on every string would make dragging it not worth doing. Re-clamped on every
     * {@code init}, so a resized window or a shorter string cannot strand it out of range.</p>
     */
    private static Integer preferredPaneHeight;

    private final TranslationScreen parent;
    private final String locale;
    private final TranslationUnit unit;
    /**
     * The other variations of this same string, in catalog order — empty when there are none.
     *
     * <p>Handed in rather than derived here: {@link TranslationScreen} already holds the catalog's
     * group index, and a second derivation is a second answer waiting to disagree with the first.
     * </p>
     */
    private final List<TranslationUnit> groupMembers;

    private MultiLineEditBox editor;
    /** The English, and any reviewer's reply about it — the whole of it, scrolled if need be. */
    private TranslationSourcePane sourcePane;
    /** Null when the English already fits and there is nothing to trade between the two. */
    private TranslationPaneSplitter splitter;
    /** Flips between "good as is" and "put it back"; relabelled in place, never rebuilt. */
    private Button dismissButton;
    /** The set readout drawn in the strip, or null when this string is not one of a set. */
    private Component setReadout;
    /** The way on to the next variation; relabelled in place, like the dismiss button beside it. */
    private Button nextButton;

    /** Why the typed text will not render, or null. Recomputed on every keystroke. */
    private TranslationFormatCheck.Problem formatProblem;
    private Button saveButton;

    public TranslationEditScreen(TranslationScreen parent, String locale, TranslationUnit unit,
                                 List<TranslationUnit> groupMembers) {
        super(Component.literal(unit.label()));
        this.parent = parent;
        this.locale = locale;
        this.unit = unit;
        this.groupMembers = groupMembers == null ? List.of() : groupMembers;
    }

    /** Where the source pane starts — {@link #TOP}, or a row lower when the set strip is there. */
    private int contentTop() {
        return inSet() ? TOP + ROW_H + GAP : TOP;
    }

    private boolean inSet() {
        return groupMembers.size() > 1;
    }

    @Override
    protected void init() {
        int contentWidth = width - MARGIN * 2;
        int contentTop = contentTop();
        addSetStrip(contentWidth);

        // A reviewer's reply about this exact string, if there is one. It goes ABOVE the box the
        // player is about to type in, because it is the reason they are here — a rejection they
        // could not otherwise explain (see TranslationReviewNotes).
        TranslationSubmissionsClient.ReviewNote reply = TranslationReviewNotes.forUnit(unit);
        Component replyBy = reply == null ? CommonComponents.EMPTY
            : Component.translatable("gui.dungeontrain.translate.edit.reply",
                reply.noteBy() == null || reply.noteBy().isBlank() ? "admin" : reply.noteBy());
        Component heading = unit.aiUnreviewed()
            ? Component.translatable("gui.dungeontrain.translate.edit.source_ai")
            : Component.translatable("gui.dungeontrain.translate.edit.source");
        sourcePane = TranslationSourcePane.wrap(font, contentWidth, heading,
            unit.aiUnreviewed() ? AI_COLOUR : LABEL_COLOUR,
            // Styled, not flat: every %s in the English is underlined and hovers what the game
            // actually puts there. A translator who cannot tell a placeholder from prose moves it
            // into a slot the real value does not fit — the commonest way a translation breaks.
            unit.source().isEmpty()
                ? FormattedText.of(
                    Component.translatable("gui.dungeontrain.translate.no_source").getString())
                : TranslationVariableText.decorate(unit.id(), unit.source(), locale),
            replyBy, reply == null ? "" : reply.note());

        int bottomRow = height - MARGIN - ROW_H;
        int noteHeight = noteHeight();
        // What is left for the English once the box the translator types in keeps its two rows.
        int available = bottomRow - GAP - noteHeight - ROW_H * 2 - SPLITTER_H - contentTop;
        int content = sourcePane.contentHeight();
        int paneHeight = preferredPaneHeight == null
            ? TranslationSourceLayout.viewportHeight(content, height, available, font.lineHeight)
            : TranslationSourceLayout.draggedHeight(
                preferredPaneHeight, content, available, font.lineHeight);
        sourcePane.place(MARGIN, contentTop, paneHeight);
        addRenderableWidget(sourcePane);
        addSourceLink(contentWidth);

        // Only where there is something to trade. A short string whose English already fits has
        // no room to give the edit box, so it gets a plain gap rather than a handle that does
        // nothing when pulled.
        if (TranslationSourceLayout.isResizable(content, available, font.lineHeight)) {
            splitter = addRenderableWidget(new TranslationPaneSplitter(
                MARGIN, contentTop + paneHeight, contentWidth, SPLITTER_H, this::onSplitterDragged));
            splitter.setTooltip(Tooltip.create(
                Component.translatable("gui.dungeontrain.translate.edit.resize.tip")));
        } else {
            splitter = null;
        }

        int editorTop = contentTop + paneHeight + SPLITTER_H;
        int editorHeight = Math.max(ROW_H * 2, bottomRow - GAP - noteHeight - editorTop);

        editor = new MultiLineEditBox(font, MARGIN, editorTop, contentWidth, editorHeight,
            Component.translatable("gui.dungeontrain.translate.edit.hint"),
            Component.translatable("gui.dungeontrain.translate.edit.label"));
        editor.setCharacterLimit(TranslationEdits.MAX_VALUE_CHARS);
        editor.setValue(currentValue());
        editor.setValueListener(value -> revalidate());
        addRenderableWidget(editor);
        setInitialFocus(editor);

        // Four now: the fourth is the answer this screen never had for the commonest case, which
        // is a machine translation that is already correct. Without it the only way out of the
        // AI queue was to rewrite a line that needed nothing.
        int buttons = 4;
        int buttonWidth = (contentWidth - GAP * (buttons - 1)) / buttons;
        int x = MARGIN;
        saveButton = addRenderableWidget(Button.builder(
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

        // Once up front, not only on edit: an override stored before this check existed — or one
        // pulled down from the relay — can already be broken when the screen opens.
        revalidate();
    }

    /**
     * The strip above the English: which variation of the set this is, and the way on to the next
     * one that still wants a human.
     *
     * <p>Above rather than in the button row, because it is context for what is being read, not a
     * fifth thing to do to it — and the row below is already four buttons wide.</p>
     */
    private void addSetStrip(int contentWidth) {
        setReadout = null;
        if (!inSet()) {
            return;
        }
        Component label = Component.translatable("gui.dungeontrain.translate.group.next");
        int buttonWidth = Math.min(contentWidth / 3, font.width(label) + ROW_H);
        nextButton = addRenderableWidget(Button.builder(label, b -> goToNext())
            .bounds(MARGIN + contentWidth - buttonWidth, TOP, buttonWidth, ROW_H).build());
        refreshSetStrip();
    }

    /**
     * Recompute what the strip says. Called on layout and again whenever this string's own standing
     * changes under it — marking it good as is takes it off the set's tally, and a strip still
     * counting it would be reporting work that is no longer there.
     */
    private void refreshSetStrip() {
        if (!inSet()) {
            return;
        }
        int position = TranslationGroups.indexIn(groupMembers, unit) + 1;
        int waiting = TranslationGroups.needingReview(groupMembers,
            TranslationOverrides.approvedFor(locale), this::isDismissed);
        setReadout = waiting == 0
            ? Component.translatable("gui.dungeontrain.translate.group.position",
                position, groupMembers.size())
            : Component.translatable("gui.dungeontrain.translate.group.position.needs",
                position, groupMembers.size(), waiting);

        // Everything else in the set has been seen to, so there is nowhere for this to go. Greyed
        // rather than removed: a control that vanishes as you work reads as a bug.
        TranslationUnit target = nextInSet();
        nextButton.active = target != null;
        nextButton.setTooltip(Tooltip.create(target == null
            ? Component.translatable("gui.dungeontrain.translate.group.next.none")
            : Component.translatable("gui.dungeontrain.translate.group.next.tip", waiting)));
    }

    private boolean isDismissed(TranslationUnit other) {
        return TranslationDismissals.isDismissed(locale, other);
    }

    private TranslationUnit nextInSet() {
        return TranslationGroups.nextNeedingReview(groupMembers, unit,
            TranslationOverrides.approvedFor(locale), this::isDismissed);
    }

    /**
     * Move on to the next variation that still needs a human, asking first if there is typing in
     * the box that would be lost.
     *
     * <p>Asking rather than choosing for them: saving silently would put text into a submission the
     * translator never decided to send, and discarding silently would throw away the work they came
     * here to do. Neither is ours to pick.</p>
     */
    private void goToNext() {
        TranslationUnit target = nextInSet();
        if (target == null) {
            return;
        }
        if (editor.getValue().equals(currentValue())) {
            openInSet(target);
            return;
        }
        minecraft.setScreen(new TranslationUnsavedScreen(choice -> {
            switch (choice) {
                case SAVE -> {
                    if (formatProblem != null) {
                        minecraft.setScreen(this);   // cannot save this; stay and show why
                        return;
                    }
                    store(editor.getValue());
                    openInSet(target);
                }
                case DISCARD -> openInSet(target);
                case STAY -> minecraft.setScreen(this);
            }
        }));
    }

    /**
     * Open another member of the same set, keeping the list behind us as it was.
     *
     * <p>The list is told what changed first: the row we are leaving may have just dropped out of
     * the queue, and it should say so by the time the translator returns to it.</p>
     */
    private void openInSet(TranslationUnit target) {
        parent.onEditsChanged();
        minecraft.setScreen(new TranslationEditScreen(parent, locale, target, groupMembers));
    }

    /**
     * The link to where this string lives in the repo, at the right of the heading row.
     *
     * <p>That row is the one part of the pane that does not scroll, so the link stays put while
     * long English scrolls under it. Only Dungeon Train's own strings get one — see
     * {@link TranslationSourceLink#available}.</p>
     */
    private void addSourceLink(int contentWidth) {
        if (!TranslationSourceLink.available(unit)) {
            sourcePane.reserveHeading(0);
            return;
        }
        Component label = Component.translatable("gui.dungeontrain.translate.edit.source_link");
        // Never let the link eat more than half the row: a long translated label would otherwise
        // squeeze the heading out of existence on a narrow window.
        int linkWidth = Mth.clamp(font.width(label), 0, contentWidth / 2);
        PlainTextButton link = new PlainTextButton(
            MARGIN + contentWidth - linkWidth, contentTop(), linkWidth, font.lineHeight,
            label, b -> openSource(), font);
        link.setTooltip(Tooltip.create(
            Component.translatable("gui.dungeontrain.translate.edit.source_link.tip")));
        addRenderableWidget(link);
        sourcePane.reserveHeading(linkWidth + GAP);
    }

    /** Standard external-link confirm, then back to this screen with the edit box as it was. */
    private void openSource() {
        String url = TranslationSourceLink.urlFor(unit);
        minecraft.setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) {
                Util.getPlatform().openUri(URI.create(url));
            }
            minecraft.setScreen(this);
        }, url, true));
    }

    /**
     * Give the keyboard back after a drag or a scroll.
     *
     * <p>Clicking the divider or the source scrollbar makes it the focused child, which silently
     * leaves the translator typing into nothing. Handing focus back has to wait for the release —
     * moving it mid-drag would end the drag it is meant to be following.</p>
     */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseReleased(mouseX, mouseY, button);
        if (getFocused() == splitter || getFocused() == sourcePane) {
            setFocused(editor);
        }
        return handled;
    }

    /**
     * Follow the divider. The widgets are moved where they stand rather than rebuilt: a rebuild
     * would throw away what the translator has typed and where their cursor is, which is a high
     * price for dragging a line.
     */
    private void onSplitterDragged(double mouseY) {
        int bottomRow = height - MARGIN - ROW_H;
        int noteHeight = noteHeight();
        int contentTop = contentTop();
        int available = bottomRow - GAP - noteHeight - ROW_H * 2 - SPLITTER_H - contentTop;
        int paneHeight = TranslationSourceLayout.draggedHeight((int) Math.round(mouseY) - contentTop,
            sourcePane.contentHeight(), available, font.lineHeight);
        preferredPaneHeight = paneHeight;

        sourcePane.place(MARGIN, contentTop, paneHeight);
        splitter.setY(contentTop + paneHeight);
        int editorTop = contentTop + paneHeight + SPLITTER_H;
        editor.setY(editorTop);
        editor.setHeight(Math.max(ROW_H * 2, bottomRow - GAP - noteHeight - editorTop));
    }

    /**
     * The one-line warning row under the edit box.
     *
     * <p>Always reserved, including for an ordinary lang string in a live locale that has nothing
     * to say today. A format error appears and disappears as the translator types, and a row that
     * only exists while the text is wrong would resize the edit box under their cursor — so the
     * space is held whether or not it is used.</p>
     */
    private int noteHeight() {
        return font.lineHeight + GAP;
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
        refreshSetStrip();
        parent.onEditsChanged();
    }

    /** What the box opens with: this player's override if any, else the shipped translation. */
    private String currentValue() {
        String override = TranslationScreen.overrideOf(unit, TranslationOverrides.mergedFor(locale));
        return override != null ? override : unit.shipped();
    }

    /**
     * Re-check the typed text and grey out Save when it will not render.
     *
     * <p>Greyed rather than silently accepted: the translator finds out now, with the text in
     * front of them, instead of after the relay has approved it and the weekly import has set it
     * aside — which is what happened to a Russian translation of {@code clear_backups.confirm.both}
     * that had lost one of its two {@code %s}.</p>
     */
    private void revalidate() {
        formatProblem = problemWith(editor.getValue());
        if (saveButton == null) {
            return;
        }
        saveButton.active = formatProblem == null;
        saveButton.setTooltip(formatProblem == null ? null
            : Tooltip.create(formatMessage(formatProblem)));
    }

    /**
     * What is wrong with {@code typed}, or null.
     *
     * <p>Books are exempt: their prose is rendered with {@code Component.literal}
     * ({@code narrative/BookFactory}), never reaches the format parser, and a {@code %} in a story
     * is just a percent sign.</p>
     */
    private TranslationFormatCheck.Problem problemWith(String typed) {
        if (unit.type() == TranslationUnit.Type.BOOK) {
            return null;
        }
        return TranslationFormatCheck.checkTyped(unit.source(), typed);
    }

    private Component formatMessage(TranslationFormatCheck.Problem problem) {
        return Component.translatable(problem.messageKey(), problem.tokens());
    }

    private void save() {
        if (formatProblem != null) {
            return;     // the button is disabled; this guards the keyboard path to it
        }
        store(editor.getValue());
        close();
    }

    private void revert() {
        store(unit.shipped());
        close();
    }

    /**
     * Write {@code value} to this unit's override layer — the one place Save, Use Shipped and
     * Save-and-continue all go through, so they cannot drift apart.
     *
     * <p>Storing text identical to the shipped translation is a revert, not an override: keeping it
     * would put the key in the submission and in the "edited" filter for no reason.</p>
     */
    private void store(String value) {
        String toStore = value.equals(unit.shipped()) ? "" : value;
        if (unit.type() == TranslationUnit.Type.BOOK) {
            TranslationOverrides.setBookFor(locale, unit.id(), toStore);
        } else {
            TranslationOverrides.setLangFor(locale, unit.id(), toStore);
        }
    }

    private void close() {
        parent.onEditsChanged();
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        if (setReadout != null) {
            g.drawString(font, setReadout, MARGIN, TOP + (ROW_H - font.lineHeight) / 2,
                GROUP_COLOUR, false);
        }

        Component note = null;
        int noteColour = NOTE_COLOUR;
        if (formatProblem != null) {
            // Takes the line: a translation that will not render is more urgent than either of
            // the informational notes, and both cannot be shown in one reserved row.
            note = formatMessage(formatProblem);
            noteColour = ERROR_COLOUR;
        } else if (!TranslationOverrides.isLive(locale)) {
            // Nothing on screen will change when this saves — the game is rendering another
            // language entirely.
            note = Component.literal("Dev: editing " + locale
                + " while the game runs in English — saving won't change anything on screen.");
        } else if (unit.type() == TranslationUnit.Type.BOOK) {
            note = Component.translatable("gui.dungeontrain.translate.edit.book_note");
        }
        if (note != null) {
            g.drawString(font, note, MARGIN, height - MARGIN - ROW_H - GAP - font.lineHeight,
                noteColour, false);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
