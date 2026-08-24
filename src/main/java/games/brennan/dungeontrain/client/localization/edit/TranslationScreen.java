package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.DungeonTrainLanguages;
import games.brennan.dungeontrain.client.menu.ColorTintedButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

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

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    private static final int MARGIN = 16;
    private static final int ROW_H = 20;
    private static final int GAP = 4;
    private static final int TOP = 30;
    private static final int SUBTITLE_COLOUR = 0xFFA0A0A0;
    /** The export/import result line — the same green/red the Files screen reported in. */
    private static final int STATUS_OK_COLOUR = 0xFF7FDD7F;
    private static final int STATUS_ERROR_COLOUR = 0xFFDD7F7F;
    /** Share of the width the "sent in" column takes; the strings pane keeps the rest. */
    private static final int SENT_COLUMN_PERCENT = 20;
    /** Floor so the column stays legible at a small GUI scale, where 20% is barely a word. */
    private static final int SENT_COLUMN_MIN_W = 90;
    /**
     * Cap on each narrowing cycle, so they stay a control beside the search box rather than
     * competing with it. Wide enough for the longest label; the rest of the row is search.
     */
    private static final int FILTER_MAX_W = 110;

    /**
     * The row's glyphs. Search borrows vanilla's own magnifier — it is the icon Minecraft uses for
     * searching, which is as standard as this gets. The three file actions ship their own sprites,
     * because vanilla has no folder and no arrows, and items were the wrong vocabulary for them: a
     * chest is a block you place in the world, and two kinds of book do not read as a direction.
     */
    private static final ResourceLocation SEARCH_ICON =
        ResourceLocation.withDefaultNamespace("icon/search");
    private static final ResourceLocation FOLDER_ICON =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/folder");
    private static final ResourceLocation EXPORT_ICON =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/export");
    private static final ResourceLocation IMPORT_ICON =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/import");
    private static final ResourceLocation TRASH_ICON =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/trash");
    /** Vanilla's own globe — the icon Minecraft uses for this exact choice. */
    private static final ResourceLocation LANGUAGE_ICON =
        ResourceLocation.withDefaultNamespace("icon/language");
    /** The mod's one green — SupportScreen's direct-donation tint, not a second one like it. */
    private static final float[] SUBMIT_TINT = {0.30F, 0.80F, 0.35F};
    /** Authored sizes, drawn 1:1 — scaling pixel art by a fraction is what makes it look soft. */
    private static final int OWN_ICON_PX = 16;
    private static final int VANILLA_ICON_PX = 12;
    /** How long an export/import result holds the subtitle's line: ~8 seconds at 20 ticks/s. */
    private static final int STATUS_TICKS = 160;

    /**
     * Which of the UNFINISHED strings the list shows. Declaration order is the cycle order.
     *
     * <p>Every value here narrows work still to do. "What have I sent?" is not one of them: that is
     * what the submissions column answers, and having it in here as well was the screen's fifth
     * overlapping way to choose what you were looking at.</p>
     */
    private enum StateFilter {
        AI_UNREVIEWED("ai_unreviewed"),
        EDITED("edited"),
        /**
         * The working queue: machine translation nobody has reviewed, minus whatever this player
         * has already fixed — so a line vanishes from the list once they have corrected it.
         */
        TODO("todo"),
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
    private StateFilter stateFilter;
    private BodyFilter bodyFilter = BodyFilter.ALL;
    /** The strings of the submission currently picked in the SENT view. */
    private List<TranslationSubmissionsClient.SentUnit> sentUnits = List.of();
    /** True when the picked row is the working batch, whose strings come from disk, not the relay. */
    private boolean showingUnsubmitted;
    /** The column row currently being read, or null for "the work still to do" — the default. */
    private TranslationSubmission picked;
    /** Both sit under the column, and only while the working batch is what is selected in it. */
    private Button submit;
    private SpriteIconButton revert;
    /** The two narrowing controls — hidden while the left pane belongs to a finished submission. */
    private CycleButton<StateFilter> stateCycle;
    private CycleButton<BodyFilter> bodyCycle;
    /**
     * The green ask-for-a-first-draft button, present only on a language the mod ships nothing for,
     * and the tally of other players who have asked for the same one ({@code -1} until it lands).
     */
    private ColorTintedButton request;
    private int requestCount = -1;
    /** The magnifier that reveals the search box, and whether it currently is. */
    private SpriteIconButton searchToggle;
    private boolean searchOpen;
    /** The row's icons — one list so the search toggle has one thing to flip, not four. */
    private final List<SpriteIconButton> fileIcons = new ArrayList<>();
    /** Where the current language's name is drawn, right of the globe, and where it must stop. */
    private int languageLabelX;
    private int cyclesLeftEdge;
    /** Column height with and without Submit beneath it; swapped by {@link #setSubmitVisible}. */
    private int fullColumnHeight;
    private int shortColumnHeight;
    /** What the last export/import did, shown under the title. Empty until one runs. */
    private Component status = CommonComponents.EMPTY;
    private boolean statusIsError;
    /** Ticks left before it hands the subtitle's line back. */
    private int statusTicks;

    public TranslationScreen(Screen parent, String locale) {
        super(Component.translatable("gui.dungeontrain.translate.title"));
        this.parent = parent;
        this.locale = locale == null ? "" : locale.toLowerCase(Locale.ROOT);
        // "Needs a human" is the right thing to open on for a language the mod ships machine
        // translation for. For one it ships nothing for, that queue is empty by definition — every
        // string needs a human — and opening on it would show a translator an empty screen for a
        // language with a thousand blank lines in it.
        this.stateFilter = hasAiQueue() ? StateFilter.AI_UNREVIEWED : StateFilter.ALL;
    }

    /** Whether this locale has machine translation to review at all — see {@link #offeredStates}. */
    private boolean hasAiQueue() {
        return ProvenanceManifestRegistry.hasData(locale);
    }

    /**
     * True when {@code locale} is a language the editor should open on — every real language, now,
     * rather than only the nineteen the mod ships a lang file for.
     *
     * <p>The rule itself lives in {@link TranslationFilters#isTranslatableLocale}, with the
     * reasoning, for the reason every predicate in that class does: what a translator is offered is
     * worth being able to answer without opening Minecraft.</p>
     */
    public static boolean isEditable(String locale) {
        return TranslationFilters.isTranslatableLocale(locale);
    }

    @Override
    protected void init() {
        // Coming back from the language picker with a different language chosen: this screen's
        // locale is fixed at construction, so it hands over to one for the new target rather than
        // going on editing the language you just left. The equality check makes it a no-op on every
        // other init, and false immediately after the swap, so it cannot recurse.
        String current = TranslationTarget.resolveForClient();
        if (!current.isEmpty() && !current.equals(locale)) {
            minecraft.setScreen(new TranslationScreen(parent, current));
            return;
        }
        // Pull the latest approvals for the language on screen every time the editor opens. The
        // title screen's fetch is once per session, which would mean a translator who just had a
        // string approved kept being asked to fix it until they restarted the game.
        ApprovedTranslationsFetcher.fetchAsync(locale);
        // And whatever a reviewer has written back — asked again on every open, for the same
        // reason the approvals fetch above is: a reply written while the game was running would
        // otherwise wait for a restart. Opening the editor is also the moment those replies stop
        // being unread, since this is the screen they can be acted on from.
        TranslationReviewNotes.refresh(this::onNotesLoaded);
        // One row of controls, not three tiers of them. Search and the two narrowing cycles sit
        // together because they do the same job — cutting down what is in front of you — and giving
        // the cycles a tier of their own made them read as top-level navigation, which the column
        // now is.
        int bottomRow = height - MARGIN - ROW_H;
        int listBottom = bottomRow - GAP;
        int contentWidth = width - MARGIN * 2;
        // A language with no machine draft gets a row of its own between the controls and the list.
        // Above the strings rather than below them because it is the first thing to do on such a
        // language, not the last: a thousand blank lines is not where a translator should start.
        boolean offerRequest = !DungeonTrainLanguages.isTranslated(locale) && isEditable(locale);
        int requestStrip = offerRequest ? ROW_H + GAP : 0;
        int listTop = TOP + ROW_H + GAP * 2 + requestStrip;

        List<StateFilter> states = offeredStates();
        if (!states.contains(stateFilter)) {
            // A selection carried over from a session where it was unlocked. Without this the gate
            // is bypassed simply by reopening the screen.
            stateFilter = StateFilter.AI_UNREVIEWED;
        }
        int cycleWidth = Math.min(FILTER_MAX_W, (contentWidth - GAP * 2) / 4);
        // Search costs one square until you want it. Both ends of the row are pinned — icon left,
        // cycles right — so opening the box fills the middle instead of shoving anything sideways.
        int searchX = MARGIN + ROW_H + GAP;
        int cyclesX = MARGIN + contentWidth - (cycleWidth + GAP) - cycleWidth;
        cyclesLeftEdge = cyclesX;
        int searchWidth = Math.max(ROW_H, cyclesX - GAP - searchX);

        searchToggle = addRenderableWidget(spriteButton(MARGIN, TOP, SEARCH_ICON, VANILLA_ICON_PX,
            Component.translatable("gui.dungeontrain.translate.search"),
            b -> setSearchOpen(!searchOpen)));

        // Working outside the game lives here too, in the gap the open search box wants. The
        // magnifier keeps the corner: it is the one control that stays put in both states, so it is
        // what the row reads from.
        fileIcons.clear();
        int iconX = searchX;
        fileIcons.add(iconButton(iconX, TOP, FOLDER_ICON, "open", null, b -> openFolder()));
        iconX += ROW_H + GAP;
        fileIcons.add(iconButton(iconX, TOP, EXPORT_ICON, "export", "export.tip", b -> runExport()));
        iconX += ROW_H + GAP;
        fileIcons.add(iconButton(iconX, TOP, IMPORT_ICON, "import", "import.tip", b -> runImport()));
        iconX += ROW_H + GAP;
        // Which language you are translating, and the way to change it. It belongs here rather than
        // three screens away in Options: this is the screen where that fact matters.
        SpriteIconButton language = spriteButton(iconX, TOP, LANGUAGE_ICON, VANILLA_ICON_PX,
            Component.translatable("options.language"),
            b -> minecraft.setScreen(new LanguageSelectScreen(this, minecraft.options,
                minecraft.getLanguageManager())));
        language.setTooltip(Tooltip.create(Component.translatable("options.language")));
        fileIcons.add(addRenderableWidget(language));
        languageLabelX = iconX + ROW_H + GAP;

        search = new EditBox(font, searchX, TOP, searchWidth, ROW_H,
            Component.translatable("gui.dungeontrain.translate.search"));
        search.setHint(Component.translatable("gui.dungeontrain.translate.search"));
        search.setMaxLength(100);
        search.setResponder(text -> refresh());
        addRenderableWidget(search);

        stateCycle = addRenderableWidget(CycleButton.<StateFilter>builder(StateFilter::label)
            .withValues(states)
            .withInitialValue(stateFilter)
            .displayOnlyValue()
            .create(cyclesX, TOP, cycleWidth, ROW_H,
                Component.translatable("gui.dungeontrain.translate.filter"),
                (button, value) -> {
                    stateFilter = value;
                    refresh();
                }));
        bodyCycle = addRenderableWidget(CycleButton.<BodyFilter>builder(BodyFilter::label)
            .withValues(BodyFilter.values())
            .withInitialValue(bodyFilter)
            .displayOnlyValue()
            .create(cyclesX + cycleWidth + GAP, TOP, cycleWidth, ROW_H,
                Component.translatable("gui.dungeontrain.translate.body"),
                (button, value) -> {
                    bodyFilter = value;
                    refresh();
                }));
        applySearchOpen(); // the box starts collapsed, and survives a resize in whatever state it was

        if (offerRequest) {
            request = addRenderableWidget(new ColorTintedButton(MARGIN, TOP + ROW_H + GAP,
                contentWidth, ROW_H, requestLabel(),
                SUBMIT_TINT[0], SUBMIT_TINT[1], SUBMIT_TINT[2], b -> requestTranslation()));
            request.setTooltip(Tooltip.create(
                Component.translatable("gui.dungeontrain.translate.request.tip")));
            request.active = !TranslationRequests.isRequested(locale);
            // Asked for on every open, not only after a press: the number is the one part of this
            // that makes a lone request feel like it joined something.
            TranslationRequestClient.fetchCount(locale, this::onRequestCount);
        }

        // Two panes, always: strings on the left, what you have sent down a narrow right column.
        // The column is the navigation — what the left pane is showing is whatever it says.
        int listHeight = Math.max(ROW_H, listBottom - listTop);
        int sentWidth = Math.max(SENT_COLUMN_MIN_W, (contentWidth - GAP) * SENT_COLUMN_PERCENT / 100);
        int leftWidth = Math.max(ROW_H, contentWidth - GAP - sentWidth);

        // The two panes have different bottoms. Done is only as wide as the column, so nothing sits
        // under the strings — they run all the way to the margin instead of stopping short for a
        // button that was never over them.
        int leftHeight = Math.max(ROW_H, height - MARGIN - listTop);
        list = new TranslationListWidget(font, MARGIN, listTop, leftWidth, leftHeight,
            this::openEditor);
        addRenderableWidget(list);

        // Submit sits under the column, because it acts on the working batch at the top of it. The
        // column runs full height and only gives up a row when Submit is actually there — an empty
        // strip held open for a button that is not showing is just wasted column.
        int sentX = MARGIN + leftWidth + GAP;
        fullColumnHeight = listHeight;
        shortColumnHeight = Math.max(ROW_H, listHeight - ROW_H - GAP);
        sentList = new TranslationSubmissionList(font, sentX, listTop,
            sentWidth, fullColumnHeight, this::openSubmission);
        addRenderableWidget(sentList);

        // Submit and Undo are the two things you can do to the working batch — send it, or throw it
        // away — so they sit together under it rather than at opposite ends of the screen. Green
        // because it is the one affirmative action here; the trash keeps its words in its tooltip
        // and its confirm screen.
        int stripY = listTop + shortColumnHeight + GAP;
        submit = addRenderableWidget(new ColorTintedButton(sentX, stripY,
            Math.max(ROW_H, sentWidth - ROW_H - GAP), ROW_H,
            Component.translatable("gui.dungeontrain.translate.submit"),
            SUBMIT_TINT[0], SUBMIT_TINT[1], SUBMIT_TINT[2],
            b -> minecraft.setScreen(new TranslationSubmitScreen(this, locale))));
        Component undoName = Component.translatable("gui.dungeontrain.translate.revert_all");
        revert = addRenderableWidget(spriteButton(sentX + sentWidth - ROW_H, stripY, TRASH_ICON,
            OWN_ICON_PX, undoName, b -> revertAll()));
        revert.setTooltip(Tooltip.create(undoName));
        setSubmitVisible(false); // and with it, the column back to full height
        // Paint the working batch immediately from local state; the relay's history lands on top of
        // it when the fetch returns. The column is never empty while waiting on the network.
        onHistory(List.of());
        TranslationSubmissionsClient.fetch(this::onHistory);

        layoutBottomRow(bottomRow, sentX, sentWidth);
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
        // Always the first row, empty or not — it is where "what am I working on" lives, and the
        // column is the navigation now, so its top entry cannot come and go. Submit is what hides
        // when there is nothing in it; the row still reports.
        all.add(TranslationSubmission.unsubmitted(locale,
            TranslationOverrides.unsubmittedFor(locale).size()));
        all.addAll(sent);
        sentList.setRows(all);
        // Open on the working batch. It is row 0 and always exists, so the column always has a
        // current context — without this the resting state and the selected state paint the same
        // pane, told apart only by a highlight, and Submit hides behind a click that looks like it
        // did nothing.
        sentList.selectFirst();
    }

    /**
     * Submit exists only while the working batch is the selected row.
     *
     * <p>One rule in one place, because the alternative — a button that asks separately whether
     * there is anything to send — can disagree with the row it is sitting under. The row is only
     * built when there IS something to send, so selecting it is the whole condition.</p>
     */
    private void setSubmitVisible(boolean visible) {
        if (submit == null) {
            return;
        }
        submit.visible = visible;
        // The trash goes with it: both act on the working batch, and a lone bin beside a hidden
        // Submit would be an action with nothing to act on.
        if (revert != null) {
            revert.visible = visible;
        }
        // The column takes the space back when the buttons are not there, rather than holding an
        // empty strip open for them.
        if (sentList != null) {
            sentList.resizeTo(visible ? shortColumnHeight : fullColumnHeight);
        }
    }

    /**
     * Show one submission's strings in the left pane. A queued submission has no relay-side units
     * yet, so it opens empty — which is the truth: the relay has not seen it.
     */
    private void openSubmission(TranslationSubmission submission) {
        sentUnits = List.of();
        // null is the row being put back down: nothing is picked, so the left pane goes back to the
        // work still to do and the narrowing controls come back with it.
        picked = submission;
        showingUnsubmitted = submission != null && submission.unsubmitted();
        // Selected AND carrying something. The row exists even when empty, so "is it selected" is no
        // longer the whole question — an empty batch would put back the button that can do nothing.
        setSubmitVisible(showingUnsubmitted && submission.units() > 0);
        applyFilterVisibility();
        refresh();
        if (submission != null && !submission.queued() && !submission.unsubmitted()) {
            TranslationSubmissionsClient.fetchUnits(submission.submittedAtMs(), units -> {
                sentUnits = units;
                refresh();
            });
        }
    }

    /**
     * Open or close the search box.
     *
     * <p>Closing CLEARS the query. A hidden box still holding text is an invisible filter — the list
     * would show a subset with nothing on screen to explain why — and that is the same class of lie
     * as a highlighted row driving nothing.</p>
     */
    private void setSearchOpen(boolean open) {
        searchOpen = open;
        if (!open && search != null && !search.getValue().isEmpty()) {
            search.setValue(""); // fires the responder, which refreshes
        }
        applySearchOpen();
        if (open && search != null) {
            setFocused(search);
            search.setFocused(true);
        }
    }

    private void applySearchOpen() {
        if (search != null) {
            search.visible = searchOpen;
            search.active = searchOpen;
        }
        // The file icons sit exactly where the open box wants to be. One row, two things wanting the
        // same width — the one just asked for wins, and they come back when it closes.
        for (SpriteIconButton icon : fileIcons) {
            icon.visible = !searchOpen;
            icon.active = !searchOpen;
        }
        if (searchToggle != null) {
            searchToggle.setTooltip(Tooltip.create(Component.translatable(searchOpen
                ? "gui.dungeontrain.translate.search.close"
                : "gui.dungeontrain.translate.search")));
        }
    }

    /**
     * The narrowing controls exist only over unfinished work.
     *
     * <p>A finished submission is a record of what was sent; narrowing it by "needs a human" or
     * "still to do" asks a question of it that has no meaning. Search stays either way — finding a
     * string inside something you sent is a fair thing to want, and it is not a filter.</p>
     */
    private void applyFilterVisibility() {
        boolean unfinished = picked == null || picked.unsubmitted();
        if (stateCycle != null) {
            stateCycle.visible = unfinished;
        }
        if (bodyCycle != null) {
            bodyCycle.visible = unfinished;
        }
    }

    /**
     * The catalog units of the picked submission, looked up by id so they open in the normal
     * editor. A string whose key has since been removed from the mod is dropped rather than
     * faked — there is nothing left to edit.
     */
    private List<TranslationUnit> sentUnitRows(String needle) {
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
     * The state filters this player is offered.
     *
     * <p>Browsing every string unfiltered turns the editor into a way to read the game's text out
     * of context, so it is not offered to everyone — it unlocks once the player has had a
     * translation accepted, which is the cheapest honest proof they are here to translate. Fails
     * closed: no verdict, no unlock.</p>
     */
    private List<StateFilter> offeredStates() {
        List<StateFilter> out = new ArrayList<>(
            List.of(StateFilter.AI_UNREVIEWED, StateFilter.EDITED, StateFilter.TODO));
        // Unlocked by contributing — or, on a language with no machine translation to review, from
        // the start: the gate exists to point a newcomer at the review queue first, and where there
        // is no queue it would only hide the entire catalog behind work they cannot do yet.
        if (TranslationContributor.hasApprovedTranslation() || !hasAiQueue()) {
            out.add(StateFilter.ALL);
        }
        return out;
    }

    /**
     * The action row along the bottom. Split out because every entry here is one feature's
     * doorway, and the row grows as those land.
     */
    private void layoutBottomRow(int y, int x, int width) {
        // Nothing else lives down here any more: the file icons went up to the control row, Submit
        // and the bin belong to the working batch under the column, and what is left is the way out.
        // Column width, under the column, in line with Submit — so the strings keep the full height
        // of their own side rather than paying for a button that is not over them.
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(x, y, width, ROW_H).build());
    }

    /**
     * A square button carrying a GUI sprite, drawn at the sprite's AUTHORED size — vanilla's
     * icon/search is 12px and ours are 16, and drawing each 1:1 is what keeps them crisp. In a
     * ROW_H button that leaves the glyph clear of the frame rather than sitting on it.
     */
    private SpriteIconButton spriteButton(int x, int y, ResourceLocation icon, int iconPx,
                                          Component name, Button.OnPress onPress) {
        SpriteIconButton button = SpriteIconButton.builder(name, onPress, true)
            .width(ROW_H)
            .sprite(icon, iconPx, iconPx)
            .build();
        button.setPosition(x, y);
        return button;
    }

    /**
     * One square action button carrying a GUI sprite.
     *
     * <p>The tooltip does the labelling an icon cannot: the button's own name, and — where the old
     * Files screen had one — the sentence explaining what it will actually do to your disk.</p>
     */
    private SpriteIconButton iconButton(int x, int y, ResourceLocation icon, String key,
                                        String tipKey, Button.OnPress onPress) {
        Component name = Component.translatable("gui.dungeontrain.translate.files." + key);
        SpriteIconButton button = spriteButton(x, y, icon, OWN_ICON_PX, name, onPress);
        button.setTooltip(Tooltip.create(tipKey == null ? name
            : name.copy().append("\n").append(
                Component.translatable("gui.dungeontrain.translate.files." + tipKey))));
        return addRenderableWidget(button);
    }

    // ---- working outside the game (was TranslationFilesScreen) ----------------------------------

    /** Write this locale's text out to CSV + JSON for editing in a spreadsheet or text editor. */
    private void runExport() {
        TranslationExporter.Result result = TranslationExporter.export(locale);
        if (result.ok()) {
            setStatus(Component.translatable("gui.dungeontrain.translate.files.exported",
                result.uiRows() + result.siblingRows(), result.books()), false);
        } else {
            setStatus(Component.translatable("gui.dungeontrain.translate.files.failed"), true);
        }
    }

    /** Read back whatever is in the import folder and apply it as this player's edits. */
    private void runImport() {
        TranslationImporter.Result result = TranslationImporter.importAll(locale);
        if (!result.ok()) {
            setStatus(Component.translatable("gui.dungeontrain.translate.files.failed"), true);
            return;
        }
        if (result.changed() == 0) {
            // Distinct from an error on purpose: the usual cause is an empty import folder, and
            // "0 changes" plus the folder path is the answer to "why did nothing happen".
            setStatus(Component.translatable("gui.dungeontrain.translate.files.nothing"), false);
            return;
        }
        setStatus(Component.translatable("gui.dungeontrain.translate.files.imported",
            result.langChanged(), result.bookChanged()), false);
        // An import IS an edit, so the list and the working batch both have to catch up.
        refresh();
        onHistory(List.of());
    }

    /**
     * Open the translations folder in the OS file manager, creating both halves first so the
     * player lands somewhere that exists and can see where their files should go.
     */
    private void openFolder() {
        try {
            java.nio.file.Path root = TranslationOverrideStore.root();
            java.nio.file.Files.createDirectories(TranslationExporter.directoryFor(locale));
            java.nio.file.Files.createDirectories(TranslationImporter.directoryFor(locale));
            net.minecraft.Util.getPlatform().openUri(root.toUri());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: could not open the folder — {}", e.toString());
            setStatus(Component.translatable("gui.dungeontrain.translate.files.failed"), true);
        }
    }

    private void setStatus(Component message, boolean error) {
        this.status = message;
        this.statusIsError = error;
        this.statusTicks = STATUS_TICKS;
    }

    /** Replies landed (or were already held): mark them read and show their markers. */
    private void onNotesLoaded() {
        TranslationReviewNotes.markAllSeen();
        refresh();
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
        list.setDismissed(this::isDismissed);
        list.setNoteText(TranslationReviewNotes::textFor);
        list.setUnits(visibleUnits());
    }

    private List<TranslationUnit> visibleUnits() {
        if (!isEditable(locale)) {
            return List.of();
        }
        String needle = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        // Only a SUBMISSION diverts this pane. The working batch is where translating happens, so it
        // gets the whole catalog with the filters doing their job — being inside your unsubmitted
        // work is precisely when "needs a human" and the rest are worth having.
        if (picked != null && !showingUnsubmitted) {
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
            // Both work queues drop a string this player has marked good as is — that IS the
            // review they were being asked for, and asking again is what the mark exists to stop.
            // ALL still shows it (tagged ✓), so a dismissal is never a way to lose a string.
            case TODO -> TranslationFilters.needsHuman(unit, approved, this::isDismissed)
                && overrideOf(unit, edits) == null;
            case AI_UNREVIEWED -> TranslationFilters.needsHuman(unit, approved, this::isDismissed);
            case EDITED -> overrideOf(unit, edits) != null;
        };
    }

    private boolean isDismissed(TranslationUnit unit) {
        return TranslationDismissals.isDismissed(locale, unit);
    }

    // ---- asking for a machine first draft -------------------------------------------------------

    /**
     * "Request AI translation", or — once asked — what was asked and by how many. The count only
     * joins the label when the relay has answered; there is no "0 players", which would read as a
     * verdict on the language rather than as a network that has not replied yet.
     */
    private Component requestLabel() {
        boolean asked = TranslationRequests.isRequested(locale);
        String key = "gui.dungeontrain.translate.request" + (asked ? ".sent" : "");
        return requestCount > 0
            ? Component.translatable(key + ".count", requestCount)
            : Component.translatable(key);
    }

    private void requestTranslation() {
        if (!TranslationRequests.record(locale)) {
            return; // already asked, or a locale that cannot be recorded
        }
        // The local record first, so the button acknowledges the press whatever the network does.
        TranslationRequestClient.send(locale, this::onRequestCount);
        if (request != null) {
            request.active = false;
            request.setMessage(requestLabel());
        }
    }

    private void onRequestCount(int count) {
        requestCount = count;
        if (request != null) {
            request.setMessage(requestLabel());
        }
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
        // The last export/import takes the subtitle's line while it lasts — right under the icons
        // that produced it, now that those are in the top row. It hands the line back on its own
        // (see tick), because the subtitle carries a real warning that cannot be displaced for good.
        boolean showStatus = status != CommonComponents.EMPTY;
        g.drawCenteredString(font, showStatus ? status : subtitle(), width / 2,
            8 + font.lineHeight + 2,
            showStatus ? (statusIsError ? STATUS_ERROR_COLOUR : STATUS_OK_COLOUR) : SUBTITLE_COLOUR);

        // The language being translated, beside the globe that changes it. A readout, not a widget:
        // there is nothing to click that the button next to it does not already do. Truncated to the
        // room left before the filter cycles rather than pushing anything sideways.
        if (!searchOpen && languageLabelX > 0) {
            int room = Math.max(0, cyclesLeftEdge - GAP - languageLabelX);
            g.drawString(font, font.plainSubstrByWidth(languageName(), room),
                languageLabelX, TOP + (ROW_H - font.lineHeight) / 2, SUBTITLE_COLOUR, false);
        }
    }

    /**
     * The display name of the language being translated — vanilla's own, so there is not a second
     * table of language names here to fall out of step with the one in the picker this sits beside.
     * Falls back to the bare code for a locale the client does not know (a localization pack's own).
     */
    private String languageName() {
        LanguageInfo info = minecraft == null || minecraft.getLanguageManager() == null
            ? null : minecraft.getLanguageManager().getLanguage(locale);
        return info == null ? locale : info.toComponent().getString();
    }

    @Override
    public void tick() {
        super.tick();
        if (statusTicks > 0 && --statusTicks == 0) {
            status = CommonComponents.EMPTY;
        }
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
