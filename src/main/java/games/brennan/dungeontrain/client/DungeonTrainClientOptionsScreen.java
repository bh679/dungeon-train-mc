package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.display.DisplayScaleOption;
import games.brennan.dungeontrain.client.localization.edit.TranslationScreen;
import games.brennan.dungeontrain.client.localization.edit.TranslationTarget;
import games.brennan.dungeontrain.client.sound.TrainVolumeOption;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.ContentMode;
import games.brennan.dungeontrain.config.CustomContentPreference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Home for Dungeon Train's client settings, opened from a "Dungeon Train…" button injected into
 * Minecraft's Options screen ({@link OptionsScreenDungeonTrainButton}) — reachable from both the
 * main-menu and the Esc/pause Options.
 *
 * <p>Unlike the in-world X-menu {@code OptionsMenuScreen} (a worldspace panel that needs a player and
 * so can't appear on the title screen), this is an ordinary GUI screen and works with or without a
 * world. Every row reads and writes the same {@link ClientDisplayConfig} accessors the X-menu uses —
 * so the surfaces never diverge.</p>
 *
 * <p><b>It is an {@link OptionsSubScreen} with a {@link TabNavigationBar}, and that is load-bearing.</b>
 * It used to lay itself out by hand: one 210px column, 24px per row, starting at {@code height/3},
 * Done tacked on after the last row. Eleven rows do not fit that way — on a small window or at GUI
 * Scale 3–4 the bottom rows <em>and the Done button</em> ran off-screen with no way to reach them.
 * The base class supplies a Done button pinned in the footer where it is always reachable, and each
 * tab owns a scrolling {@link OptionsList} sized to whatever height is left. Add rows through
 * {@link ClientOptionsTab}; never position widgets by hand here again.</p>
 *
 * <p><b>Rows are packed two-across only when they actually fit.</b> A row whose widest possible label
 * would overrun a {@value #ROW_W}px column takes a full-width line to itself instead of being paired
 * and left to scroll its own caption forever — see {@link #fitsNarrow}. The measurement is done on the
 * label, before the widget is built, because a widget's width is fixed at construction.</p>
 *
 * <p><b>Every row here is localized</b> — labels and tooltips alike — and new rows must follow suit
 * rather than reaching for {@link Component#literal}. This screen used to be plain-English literals
 * with the content-mode and Political Filter rows as localized exceptions; a screenshot of a Chinese
 * client settled it, because the exceptions are the reason the rest has to be translated too. The
 * consent flow asks a player for the Kid-mode and Chinese-filter answers <em>in their own language</em>,
 * then sends them here to change them: arriving at a screen where two rows are Chinese and four are
 * English is worse than either extreme. The on/off states come from vanilla's own {@code options.on} /
 * {@code options.off}, which are already translated for every locale MC ships.</p>
 */
public final class DungeonTrainClientOptionsScreen extends OptionsSubScreen {

    /** One of the list's two columns. Vanilla's own small-option width. */
    private static final int ROW_W = 150;
    /** A full content row, for labels too long to share a line. */
    private static final int WIDE_W = 310;
    private static final int ROW_H = 20;
    /** Vanilla insets its button text by 2px a side; leave a little more so nothing sits flush. */
    private static final int TEXT_PADDING = 8;

    /** Ceiling ladder shared with the X-menu row: 0 = AUTO, then fixed long-edge caps. */
    private static final List<Integer> RESOLUTION_VALUES = List.of(0, 1080, 1440, 2160);

    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
    private final List<OptionsTab> tabs = new ArrayList<>();
    private TabNavigationBar tabNavigationBar;

    /** Empty on a release en_us client, which is why the Translate row is conditional. */
    private String translateTarget = "";

    public DungeonTrainClientOptionsScreen(Screen parent) {
        // ".client." because gui.dungeontrain.options.title is already taken by the WORLD options screen.
        // Minecraft.getInstance() rather than this.minecraft: the field isn't populated until init().
        super(parent, Minecraft.getInstance().options,
                Component.translatable("gui.dungeontrain.options.client.title"));
    }

    @Override
    protected void init() {
        this.translateTarget = TranslationTarget.resolveForClient();
        boolean chinese = PoliticalFilterPrefs.isChineseLocale();

        this.tabs.clear();
        for (ClientOptionsTab tab : ClientOptionsTab.values()) {
            this.tabs.add(new OptionsTab(tab,
                    ClientOptionsTab.rowsFor(tab, chinese, !this.translateTarget.isEmpty())));
        }

        this.tabNavigationBar = TabNavigationBar.builder(this.tabManager, this.width)
                .addTabs(this.tabs.toArray(new Tab[0]))
                .build();
        addRenderableWidget(this.tabNavigationBar);

        this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());
        this.layout.visitWidgets(this::addRenderableWidget);

        // Reopen (and re-init after a resize) on whichever tab the player last chose.
        this.tabNavigationBar.selectTab(ClientOptionsTab.active().ordinal(), false);
        repositionElements();
    }

    /**
     * The base class puts its single list in the content region; this screen gives each tab its own,
     * so there is nothing to add here. Rows live in {@link ClientOptionsTab#rowsFor}.
     */
    @Override
    protected void addOptions() {
        // Intentionally empty — see javadoc.
    }

    @Override
    protected void repositionElements() {
        if (this.tabNavigationBar == null) {
            return;
        }
        this.tabNavigationBar.setWidth(this.width);
        this.tabNavigationBar.arrangeElements();

        int barBottom = this.tabNavigationBar.getRectangle().bottom();
        this.layout.setHeaderHeight(barBottom);
        this.layout.arrangeElements();
        this.tabManager.setTabArea(new ScreenRectangle(0, barBottom, this.width,
                this.height - barBottom - this.layout.getFooterHeight()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Remember the tab per frame rather than on close: a window resize re-runs init(), which reads
        // the remembered tab back, so a selection only recorded at close would be lost on every resize.
        if (this.tabManager.getCurrentTab() instanceof OptionsTab current) {
            ClientOptionsTab.select(current.id);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Ctrl+Tab / Ctrl+Shift+Tab between tabs, as on every other tabbed vanilla screen.
        return this.tabNavigationBar.keyPressed(keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        // The base class only knows about its own (unused) list, so commit each tab's sliders here.
        for (OptionsTab tab : this.tabs) {
            tab.list.applyUnsavedChanges();
        }
        this.minecraft.setScreen(this.lastScreen);
    }

    /** One tab: a title on the bar, and its own scrolling list of rows. */
    private final class OptionsTab implements Tab {

        private final ClientOptionsTab id;
        private final OptionsList list;

        OptionsTab(ClientOptionsTab id, List<ClientOptionsTab.Row> rows) {
            this.id = id;
            this.list = new OptionsList(DungeonTrainClientOptionsScreen.this.minecraft,
                    DungeonTrainClientOptionsScreen.this.width, DungeonTrainClientOptionsScreen.this);
            pack(this.list, rows);
        }

        @Override
        public Component getTabTitle() {
            return Component.translatable(this.id.titleKey());
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
            consumer.accept(this.list);
        }

        @Override
        public void doLayout(ScreenRectangle rectangle) {
            this.list.updateSizeAndPosition(rectangle.width(), rectangle.height(), rectangle.top());
        }
    }

    /**
     * Fills a tab's list, pairing rows two-across but only where both actually fit.
     *
     * <p>A wide row breaks the current pair rather than joining it, so a lone narrow row ahead of it
     * takes the left column and the wide row starts its own line — no widget ever straddles another.</p>
     */
    private void pack(OptionsList list, List<ClientOptionsTab.Row> rows) {
        AbstractWidget pending = null;
        for (ClientOptionsTab.Row row : rows) {
            if (fitsNarrow(row)) {
                AbstractWidget narrow = build(row, ROW_W);
                if (pending == null) {
                    pending = narrow;
                } else {
                    list.addSmall(pending, narrow);
                    pending = null;
                }
            } else {
                if (pending != null) {
                    list.addSmall(pending, null);
                    pending = null;
                }
                list.addSmall(build(row, WIDE_W), null);
            }
        }
        if (pending != null) {
            list.addSmall(pending, null);
        }
    }

    /**
     * Whether every label this row could ever display fits a {@value #ROW_W}px column.
     *
     * <p>Measured across all of a cycler's values, not just its current one — otherwise a row would
     * pair happily on AUTO and overflow the moment the player cycled it to a longer value.</p>
     */
    private boolean fitsNarrow(ClientOptionsTab.Row row) {
        for (Component candidate : labelCandidates(row)) {
            if (this.font.width(candidate) > ROW_W - TEXT_PADDING) {
                return false;
            }
        }
        return true;
    }

    /** Every label a row can show, composed the way its widget will compose it. */
    private List<Component> labelCandidates(ClientOptionsTab.Row row) {
        return switch (row) {
            case CONTENT_MODE -> List.of(
                    value("gui.dungeontrain.options.content_mode", contentModeLabel(ContentMode.ADULT)),
                    value("gui.dungeontrain.options.content_mode", contentModeLabel(ContentMode.KID)));
            case POLITICAL_FILTER -> onOffCandidates("gui.dungeontrain.political_filter.option");
            case BOOK_AUTHOR_CHAT -> onOffCandidates("gui.dungeontrain.options.book_author_chat");
            case CINEMATIC_HOTKEY -> onOffCandidates("gui.dungeontrain.options.cinematic_hotkey");
            case SNAPSHOT_CHAT_LOG -> onOffCandidates("gui.dungeontrain.options.snapshot_chat_log");
            case TRANSLATE -> List.of(Component.translatable("gui.dungeontrain.options.translate"));
            case CUSTOM_CONTENT -> {
                List<Component> out = new ArrayList<>();
                for (CustomContentPreference pref : List.of(CustomContentPreference.ASK,
                        CustomContentPreference.CONTINUE, CustomContentPreference.DISABLE)) {
                    out.add(value("gui.dungeontrain.options.custom_content", customContentLabel(pref)));
                }
                yield out;
            }
            case SNAPSHOT_MAX_RES -> {
                List<Component> out = new ArrayList<>();
                for (int res : RESOLUTION_VALUES) {
                    out.add(value("gui.dungeontrain.options.snapshot_max_res", resolutionLabel(res)));
                }
                yield out;
            }
            // Sliders: the caption plus the widest value each can reach.
            case TRAIN_VOLUME -> List.of(
                    value("gui.dungeontrain.options.train_volume", CommonComponents.OPTION_OFF),
                    Component.translatable("options.percent_value",
                            Component.translatable("gui.dungeontrain.options.train_volume"), 100));
            case SCALE_ALL -> scaleCandidates("all_displays");
            case SCALE_WORLDSPACE -> scaleCandidates("worldspace");
            case SCALE_HUD -> scaleCandidates("hud");
        };
    }

    /** A cycler's two states, as vanilla composes them: {@code "Caption: ON"} / {@code "Caption: OFF"}. */
    private static List<Component> onOffCandidates(String captionKey) {
        return List.of(value(captionKey, CommonComponents.OPTION_ON),
                value(captionKey, CommonComponents.OPTION_OFF));
    }

    /** A scale slider's widest reading — the two-digit end of the 0.2–2.0 range. */
    private static List<Component> scaleCandidates(String channel) {
        String key = "gui.dungeontrain.editor_settings." + channel;
        return List.of(Component.translatable("gui.dungeontrain.options.value_row",
                Component.translatable(key), "2.0"));
    }

    /** {@code "Caption: Value"} exactly as a {@link CycleButton} builds its own message. */
    private static Component value(String captionKey, Component valueLabel) {
        return Options.genericValueLabel(Component.translatable(captionKey), valueLabel);
    }

    /**
     * The widget for one row, at the width {@link #pack} decided for it.
     *
     * <p>Positions are left at {@code 0,0} on purpose — {@code OptionsList} assigns every row's real
     * x/y as it renders, so setting them here would only be overwritten.</p>
     */
    private AbstractWidget build(ClientOptionsTab.Row row, int width) {
        return switch (row) {
            // Adult / Kid content mode — the second home for the choice made once on the first-launch
            // consent card, so a player who dismissed that card (or an existing install that answered
            // consent before this feature shipped, and so never sees it) can still find the setting.
            // Syncs to the server immediately so the per-player gates follow without a relog.
            case CONTENT_MODE -> withTip(
                    CycleButton.<ContentMode>builder(DungeonTrainClientOptionsScreen::contentModeLabel)
                            .withValues(List.of(ContentMode.ADULT, ContentMode.KID))
                            .withInitialValue(ClientDisplayConfig.getContentMode())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.content_mode"),
                                    (btn, mode) -> {
                                        ClientDisplayConfig.setContentMode(mode);
                                        ContentModeSyncClient.syncNow();
                                    }),
                    // The longest tooltip on the screen, and the one that most has to land: it is the
                    // only place outside the one-time card that says what Kid mode actually does, and
                    // it is where a parent comes to change it.
                    "gui.dungeontrain.options.content_mode.tip");

            // Offered only where it is a live concern (Chinese-language clients), so the row is absent
            // rather than merely inert for everyone else. Translated; see the class javadoc.
            case POLITICAL_FILTER -> withTip(
                    CycleButton.onOffBuilder(PoliticalFilterPrefs.isEnabled())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.political_filter.option"),
                                    (btn, on) -> PoliticalFilterPrefs.answer(on)),
                    "gui.dungeontrain.political_filter.option.tooltip");

            // "The book by X burns" as each DT book catches fire.
            case BOOK_AUTHOR_CHAT -> withTip(
                    CycleButton.onOffBuilder(ClientDisplayConfig.isBookAuthorBurnChatEnabled())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.book_author_chat"),
                                    (btn, on) -> ClientDisplayConfig.setBookAuthorBurnChat(on)),
                    "gui.dungeontrain.options.book_author_chat.tip");

            // The binding itself lives in vanilla Controls (Dungeon Train category); this only decides
            // whether it does anything, so a player who wants the key back for something else can free
            // it without hunting through the keybind list.
            case CINEMATIC_HOTKEY -> withTip(
                    CycleButton.onOffBuilder(ClientDisplayConfig.isCinematicHotkeyEnabled())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.cinematic_hotkey"),
                                    (btn, on) -> ClientDisplayConfig.setCinematicHotkeyEnabled(on)),
                    "gui.dungeontrain.options.cinematic_hotkey.tip");

            // Shown only when there is a language to edit — on en_us in a release build there is none
            // and the row would be a dead end; a dev build points it at the dev target instead, so the
            // editor stays testable with the UI still in English.
            case TRANSLATE -> withTip(
                    Button.builder(Component.translatable("gui.dungeontrain.options.translate"),
                                    b -> this.minecraft.setScreen(
                                            new TranslationScreen(this, this.translateTarget)))
                            .bounds(0, 0, width, ROW_H).build(),
                    "gui.dungeontrain.options.translate.tip");

            // Literally the same widget vanilla's Music & Sounds screen carries (put there by
            // SoundOptionsScreenTrainVolumeMixin) — one OptionInstance definition over one config
            // accessor, so a player who came looking here rather than there finds the same control at
            // the same value. Its tooltip is baked into the option, not set here.
            case TRAIN_VOLUME -> slider(TrainVolumeOption.forModScreen(), width);

            // The standing answer to the start-of-world prompt shown when the player has Train Editor
            // edits or an imported dtpack. This is the "can be changed in options" half of that
            // prompt's "Remember decision" checkbox.
            case CUSTOM_CONTENT -> withTip(
                    CycleButton.<CustomContentPreference>builder(
                                    DungeonTrainClientOptionsScreen::customContentLabel)
                            .withValues(List.of(CustomContentPreference.ASK, CustomContentPreference.CONTINUE,
                                    CustomContentPreference.DISABLE))
                            .withInitialValue(ClientDisplayConfig.getCustomContentPreference())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.custom_content"),
                                    (btn, pref) -> ClientDisplayConfig.setCustomContentPreference(pref)),
                    "gui.dungeontrain.options.custom_content.tip");

            // Snapshot max resolution ceiling (0 = AUTO).
            case SNAPSHOT_MAX_RES -> {
                int currentRes = ClientDisplayConfig.getRideSnapshotMaxResolution();
                yield withTip(
                        CycleButton.<Integer>builder(DungeonTrainClientOptionsScreen::resolutionLabel)
                                .withValues(RESOLUTION_VALUES)
                                .withInitialValue(RESOLUTION_VALUES.contains(currentRes) ? currentRes : 0)
                                .create(0, 0, width, ROW_H,
                                        Component.translatable("gui.dungeontrain.options.snapshot_max_res"),
                                        (btn, val) -> ClientDisplayConfig.setRideSnapshotMaxResolution(val)),
                        "gui.dungeontrain.options.snapshot_max_res.tip");
            }

            case SNAPSHOT_CHAT_LOG -> withTip(
                    CycleButton.onOffBuilder(ClientDisplayConfig.isRideSnapshotChatLogEnabled())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.snapshot_chat_log"),
                                    (btn, on) -> ClientDisplayConfig.setRideSnapshotChatLog(on)),
                    "gui.dungeontrain.options.snapshot_chat_log.tip");

            // The three display-scale channels, inlined here rather than behind a sub-screen button.
            // Their tooltips are baked into the options, not set here.
            case SCALE_ALL -> slider(DisplayScaleOption.allDisplays(), width);
            case SCALE_WORLDSPACE -> slider(DisplayScaleOption.worldspace(), width);
            case SCALE_HUD -> slider(DisplayScaleOption.hud(), width);
        };
    }

    private AbstractWidget slider(OptionInstance<Integer> option, int width) {
        return option.createButton(this.minecraft.options, 0, 0, width);
    }

    /** Attaches a word-wrapping hover tooltip from a lang key and hands the widget straight back. */
    private static <T extends AbstractWidget> T withTip(T widget, String key) {
        widget.setTooltip(Tooltip.create(Component.translatable(key)));
        return widget;
    }

    private static Component resolutionLabel(int value) {
        return value <= 0
                ? Component.translatable("gui.dungeontrain.options.snapshot_max_res.auto")
                : Component.literal(value + "p"); // "1080p" — a unit, not prose
    }

    /** ASK / CONTINUE / DISABLE, each with its own translated label. */
    private static Component customContentLabel(CustomContentPreference preference) {
        return Component.translatable("gui.dungeontrain.options.custom_content."
                + preference.name().toLowerCase(Locale.ROOT));
    }

    /** Localized name for a content mode — same keys the first-launch consent card's question uses. */
    private static Component contentModeLabel(ContentMode mode) {
        return Component.translatable(mode.isKid()
                ? "gui.dungeontrain.content_mode.kid"
                : "gui.dungeontrain.content_mode.adult");
    }
}
