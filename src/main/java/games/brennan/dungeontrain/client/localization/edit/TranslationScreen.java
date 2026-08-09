package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /** Which units the list shows. */
    private enum StateFilter {
        AI_UNREVIEWED("ai_unreviewed"),
        EDITED("edited"),
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
    private StateFilter stateFilter = StateFilter.AI_UNREVIEWED;
    private BodyFilter bodyFilter = BodyFilter.ALL;

    public TranslationScreen(Screen parent, String locale) {
        super(Component.translatable("gui.dungeontrain.translate.title"));
        this.parent = parent;
        this.locale = locale == null ? "" : locale.toLowerCase(Locale.ROOT);
    }

    /** True when {@code locale} is something a player can translate into (i.e. not the source). */
    public static boolean isEditable(String locale) {
        return locale != null && !locale.isBlank() && !"en_us".equalsIgnoreCase(locale);
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
                    stateFilter = value;
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

        list = new TranslationListWidget(font, MARGIN, listTop, contentWidth,
            Math.max(ROW_H, listBottom - listTop), this::openEditor);
        addRenderableWidget(list);

        layoutBottomRow(bottomRow, contentWidth);
        refresh();
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
        List<StateFilter> out = new ArrayList<>(List.of(StateFilter.AI_UNREVIEWED, StateFilter.EDITED));
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
        addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.submit"),
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
