package games.brennan.dungeontrain.client.localization.edit;

import games.brennan.dungeontrain.client.localization.LanguageAiFilter;
import games.brennan.dungeontrain.client.localization.LanguageCountryIndex;
import games.brennan.dungeontrain.mixin.client.LanguageSelectEntryAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.network.chat.Component;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Search and an AI-coverage filter for the vanilla Language screen.
 *
 * <p>Minecraft offers about a hundred and thirty languages in one unbroken scroll. Dungeon Train
 * ships nineteen of them, in two states — machine-translated, and read by a human — and the rows
 * already say which is which, with the logo and the ring {@code LanguageSelectEntryLogoMixin} draws.
 * What the screen had no way to answer was the question those badges provoke: <em>where is
 * mine</em>, and <em>which ones still need someone</em>. This adds both, as one row above the
 * list.</p>
 *
 * <p>The magnifier reveals the box rather than the box always sitting there, the same interaction as
 * the translation editor's — a row that costs one square until you want it. The filter is the same
 * bargain in the other direction: a funnel while it is showing everything, because a control that
 * is not narrowing anything has nothing to say, and a labelled button naming the state the moment
 * it is. It never hides, unlike the box — a query you have to have typed, but a filter is a state
 * you can be in without having done anything, and a silent one would be a list quietly missing
 * rows.</p>
 *
 * <p>Both sit on the title's own line, in the header band vanilla leaves empty either side of it.
 * That space is already there and already the width of the list, so the controls cost the list no
 * height at all — a row of their own would have taken thirty pixels of languages to say nothing the
 * heading does not already say. When the box opens it takes the title's room, exactly as the
 * editor's takes its file icons'.</p>
 *
 * <p>Filtering re-lists the vanilla rows rather than rebuilding them: a row is a private inner class
 * holding a {@code LanguageInfo} this has no way to reconstruct, so the originals are captured once
 * per list and nothing is ever lost by narrowing. The re-listing goes through the list's own public
 * {@code children()}, which IS the list vanilla's protected {@code clearEntries}/{@code addEntry}
 * mutate — same object, same binding on add — so this needs no mixin and no access widening.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LanguageScreenSearch {

    /** Vanilla's own magnifier — the icon Minecraft uses for searching. */
    private static final ResourceLocation SEARCH_ICON =
        ResourceLocation.withDefaultNamespace("icon/search");
    private static final int SEARCH_ICON_PX = 12;
    /** A funnel, ours — vanilla has no filter glyph, and its icons are the wrong vocabulary here. */
    private static final ResourceLocation FILTER_ICON =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/filter");
    /** Authored at 16 and drawn 1:1, like the editor's own icons. */
    private static final int FILTER_ICON_PX = 16;
    /** Vanilla insets a button's label by 2px a side; 10 keeps a word clear of the frame. */
    private static final int LABEL_PAD = 10;

    private static final int ROW_H = 20;
    /** Vanilla's spacing, the same 8 the footer row uses. */
    private static final int GAP = 8;
    /** The screen edge the row is kept inside when the window is narrower than the list's rows. */
    private static final int MARGIN = 6;
    private static final int FILTER_MAX_W = 110;
    private static final int MAX_QUERY = 50;

    /**
     * The list the captured rows belong to. Vanilla builds a fresh one in every {@code addContents}
     * and clears the screen's widgets in the same breath, so a new instance is the one reliable
     * signal that this screen has been rebuilt from scratch.
     */
    private static WeakReference<ObjectSelectionList<?>> owner = new WeakReference<>(null);
    /**
     * Every row the list had before anything narrowed it, in vanilla's order. Held as {@code Object}
     * because the row type is a protected inner class this cannot name — nothing here needs to,
     * beyond asking each row for its locale code through the accessor.
     */
    private static List<Object> allRows = List.of();

    /** Vanilla's heading, which the open search box takes the room of. Weak, like {@link #owner}. */
    private static WeakReference<AbstractWidget> titleWidget = new WeakReference<>(null);
    private static SpriteIconButton toggle;
    private static EditBox search;
    /** The funnel, shown while the filter is showing everything. */
    private static SpriteIconButton filterIcon;
    /** What it becomes once it is narrowing something: a button naming the state. */
    private static Button filterLabel;
    /** Where the left cluster ends, so the title knows whether it still has room. */
    private static int clusterRight;
    private static boolean searchOpen;
    /** The row's band, kept so a filter change can re-lay it out without another Init.Post. */
    private static int rowY;
    private static int rowLeft;
    private static int rowRight;
    private static LanguageAiFilter filter = LanguageAiFilter.ALL;

    private LanguageScreenSearch() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof LanguageSelectScreen screen)) {
            return;
        }
        ObjectSelectionList<?> list = LanguageSwitchPrompt.languageList(screen);
        if (list == null) {
            return; // another mod owns this screen's list; leave it alone
        }
        AbstractWidget title = findTitle(screen);
        if (screen.layout.getHeaderHeight() < ROW_H) {
            return; // a header too short to hold the row; leave the screen as vanilla built it
        }

        boolean rebuilt = list != owner.get();
        if (rebuilt) {
            owner = new WeakReference<>(list);
            allRows = capture(list);
            // Deliberately NOT resetting the query or the filter: init() re-runs on every window
            // resize, and a search that emptied itself when you dragged the window would be worse
            // than not having one.
        }
        if (allRows.isEmpty()) {
            return;
        }

        // Centred on the title's line, and held inside the screen at widths where the list's rows
        // are wider than the window. The header is 33 and the controls are 20, so on any window
        // vanilla itself draws a title on, this lands clear of both edges of the band.
        rowY = Math.max(1, titleMiddle(screen, title) - ROW_H / 2);
        rowLeft = Math.max(MARGIN, list.getRowLeft());
        rowRight = Math.min(screen.width - MARGIN, list.getRowRight());

        if (rebuilt || toggle == null) {
            buildWidgets(screen);
        }
        // Adding is conditional on absence rather than on `rebuilt`, so a second Init.Post pass over
        // a screen whose widgets were never cleared cannot leave a twin behind.
        addIfAbsent(event, filterIcon);
        addIfAbsent(event, filterLabel);
        addIfAbsent(event, toggle);
        addIfAbsent(event, search);

        titleWidget = new WeakReference<>(title);
        layoutRow();
        applyFilter(list);
    }

    /**
     * Places the row left to right — filter, magnifier, then whatever is left over for the box.
     *
     * <p>Re-run whenever the filter changes, not only on init: the filter is a square while it is
     * showing everything and a labelled button when it is not, so the two controls to its right
     * move with it. Every number is derived from the band, so running it again is idempotent.</p>
     */
    private static void layoutRow() {
        int filterW = filter == LanguageAiFilter.ALL ? ROW_H : Math.min(FILTER_MAX_W,
            Math.max(ROW_H, Minecraft.getInstance().font.width(filter.label()) + LABEL_PAD));
        filterIcon.setPosition(rowLeft, rowY);
        filterLabel.setPosition(rowLeft, rowY);
        filterLabel.setWidth(filterW);

        int toggleX = rowLeft + filterW + GAP;
        toggle.setPosition(toggleX, rowY);
        clusterRight = toggleX + ROW_H;

        int searchX = clusterRight + GAP;
        search.setX(searchX);
        search.setY(rowY);
        search.setWidth(Math.max(ROW_H, rowRight - searchX));

        applyFilterState();
        applySearchOpen();
    }

    /** Which of the two filter widgets is the one on screen, and what it says. */
    private static void applyFilterState() {
        boolean narrowing = filter != LanguageAiFilter.ALL;
        filterIcon.visible = !narrowing;
        filterIcon.active = !narrowing;
        filterLabel.visible = narrowing;
        filterLabel.active = narrowing;
        filterLabel.setMessage(filter.label());
        Component name = Component.translatable("gui.dungeontrain.language.filter");
        Tooltip tip = Tooltip.create(name.copy().append("\n").append(filter.label()));
        filterIcon.setTooltip(tip);
        filterLabel.setTooltip(tip);
    }

    private static void advanceFilter() {
        LanguageAiFilter[] values = LanguageAiFilter.values();
        filter = values[(filter.ordinal() + 1) % values.length];
        layoutRow();
        refilter();
    }

    /**
     * The vertical centre of the title's line, or of the header band when another mod has taken the
     * title away. Measured off the widget rather than off {@code headerHeight / 2} so the row tracks
     * the heading it is meant to share a line with, wherever that heading actually is.
     */
    private static int titleMiddle(LanguageSelectScreen screen, AbstractWidget title) {
        return title != null
            ? title.getY() + title.getHeight() / 2
            : screen.layout.getHeaderHeight() / 2;
    }

    /** Vanilla's heading, a {@code StringWidget} carrying the screen's own title. */
    private static AbstractWidget findTitle(LanguageSelectScreen screen) {
        for (var child : screen.children()) {
            if (child instanceof StringWidget widget
                && screen.getTitle().equals(widget.getMessage())) {
                return widget;
            }
        }
        return null;
    }

    private static void buildWidgets(LanguageSelectScreen screen) {
        // Two widgets rather than one that changes shape: a sprite button and a labelled button are
        // different vanilla components, and swapping which is visible is honest where a single
        // widget pretending to be both would mean drawing the sprite ourselves.
        filterIcon = SpriteIconButton.builder(
                Component.translatable("gui.dungeontrain.language.filter"),
                b -> advanceFilter(), true)
            .width(ROW_H)
            .sprite(FILTER_ICON, FILTER_ICON_PX, FILTER_ICON_PX)
            .build();
        filterLabel = Button.builder(filter.label(), b -> advanceFilter())
            .bounds(rowLeft, rowY, ROW_H, ROW_H)
            .build();

        toggle = SpriteIconButton.builder(
                Component.translatable("gui.dungeontrain.language.search"),
                b -> setSearchOpen(screen, !searchOpen), true)
            .width(ROW_H)
            .sprite(SEARCH_ICON, SEARCH_ICON_PX, SEARCH_ICON_PX)
            .build();

        search = new EditBox(Minecraft.getInstance().font, rowLeft, rowY, ROW_H, ROW_H,
            Component.translatable("gui.dungeontrain.language.search"));
        search.setHint(Component.translatable("gui.dungeontrain.language.search.hint"));
        search.setMaxLength(MAX_QUERY);
        search.setResponder(text -> refilter());
    }

    private static void setSearchOpen(LanguageSelectScreen screen, boolean open) {
        searchOpen = open;
        if (!open && !search.getValue().isEmpty()) {
            search.setValue(""); // fires the responder, which re-filters
        }
        layoutRow();
        if (open) {
            // Focused on purpose: the box is one keystroke of intent away from being useless, and
            // while it holds focus Enter goes to it rather than to the list, whose rows read Enter
            // as "apply this language and close".
            screen.setFocused(search);
            search.setFocused(true);
        }
    }

    private static void applySearchOpen() {
        search.visible = searchOpen;
        search.active = searchOpen;
        // The row shares the title's line, so the title stands down when the row actually needs its
        // room — the open box, or a filter label long enough to reach it. Measured rather than
        // assumed: at a wide window "Language" and the whole row coexist happily, and hiding the
        // heading of a screen for no reason is not a trade worth making by default.
        AbstractWidget title = titleWidget.get();
        if (title != null) {
            title.visible = !searchOpen && title.getX() >= clusterRight + GAP;
        }
        toggle.setTooltip(Tooltip.create(Component.translatable(searchOpen
            ? "gui.dungeontrain.language.search.close"
            : "gui.dungeontrain.language.search")));
    }

    private static void refilter() {
        ObjectSelectionList<?> list = owner.get();
        if (list != null) {
            applyFilter(list);
        }
    }

    /**
     * Re-lists the captured rows, keeping those the query and the filter both admit.
     *
     * <p>The selection is carried across when it survives the narrowing and dropped when it does
     * not. Dropping it is safe: vanilla's {@code onDone} treats a null selection as "no change",
     * so a language cannot be applied by having been filtered away from.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyFilter(ObjectSelectionList<?> list) {
        String needle = searchOpen ? search.getValue().trim().toLowerCase(Locale.ROOT) : "";
        if (filter == LanguageAiFilter.ALL && needle.isEmpty()
            && list.children().size() == allRows.size()) {
            // Nothing is being narrowed and nothing currently is. Returning rather than re-listing
            // an identical set keeps vanilla's own centre-on-the-current-language scroll, which a
            // clear-and-refill would throw away every time the screen re-initialised.
            return;
        }
        // Raw, because the row type is a protected inner class: every generic signature that could
        // describe this list is one that cannot be written down outside vanilla's own package.
        List rows = list.children();
        Object selected = list.getSelected();
        Object keep = null;

        rows.clear();
        for (Object row : allRows) {
            String code = codeOf(row);
            if (code == null || !filter.matchesLocale(code) || !matchesQuery(code, needle)) {
                continue;
            }
            rows.add(row);
            if (row == selected) {
                keep = row;
            }
        }
        // Explicitly, because clearing the rows is only half of what vanilla's clearEntries does —
        // the other half is dropping a selection that may no longer be in the list.
        select(list, keep);
        if (keep == null) {
            list.setScrollAmount(0);
        }
    }

    /**
     * {@code list.setSelected(keep)} for a list whose element type cannot be named here.
     *
     * <p>{@code ObjectSelectionList.Entry} is public where {@code AbstractSelectionList.Entry} is
     * not, so its own bound — {@code E extends ObjectSelectionList.Entry<E>} — is writable, and a
     * type variable is what lets the cast happen somewhere the compiler can at least check the
     * shape of. The row genuinely came out of this list, so the unchecked cast is sound.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <E extends ObjectSelectionList.Entry<E>> void select(ObjectSelectionList<?> list,
                                                                       Object keep) {
        ((ObjectSelectionList<E>) list).setSelected((E) keep);
    }

    /**
     * Everything a language can be found by: its locale code, the name and region vanilla puts on
     * the row, and the countries that speak it — in the player's own language and in English.
     *
     * <p>Vanilla's own text already covers the country in the language's own words, so "Brasil"
     * and "Deutschland" work with no help. The index is what adds "Brazil" and "Allemagne", and
     * the countries that share a language without lending it their name: "Switzerland" reaches
     * German, French and Italian; "Argentina" reaches Spanish.</p>
     */
    private static boolean matchesQuery(String code, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        if (code.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        var info = Minecraft.getInstance().getLanguageManager().getLanguages().get(code);
        if (info != null
            && info.toComponent().getString().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        return LanguageCountryIndex.matchesCountry(code, needle, uiLocale());
    }

    /** The language the player is reading in, which is the one country names should come back in. */
    private static String uiLocale() {
        var manager = Minecraft.getInstance().getLanguageManager();
        return manager == null ? "" : manager.getSelected();
    }

    private static String codeOf(Object row) {
        return row instanceof LanguageSelectEntryAccessor accessor
            ? accessor.dungeontrain$code()
            : null;
    }

    private static List<Object> capture(ObjectSelectionList<?> list) {
        return List.copyOf(new ArrayList<Object>(list.children()));
    }

    private static void addIfAbsent(ScreenEvent.Init.Post event, GuiEventListener widget) {
        if (!event.getScreen().children().contains(widget)) {
            event.addListener(widget);
        }
    }
}
