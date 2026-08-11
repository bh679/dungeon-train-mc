package games.brennan.dungeontrain.client.localization.edit;

import games.brennan.dungeontrain.client.DungeonTrainLanguages;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The translation editor's list screen: every editable string for the language the player is
 * playing in, filtered down to the ones that need work.
 *
 * <p>Defaults to the AI-unreviewed filter, because that is the actual job — 17 of the 19 shipped
 * locales are wholly machine-translated, so "show me everything" would open on 2000-odd rows
 * with no indication of where to start.</p>
 *
 * <p>{@code en_us} has nothing to edit: English is the source. The screen says so rather than
 * showing an empty list, since a player who has never changed their language would otherwise
 * see a feature that looks broken.</p>
 */
public final class TranslationScreen extends Screen {

    private static final int MARGIN = 16;
    private static final int ROW_H = 20;
    private static final int GAP = 4;
    private static final int TOP = 30;
    private static final int SUBTITLE_COLOUR = 0xFFA0A0A0;
    /** Share of the width the "sent in" column takes; the strings pane keeps the rest. */
    private static final int SENT_COLUMN_PERCENT = 20;
    /** Floor so the column stays legible at a small GUI scale, where 20% is barely a word. */
    private static final int SENT_COLUMN_MIN_W = 90;

    /** Which units the list shows. Declaration order is the order the cycle button offers. */
    private enum StateFilter {
        AI_UNREVIEWED("ai_unreviewed"),
        EDITED("edited"),
        /**
         * The working queue: machine translation nobody has reviewed, minus whatever this player
         * has already fixed — so a line vanishes from the list once they have corrected it.
         */
        TODO("todo"),
        /**
         * Not a filter over the catalog at all: it swaps the screen into a two-pane view of what
         * this player has sent in, with the submissions down the right and the picked one's
         * strings on the left. It lives in this cycle because "what have I sent?" is the same
         * question as "what still needs doing?", asked from the other end.
         */
        SENT("sent"),
        ALL("all");

        final String key;

        StateFilter(String key) {
            this.key = key;
        }

        Component label() {
            return Component.translatable("gui.dungeontrain.translate.filter." + key);
        }
    }

    /** Which body the list shows. */
    private enum BodyFilter {
        ALL("all"),
        UI("ui"),
        BOOKS("books");

        final String key;

        BodyFilter(String key) {
            this.key = key;
        }

        Component label() {
            return Component.translatable("gui.dungeontrain.translate.body." + key);
        }
    }

    private final Screen parent;
    private final String locale;

    private EditBox search;
    private TranslationListWidget list;
    private TranslationSubmissionList sentList;
    private StateFilter stateFilter = StateFilter.AI_UNREVIEWED;
    private BodyFilter bodyFilter = BodyFilter.ALL;
    /** The strings of the submission currently picked in the SENT view. */
    private List<TranslationSubmissionsClient.SentUnit> sentUnits = List.of();
    /** True when the picked row is the working batch, whose strings come from disk, not the relay. */
    private boolean showingUnsubmitted;
    /** Sits under the column, and only while the working batch is what is selected in it. */
    private Button submit;

    public TranslationScreen(Screen parent, String locale) {
        super(Component.translatable("gui.dungeontrain.translate.title"));
        this.parent = parent;
        this.locale = locale == null ? "" : locale.toLowerCase(Locale.ROOT);
    }

    /**
     * True when {@code locale} is a language Dungeon Train actually ships a translation for.
     *
     * <p>Not merely "anything but {@code en_us}": Minecraft offers dozens of locales the mod has
     * no lang file for — {@code en_au}, {@code en_gb}, and the joke ones like {@code lol_us} and
     * {@code en_ud}. A player on those already sees the mod in English, because that is what
     * vanilla falls back to, so there is nothing there to fix. Pointing the editor at one would
     * invent a locale the repo does not track and produce submissions nothing could ever apply.
     * </p>
     *
     * <p>Discovered from the resource pack via {@link DungeonTrainLanguages#isTranslated} rather
     * than hardcoded, so a localization resource pack's locale counts too.</p>
     */
    public static boolean isEditable(String locale) {
        return locale != null && !locale.isBlank() && !"en_us".equalsIgnoreCase(locale)
            && DungeonTrainLanguages.isTranslated(locale);
    }

