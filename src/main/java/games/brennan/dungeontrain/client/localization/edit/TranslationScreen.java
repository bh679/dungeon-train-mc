package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.DungeonTrainLanguages;
import games.brennan.dungeontrain.client.menu.SpriteIconButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
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
    private StateFilter stateFilter = StateFilter.AI_UNREVIEWED;
    private BodyFilter bodyFilter = BodyFilter.ALL;
    /** The strings of the submission currently picked in the SENT view. */
    private List<TranslationSubmissionsClient.SentUnit> sentUnits = List.of();
    /** True when the picked row is the working batch, whose strings come from disk, not the relay. */
    private boolean showingUnsubmitted;
    /** The column row currently being read, or null for "the work still to do" — the default. */
    private TranslationSubmission picked;
    /** Sits under the column, and only while the working batch is what is selected in it. */
    private Button submit;
    /** The two narrowing controls — hidden while the left pane belongs to a finished submission. */
    private CycleButton<StateFilter> stateCycle;
    private CycleButton<BodyFilter> bodyCycle;
    /** The magnifier that reveals the search box, and whether it currently is. */
    private SpriteIconButton searchToggle;
    private boolean searchOpen;
    /** Column height with and without Submit beneath it; swapped by {@link #setSubmitVisible}. */
    private int fullColumnHeight;
    private int shortColumnHeight;
    /** What the last export/import did, drawn above the bottom row. Empty until one runs. */
    private Component status = CommonComponents.EMPTY;
    private boolean statusIsError;
    /** Where that line lands — just above the action row, set during layout. */
    private int statusY;

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
        // One row of controls, not three tiers of them. Search and the two narrowing cycles sit
        // together because they do the same job — cutting down what is in front of you — and giving
        // the cycles a tier of their own made them read as top-level navigation, which the column
        // now is.
        int bottomRow = height - MARGIN - ROW_H;
        int listBottom = bottomRow - GAP;
        int contentWidth = width - MARGIN * 2;
        int listTop = TOP + ROW_H + GAP * 2;

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
        int searchWidth = Math.max(ROW_H, cyclesX - GAP - searchX);

        searchToggle = addRenderableWidget(new SpriteIconButton(MARGIN, TOP, ROW_H, SEARCH_ICON,
            Component.translatable("gui.dungeontrain.translate.search"),
            b -> setSearchOpen(!searchOpen)));

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

        // Two panes, always: strings on the left, what you have sent down a narrow right column.
        // The column is the navigation — what the left pane is showing is whatever it says.
        int listHeight = Math.max(ROW_H, listBottom - listTop);
        int sentWidth = Math.max(SENT_COLUMN_MIN_W, (contentWidth - GAP) * SENT_COLUMN_PERCENT / 100);
        int leftWidth = Math.max(ROW_H, contentWidth - GAP - sentWidth);

        list = new TranslationListWidget(font, MARGIN, listTop, leftWidth, listHeight,
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

        submit = addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.submit"),
            b -> minecraft.setScreen(new TranslationSubmitScreen(this, locale)))
            .bounds(sentX, listTop + shortColumnHeight + GAP, sentWidth, ROW_H).build());
        setSubmitVisible(false); // and with it, the column back to full height
        // Paint the working batch immediately from local state; the relay's history lands on top of
        // it when the fetch returns. The column is never empty while waiting on the network.
        onHistory(List.of());
        TranslationSubmissionsClient.fetch(this::onHistory);

        statusY = bottomRow - font.lineHeight - 2; // in the gap the icons sit under
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
        // The column takes the space back when the button is not there, rather than holding an
        // empty strip open for it.
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
    private static List<StateFilter> offeredStates() {
        List<StateFilter> out = new ArrayList<>(
            List.of(StateFilter.AI_UNREVIEWED, StateFilter.EDITED, StateFilter.TODO));
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
        // heads (see init). Working outside the game is three icons rather than a doorway to a
        // screen that was only ever these same three buttons — hover names each one.
        int x = MARGIN;
        x += iconButton(x, y, FOLDER_ICON, "open", null, b -> openFolder());
        x += iconButton(x, y, EXPORT_ICON, "export", "export.tip", b -> runExport());
        x += iconButton(x, y, IMPORT_ICON, "import", "import.tip", b -> runImport());

        int rest = Math.max(ROW_H, MARGIN + contentWidth - x);
        int buttonWidth = (rest - GAP) / 2;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.revert_all"), b -> revertAll())
            .bounds(x, y, buttonWidth, ROW_H).build());
        x += buttonWidth + GAP;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(x, y, buttonWidth, ROW_H).build());
    }

    /**
     * One square action button carrying a vanilla item, returning the width it consumed.
     *
     * <p>The tooltip does the labelling an icon cannot: the button's own name, and — where the old
     * Files screen had one — the sentence explaining what it will actually do to your disk.</p>
     */
    private int iconButton(int x, int y, ResourceLocation icon, String key,
                           String tipKey, Button.OnPress onPress) {
        Component name = Component.translatable("gui.dungeontrain.translate.files." + key);
        SpriteIconButton button = new SpriteIconButton(x, y, ROW_H, icon, name, onPress);
        button.setTooltip(Tooltip.create(tipKey == null ? name
            : name.copy().append("\n").append(
                Component.translatable("gui.dungeontrain.translate.files." + tipKey))));
        addRenderableWidget(button);
        return ROW_H + GAP;
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
            case TODO -> TranslationFilters.needsHuman(unit, approved) && overrideOf(unit, edits) == null;
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
        // What the last export/import did, left-aligned under the icons that did it.
        if (status != CommonComponents.EMPTY) {
            g.drawString(font, status, MARGIN, statusY,
                statusIsError ? STATUS_ERROR_COLOUR : STATUS_OK_COLOUR, false);
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
