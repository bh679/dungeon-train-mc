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
                    boolean wasSplit = stateFilter == StateFilter.SENT;
                    stateFilter = value;
                    if (wasSplit != (value == StateFilter.SENT)) {
                        // The pane count changed: vanilla's rebuildWidgets re-runs init() for us.
                        rebuildWidgets();
                    } else {
                        refresh();
                    }
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

        // The SENT view splits the width: submissions down a narrow right column, the picked
        // one's strings taking the rest. Every other filter gives the whole width to the catalog
        // list. The strings are what gets read and edited, so they keep the room.
        boolean split = stateFilter == StateFilter.SENT;
        int listHeight = Math.max(ROW_H, listBottom - listTop);
        int sentWidth = split
            ? Math.max(SENT_COLUMN_MIN_W, (contentWidth - GAP) * SENT_COLUMN_PERCENT / 100) : 0;
        int leftWidth = split ? Math.max(ROW_H, contentWidth - GAP - sentWidth) : contentWidth;

        list = new TranslationListWidget(font, MARGIN, listTop, leftWidth, listHeight,
            this::openEditor);
        addRenderableWidget(list);

        if (split) {
            sentList = new TranslationSubmissionList(font, MARGIN + leftWidth + GAP, listTop,
                sentWidth, listHeight, this::openSubmission);
            addRenderableWidget(sentList);
            TranslationSubmissionsClient.fetch(this::onHistory);
        } else {
            sentList = null;
        }

        layoutBottomRow(bottomRow, contentWidth);
        refresh();
    }

    /** Merge the relay's history with the local outbox, newest first, and open on the newest. */
    private void onHistory(List<TranslationSubmission> fromRelay) {
        if (sentList == null) {
            return;
        }
        List<TranslationSubmission> all = new ArrayList<>(TranslationOutbox.get().queued());
        all.addAll(fromRelay);
        all.sort((a, b) -> Long.compare(b.submittedAtMs(), a.submittedAtMs()));
        sentList.setRows(all);
        sentList.selectFirst();
    }

    /**
     * Show one submission's strings in the left pane. A queued submission has no relay-side units
     * yet, so it opens empty — which is the truth: the relay has not seen it.
     */
    private void openSubmission(TranslationSubmission submission) {
        sentUnits = List.of();
        refresh();
        if (!submission.queued()) {
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
     * The Submit doorway's label, green while there is work the relay has not seen yet — the one
     * cue that says "you have finished edits sitting on this machine doing nobody any good".
     */
    private Component submitLabel() {
        Component label = Component.translatable("gui.dungeontrain.translate.submit");
        return TranslationOverrides.hasUnsubmittedChanges(locale)
            ? label.copy().withStyle(ChatFormatting.GREEN) : label;
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
        int buttons = 4;
        int buttonWidth = (contentWidth - GAP * (buttons - 1)) / buttons;
        int x = MARGIN;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.files"),
            b -> minecraft.setScreen(new TranslationFilesScreen(this, locale)))
            .bounds(x, y, buttonWidth, ROW_H).build());
        x += buttonWidth + GAP;
        addRenderableWidget(Button.builder(submitLabel(),
            b -> minecraft.setScreen(new TranslationSubmitScreen(this, locale)))
            .bounds(x, y, buttonWidth, ROW_H).build());
        x += buttonWidth + GAP;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.revert_all"), b -> revertAll())
            .bounds(x, y, buttonWidth, ROW_H).build());
        x += buttonWidth + GAP;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(x, y, buttonWidth, ROW_H).build());
    }

    /** Rebuild the visible rows from the catalog, the current filters and the search text. */
    private void refresh() {
        if (list == null) {
            return;
        }
        list.setEdits(TranslationOverrides.mergedFor(locale));
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
        List<TranslationUnit> out = new ArrayList<>();
        for (TranslationUnit unit : TranslationCatalog.forLocale(locale)) {
            if (!matchesBody(unit) || !matchesState(unit, edits) || !unit.matches(needle)) {
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

    private boolean matchesState(TranslationUnit unit, TranslationEdits edits) {
        return switch (stateFilter) {
            case ALL -> true;
            case TODO -> unit.aiUnreviewed() && overrideOf(unit, edits) == null;
            case SENT -> true; // handled in visibleUnits — the rows come from the relay, not the catalog
            case AI_UNREVIEWED -> unit.aiUnreviewed();
            case EDITED -> overrideOf(unit, edits) != null;
        };
    }

    static String overrideOf(TranslationUnit unit, TranslationEdits edits) {
        return unit.type() == TranslationUnit.Type.BOOK
            ? edits.books().get(unit.id())
            : edits.lang().get(unit.id());
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