    @Override
    protected void init() {
        // Pull the latest approvals for the language on screen every time the editor opens. The
        // title screen's fetch is once per session, which would mean a translator who just had a
        // string approved kept being asked to fix it until they restarted the game.
        ApprovedTranslationsFetcher.fetchAsync(locale);
        int listTop = TOP + ROW_H * 2 + GAP * 3;
        int bottomRow = height - MARGIN - ROW_H;
        int listBottom = bottomRow - GAP;
        int contentWidth = width - MARGIN * 2;

        search = new EditBox(font, MARGIN, TOP, contentWidth, ROW_H,
            Component.translatable("gui.dungeontrain.translate.search"));
        search.setHint(Component.translatable("gui.dungeontrain.translate.search"));
        search.setMaxLength(100);
        search.setResponder(text -> refresh());
        addRenderableWidget(search);

        int filterWidth = (contentWidth - GAP) / 2;
        List<StateFilter> states = offeredStates();
        if (!states.contains(stateFilter)) {
            // A selection carried over from a session where it was unlocked. Without this the gate
            // is bypassed simply by reopening the screen.
            stateFilter = StateFilter.AI_UNREVIEWED;
        }
        addRenderableWidget(CycleButton.<StateFilter>builder(StateFilter::label)
            .withValues(states)
            .withInitialValue(stateFilter)
            .displayOnlyValue()
            .create(MARGIN, TOP + ROW_H + GAP, filterWidth, ROW_H,
                Component.translatable("gui.dungeontrain.translate.filter"),
                (button, value) -> {
                    stateFilter = value;
                    // Leaving SENT means the left pane is no longer showing the picked submission,
                    // so the column must stop claiming one — a highlighted row that drives nothing
                    // is the one way these two can lie to each other.
                    if (value != StateFilter.SENT && sentList != null) {
                        sentList.clearSelection();
                        showingUnsubmitted = false;
                        setSubmitVisible(false);
                    }
                    refresh();
                }));
        addRenderableWidget(CycleButton.<BodyFilter>builder(BodyFilter::label)
            .withValues(BodyFilter.values())
            .withInitialValue(bodyFilter)
            .displayOnlyValue()
            .create(MARGIN + filterWidth + GAP, TOP + ROW_H + GAP, filterWidth, ROW_H,
                Component.translatable("gui.dungeontrain.translate.body"),
                (button, value) -> {
                    bodyFilter = value;
                    refresh();
                }));

        // Two panes, always: strings on the left, what you have sent down a narrow right column.
        // The column used to appear only under the SENT filter, which meant a translator working
        // through the queue could not see their own history — or, more to the point, how much work
        // was sitting here unsent. The strings are what gets read and edited, so they keep the room.
        int listHeight = Math.max(ROW_H, listBottom - listTop);
        int sentWidth = Math.max(SENT_COLUMN_MIN_W, (contentWidth - GAP) * SENT_COLUMN_PERCENT / 100);
        int leftWidth = Math.max(ROW_H, contentWidth - GAP - sentWidth);

        list = new TranslationListWidget(font, MARGIN, listTop, leftWidth, listHeight,
            this::openEditor);
        addRenderableWidget(list);

        // The column gives up its last row-height to Submit, which sits directly under it: the
        // button acts on the working batch at the top of this column, so it belongs to the column
        // rather than to the screen's action row. The LEFT list keeps its full height — the strings
        // pane pays nothing for this.
        int sentX = MARGIN + leftWidth + GAP;
        int sentHeight = Math.max(ROW_H, listHeight - ROW_H - GAP);
        sentList = new TranslationSubmissionList(font, sentX, listTop,
            sentWidth, sentHeight, this::openSubmission);
        addRenderableWidget(sentList);

        submit = addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.submit"),
            b -> minecraft.setScreen(new TranslationSubmitScreen(this, locale)))
            .bounds(sentX, listTop + sentHeight + GAP, sentWidth, ROW_H).build());
        // Hidden until the working batch is the selected row — see setSubmitVisible.
        submit.visible = false;
        // Paint the working batch immediately from local state; the relay's history lands on top of
        // it when the fetch returns. The column is never empty while waiting on the network.
        onHistory(List.of());
        TranslationSubmissionsClient.fetch(this::onHistory);

        layoutBottomRow(bottomRow, contentWidth);
        refresh();
    }

    /**
     * Merge the relay's history with the local outbox, newest first, under the working batch.
     *
     * <p>The unsubmitted row is PREPENDED rather than sorted in: it has no submission time, and it
     * belongs at the top by what it is, not by when it happened. Everything below it is ordered by
     * date as before.</p>
     */
    private void onHistory(List<TranslationSubmission> fromRelay) {
        if (sentList == null) {
            return;
        }
        List<TranslationSubmission> sent = new ArrayList<>(TranslationOutbox.get().queued());
        sent.addAll(fromRelay);
        sent.sort((a, b) -> Long.compare(b.submittedAtMs(), a.submittedAtMs()));

        List<TranslationSubmission> all = new ArrayList<>();
        // No row when there is nothing unsent: an action you cannot take does not deserve a row, and
        // a permanent "0 changes" at the top is just something to read past every time.
        int unsent = TranslationOverrides.unsubmittedFor(locale).size();
        if (unsent > 0) {
            all.add(TranslationSubmission.unsubmitted(locale, unsent));
        }
        all.addAll(sent);
        sentList.setRows(all);
        setSubmitVisible(false); // setRows drops the selection, so Submit has nothing to belong to
        // Only auto-open a row when the left pane is actually showing one. On every other filter the
        // left pane belongs to the catalog, and highlighting a row there would claim otherwise.
        if (stateFilter == StateFilter.SENT && !sentList.hasSelection()) {
            sentList.selectFirst();
        }
    }

    /**
     * Submit exists only while the working batch is the selected row.
     *
     * <p>One rule in one place, because the alternative — a button that asks separately whether
     * there is anything to send — can disagree with the row it is sitting under. The row is only
     * built when there IS something to send, so selecting it is the whole condition.</p>
     */
    private void setSubmitVisible(boolean visible) {
        if (submit != null) {
            submit.visible = visible;
        }
    }

    /**
     * Show one submission's strings in the left pane. A queued submission has no relay-side units
     * yet, so it opens empty — which is the truth: the relay has not seen it.
     */
    private void openSubmission(TranslationSubmission submission) {
        sentUnits = List.of();
        // Picking a row is a request to read it, so the left pane has to be the one that shows it.
        stateFilter = StateFilter.SENT;
        showingUnsubmitted = submission.unsubmitted();
        setSubmitVisible(showingUnsubmitted);
        refresh();
        if (!submission.queued() && !submission.unsubmitted()) {
            TranslationSubmissionsClient.fetchUnits(submission.submittedAtMs(), units -> {
                sentUnits = units;
                refresh();
            });
        }
    }

    /**
     * The catalog units of the picked submission, looked up by id so they open in the normal
     * editor. A string whose key has since been removed from the mod is dropped rather than
     * faked — there is nothing left to edit.
     */
    private List<TranslationUnit> sentUnitRows(String needle) {
        if (showingUnsubmitted) {
            return unsubmittedRows(needle);
        }
        if (sentUnits.isEmpty()) {
            return List.of();
        }
        Map<String, TranslationUnit> byId = new LinkedHashMap<>();
        for (TranslationUnit unit : TranslationCatalog.forLocale(locale)) {
            byId.put(unit.id(), unit);
        }
        List<TranslationUnit> out = new ArrayList<>();
        for (TranslationSubmissionsClient.SentUnit sent : sentUnits) {
            TranslationUnit unit = byId.get(sent.unitId());
            if (unit != null && unit.matches(needle)) {
                out.add(unit);
            }
        }
        return out;
    }

    /**
     * The working batch's strings — the local edits the relay has not been told about. Read off
     * disk rather than fetched: this row is the one thing in the column that has never left the
     * machine, so it is also the only one that reads correctly with no network at all.
     */
    private List<TranslationUnit> unsubmittedRows(String needle) {
        TranslationEdits pending = TranslationOverrides.unsubmittedFor(locale);
        if (pending.isEmpty()) {
            return List.of();
        }
        List<TranslationUnit> out = new ArrayList<>();
        for (TranslationUnit unit : TranslationCatalog.forLocale(locale)) {
            if (TranslationFilters.overrideOf(unit, pending) != null && unit.matches(needle)) {
                out.add(unit);
            }
        }
        return out;
    }

    /**
     * The state filters this player is offered.
     *
     * <p>Browsing every string unfiltered turns the editor into a way to read the game's text out
     * of context, so it is not offered to everyone — it unlocks once the player has had a
     * translation accepted, which is the cheapest honest proof they are here to translate. Fails
     * closed: no verdict, no unlock.</p>
     */
    private static List<StateFilter> offeredStates() {
        List<StateFilter> out = new ArrayList<>(
            List.of(StateFilter.AI_UNREVIEWED, StateFilter.EDITED, StateFilter.TODO,
                StateFilter.SENT));
        if (TranslationContributor.hasApprovedTranslation()) {
            out.add(StateFilter.ALL);
        }
        return out;
    }

    /**
     * The action row along the bottom. Split out because every entry here is one feature's
     * doorway, and the row grows as those land.
     */
    private void layoutBottomRow(int y, int contentWidth) {
        // Submit is not here: it belongs to the working batch and lives under the column that row
        // heads (see init). What is left is the screen's own three doorways.
        int buttons = 3;
        int buttonWidth = (contentWidth - GAP * (buttons - 1)) / buttons;
        int x = MARGIN;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.files"),
            b -> minecraft.setScreen(new TranslationFilesScreen(this, locale)))
            .bounds(x, y, buttonWidth, ROW_H).build());
        x += buttonWidth + GAP;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.revert_all"), b -> revertAll())
            .bounds(x, y, buttonWidth, ROW_H).build());
        x += buttonWidth + GAP;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(x, y, buttonWidth, ROW_H).build());
    }

    /**
     * Re-read the approved layer and repaint — called when a pool fetch lands while this screen is
     * open, so a string approved a moment ago on the review page leaves the queue without the
     * translator having to close and reopen the editor.
     */
    void onApprovedFetched(String fetchedLocale) {
        if (locale.equals(fetchedLocale)) {
            refresh();
        }
    }

    /** Rebuild the visible rows from the catalog, the current filters and the search text. */
    private void refresh() {
        if (list == null) {
            return;
        }
        list.setEdits(TranslationOverrides.mergedFor(locale));
        list.setApproved(TranslationOverrides.approvedFor(locale));
        list.setUnits(visibleUnits());
    }

    private List<TranslationUnit> visibleUnits() {
        if (!isEditable(locale)) {
            return List.of();
        }
        String needle = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        if (stateFilter == StateFilter.SENT) {
            return sentUnitRows(needle);
        }
        TranslationEdits edits = TranslationOverrides.mergedFor(locale);
        // The approved layer on its own, not folded into `edits`: "needs a human" turns on whether
        // somebody ELSE has reviewed this string, which this player's own pending edit does not make
        // true. See TranslationFilters#needsHuman.
        TranslationEdits approved = TranslationOverrides.approvedFor(locale);
        List<TranslationUnit> out = new ArrayList<>();
        for (TranslationUnit unit : TranslationCatalog.forLocale(locale)) {
            if (!matchesBody(unit) || !matchesState(unit, edits, approved) || !unit.matches(needle)) {
                continue;
            }
            out.add(unit);
        }
        return out;
    }

    private boolean matchesBody(TranslationUnit unit) {
        return switch (bodyFilter) {
            case ALL -> true;
            case UI -> unit.type() == TranslationUnit.Type.LANG;
            case BOOKS -> unit.type() == TranslationUnit.Type.BOOK;
        };
    }

    private boolean matchesState(TranslationUnit unit, TranslationEdits edits,
                                 TranslationEdits approved) {
        return switch (stateFilter) {
            case ALL -> true;
            case TODO -> TranslationFilters.needsHuman(unit, approved) && overrideOf(unit, edits) == null;
            case SENT -> true; // handled in visibleUnits — the rows come from the relay, not the catalog
            case AI_UNREVIEWED -> TranslationFilters.needsHuman(unit, approved);
            case EDITED -> overrideOf(unit, edits) != null;
        };
    }

    static String overrideOf(TranslationUnit unit, TranslationEdits edits) {
        return TranslationFilters.overrideOf(unit, edits);
    }

    private void openEditor(TranslationUnit unit) {
        minecraft.setScreen(new TranslationEditScreen(this, locale, unit));
    }

    /** Drop every local override for this locale, after the player confirms. */
    private void revertAll() {
        TranslationEdits local = TranslationOverrides.localFor(locale);
        if (local.isEmpty()) {
            return;
        }
        minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(
            confirmed -> {
                if (confirmed) {
                    TranslationOverrides.replaceLocalFor(locale, TranslationEdits.empty(locale));
                }
                minecraft.setScreen(this);
            },
            Component.translatable("gui.dungeontrain.translate.revert_all.confirm"),
            Component.translatable("gui.dungeontrain.translate.revert_all.detail", local.size())));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        g.drawCenteredString(font, subtitle(), width / 2, 8 + font.lineHeight + 2, SUBTITLE_COLOUR);
    }

    private Component subtitle() {
        if (!isEditable(locale)) {
            return Component.translatable("gui.dungeontrain.translate.source_locale");
        }
        // Editing a language the client is not rendering (the dev-build case) cannot show its
        // edits applied, so say so rather than leaving the tester waiting for text that will
        // never change.
        if (!TranslationOverrides.isLive(locale)) {
            // Literal, not a lang key: this can only be reached on a dev build, and a diagnostic
            // no player can see does not belong in a file all 19 locales have to mirror.
            return Component.literal(String.format(
                "%s (dev) — %s shown, %s edited by you. The game is not displaying this language, "
                    + "so edits won't appear on screen.",
                locale, list == null ? 0 : list.rowCount(),
                TranslationOverrides.localFor(locale).size()));
        }
        return Component.translatable("gui.dungeontrain.translate.subtitle",
            locale, list == null ? 0 : list.rowCount(),
            TranslationOverrides.localFor(locale).size());
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** Called by the edit screen on the way back so a saved edit shows in the list at once. */
    void onEditsChanged() {
        refresh();
    }
}
