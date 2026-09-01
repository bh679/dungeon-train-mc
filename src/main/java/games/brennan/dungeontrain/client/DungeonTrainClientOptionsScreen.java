package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.display.DisplayScaleOption;
import games.brennan.dungeontrain.client.localization.edit.TranslationScreen;
import games.brennan.dungeontrain.client.policy.AiPolicyScreen;
import games.brennan.dungeontrain.client.localization.edit.TranslationTarget;
import games.brennan.dungeontrain.client.sound.TrainVolumeOption;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.train.CatchUpBurstMode;
import games.brennan.dungeontrain.data.PlayerDataBackup;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.data.BackupMode;
import games.brennan.dungeontrain.config.ContentMode;
import games.brennan.dungeontrain.config.CustomContentPreference;
import games.brennan.dungeontrain.config.EditorMenuSpace;
import games.brennan.ediblebackpacks.config.EBClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.nio.file.Path;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    /** Slider bounds for "Backups per version". Kept in step with the config's own range. */
    private static final int BACKUPS_PER_VERSION_MIN = 1;
    private static final int BACKUPS_PER_VERSION_MAX = 20;


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
        // The catch-up row writes the SERVER config, which is only loaded (and only ours to
        // write) while a singleplayer world is open.
        boolean trainSettingsWritable = DungeonTrainConfig.isLoaded();

        this.tabs.clear();
        for (ClientOptionsTab tab : ClientOptionsTab.values()) {
            this.tabs.add(new OptionsTab(tab,
                    ClientOptionsTab.rowsFor(tab, chinese, !this.translateTarget.isEmpty(), trainSettingsWritable)));
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
            // A group leader never shares a line with whatever came before it, so the rows that
            // belong together read as one block instead of being split across pair boundaries.
            if (ClientOptionsTab.startsGroup(row) && pending != null) {
                list.addSmall(pending, null);
                pending = null;
            }
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
        // A caption spans the block it introduces; pairing it with a setting would read as a label
        // for that setting alone.
        if (ClientOptionsTab.isHeading(row)) {
            return false;
        }
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
            case BACKPACK_BUTTON -> onOffCandidates("gui.dungeontrain.options.backpack_button");
            // Every mode, because the row must fit its LONGEST value — the button shows the
            // caption and the value together, and "Fill all" is not the longest in every locale.
            case CATCH_UP_BURST -> List.of(
                    value("gui.dungeontrain.options.catch_up_burst", catchUpBurstLabel(CatchUpBurstMode.OFF)),
                    value("gui.dungeontrain.options.catch_up_burst", catchUpBurstLabel(CatchUpBurstMode.BURST_TWO)),
                    value("gui.dungeontrain.options.catch_up_burst", catchUpBurstLabel(CatchUpBurstMode.FILL)));
            case AI_POLICY -> List.of(Component.translatable("gui.dungeontrain.options.ai_policy"));
            case TRANSLATE -> List.of(Component.translatable("gui.dungeontrain.options.translate"));
            case CUSTOM_CONTENT -> {
                List<Component> out = new ArrayList<>();
                for (CustomContentPreference pref : List.of(CustomContentPreference.ASK,
                        CustomContentPreference.CONTINUE, CustomContentPreference.DISABLE)) {
                    out.add(value("gui.dungeontrain.options.custom_content", customContentLabel(pref)));
                }
                yield out;
            }
            case BACKUPS_HEADING -> List.of(
                    Component.translatable("gui.dungeontrain.options.backups_heading"));
            case BACKUPS_PER_VERSION -> List.of(backupsPerVersionLabel(
                    Component.translatable("gui.dungeontrain.options.backups_per_version"),
                    BACKUPS_PER_VERSION_MAX));
            case CONFIRM_BUILD_RESTORE -> onOffCandidates("gui.dungeontrain.options.confirm_build_restore");
            // The size is read at build time, so the candidate has to stand in for the widest it
            // could ever be rather than whatever it happens to be right now.
            case CLEAR_BACKUPS -> List.of(Component.translatable(
                "gui.dungeontrain.options.clear_backups", "000.0 GB"));
            case BACKUPS -> {
                List<Component> out = new ArrayList<>();
                for (BackupMode mode : BackupMode.values()) {
                    out.add(value("gui.dungeontrain.options.backups", backupModeLabel(mode)));
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
            case MENU_SPACE_COMMAND -> menuSpaceCandidates("command_menu");
            case MENU_SPACE_TEMPLATE_BLOCKS -> menuSpaceCandidates("template_blocks_menu");
            case MENU_SPACE_CONTAINER_CONTENTS -> menuSpaceCandidates("container_contents_menu");
            case MENU_SPACE_BLOCK_VARIANT -> menuSpaceCandidates("block_variant_menu");
        };
    }

    /** A cycler's two states, as vanilla composes them: {@code "Caption: ON"} / {@code "Caption: OFF"}. */
    private static List<Component> onOffCandidates(String captionKey) {
        return List.of(value(captionKey, CommonComponents.OPTION_ON),
                value(captionKey, CommonComponents.OPTION_OFF));
    }

    /** A menu-space cycler's two states — {@code "<menu>: Worldspace"} / {@code "…: Screenspace"}. */
    private static List<Component> menuSpaceCandidates(String menu) {
        String key = "gui.dungeontrain.editor_settings." + menu;
        return List.of(value(key, menuSpaceLabel(EditorMenuSpace.WORLDSPACE)),
                value(key, menuSpaceLabel(EditorMenuSpace.SCREENSPACE)));
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

            // How fast the train may re-extend once an end has fallen behind the carriages a nearby
            // player needs. Applies live to the train already running — the appender reads this at
            // the moment it decides each spawn, so there is no reload or respawn to wait for.
            case CATCH_UP_BURST -> withTip(
                    CycleButton.<CatchUpBurstMode>builder(DungeonTrainClientOptionsScreen::catchUpBurstLabel)
                            .withValues(CatchUpBurstMode.values())
                            .withInitialValue(DungeonTrainConfig.getCatchUpBurstMode())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.catch_up_burst"),
                                    (btn, mode) -> DungeonTrainConfig.setCatchUpBurstMode(mode)),
                    "gui.dungeontrain.options.catch_up_burst.tip");

            // The binding itself lives in vanilla Controls (Dungeon Train category); this only decides
            // whether it does anything, so a player who wants the key back for something else can free
            // it without hunting through the keybind list.
            case CINEMATIC_HOTKEY -> withTip(
                    CycleButton.onOffBuilder(ClientDisplayConfig.isCinematicHotkeyEnabled())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.cinematic_hotkey"),
                                    (btn, on) -> ClientDisplayConfig.setCinematicHotkeyEnabled(on)),
                    "gui.dungeontrain.options.cinematic_hotkey.tip");

            // "Was any of this made by AI?" answered in full. Unconditional, and deliberately a
            // page rather than a tooltip: the honest answer is longer than a row can carry. Also
            // reachable from the Credits page — see AiPolicyScreen.
            case AI_POLICY -> withTip(
                    Button.builder(Component.translatable("gui.dungeontrain.options.ai_policy"),
                                    b -> this.minecraft.setScreen(new AiPolicyScreen(this)))
                            .bounds(0, 0, width, ROW_H).build(),
                    "gui.dungeontrain.options.ai_policy.tip");

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

            // Where restore points of builds and progress are written. Unlike every other row here
            // the tooltip is PER VALUE, not per row: "Instanced" means nothing on its own, and the
            // whole point of the setting is the difference in what each option survives. The
            // tooltip is therefore re-set on every change as well as seeded with the initial value.
            case BACKUPS -> {
                CycleButton<BackupMode> button = CycleButton.<BackupMode>builder(
                                DungeonTrainClientOptionsScreen::backupModeLabel)
                        .withValues(List.of(BackupMode.EXTERNAL, BackupMode.INSTANCE, BackupMode.OFF))
                        .withInitialValue(ClientDisplayConfig.getBackupMode())
                        .create(0, 0, width, ROW_H,
                                Component.translatable("gui.dungeontrain.options.backups"),
                                (btn, mode) -> {
                                    ClientDisplayConfig.setBackupMode(mode);
                                    btn.setTooltip(backupModeTip(mode));
                                });
                button.setTooltip(backupModeTip(ClientDisplayConfig.getBackupMode()));
                yield button;
            }

            // The bundled Edible Backpacks' open/close button on the survival inventory screen.
            // Reads and writes EB's OWN client config rather than mirroring it into
            // ClientDisplayConfig: config/ediblebackpacks-client.toml already owns the button
            // (it also carries the anchor and custom x/y this row deliberately does not expose),
            // and EB re-reads the value every frame, so the change lands without reopening the
            // inventory. Turning it off is safe — EB's keybind still opens the panels.
            case BACKPACK_BUTTON -> withTip(
                    CycleButton.onOffBuilder(EBClientConfig.buttonEnabled())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.backpack_button"),
                                    (btn, on) -> setBackpackButtonEnabled(on)),
                    "gui.dungeontrain.options.backpack_button.tip");
            // A caption, not a control: left-aligned and unfocusable, so keyboard navigation
            // steps straight past it to the settings it introduces.
            case BACKUPS_HEADING -> {
                StringWidget heading = new StringWidget(width, ROW_H,
                        Component.translatable("gui.dungeontrain.options.backups_heading"), this.font);
                heading.alignLeft();
                yield heading;
            }

            case BACKUPS_PER_VERSION -> slider(backupsPerVersionOption(), width);

            // Off by default: a restore is the same upload the build's next save would have made, so
            // there is normally nothing to decide. On, it shows the title-screen card instead.
            case CONFIRM_BUILD_RESTORE -> withTip(
                    CycleButton.onOffBuilder(ClientDisplayConfig.isConfirmBuildRestore())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.confirm_build_restore"),
                                    (btn, on) -> ClientDisplayConfig.setConfirmBuildRestore(on)),
                    "gui.dungeontrain.options.confirm_build_restore.tip");

            case CLEAR_BACKUPS -> withTip(
                    Button.builder(
                            Component.translatable("gui.dungeontrain.options.clear_backups",
                                PlayerDataBackup.formatBytes(totalBackupBytes())),
                            b -> confirmClearBackups())
                            .bounds(0, 0, width, ROW_H).build(),
                    "gui.dungeontrain.options.clear_backups.tip");

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

            // Where each of the four editor menus (X/V/C/Z) draws itself — worldspace panel aimed by
            // the camera, or a screenspace window pointed at with a free mouse cursor. Same accessors
            // and lang keys the deleted DungeonTrainEditorSettingsScreen used; only the row's home moved.
            case MENU_SPACE_COMMAND -> menuSpaceRow("command_menu",
                    ClientDisplayConfig::getCommandMenuSpace, ClientDisplayConfig::setCommandMenuSpace, width);
            case MENU_SPACE_TEMPLATE_BLOCKS -> menuSpaceRow("template_blocks_menu",
                    ClientDisplayConfig::getTemplateBlocksMenuSpace,
                    ClientDisplayConfig::setTemplateBlocksMenuSpace, width);
            case MENU_SPACE_CONTAINER_CONTENTS -> menuSpaceRow("container_contents_menu",
                    ClientDisplayConfig::getContainerContentsMenuSpace,
                    ClientDisplayConfig::setContainerContentsMenuSpace, width);
            case MENU_SPACE_BLOCK_VARIANT -> menuSpaceRow("block_variant_menu",
                    ClientDisplayConfig::getBlockVariantMenuSpace,
                    ClientDisplayConfig::setBlockVariantMenuSpace, width);
        };
    }

    /**
     * Writes Edible Backpacks' {@code buttonEnabled} through to
     * {@code config/ediblebackpacks-client.toml}.
     *
     * <p>The {@code isLoaded} guard mirrors EB's own readers: this screen is reachable from the
     * title screen, where the spec may not have loaded yet, and {@code set} on an unloaded spec
     * throws. Nothing is lost by skipping — the row is showing the default in that case.</p>
     */
    private static void setBackpackButtonEnabled(boolean enabled) {
        if (!EBClientConfig.SPEC.isLoaded()) {
            return;
        }
        EBClientConfig.BUTTON_ENABLED.set(enabled);
        EBClientConfig.SPEC.save();
    }

    private AbstractWidget slider(OptionInstance<Integer> option, int width) {
        return option.createButton(this.minecraft.options, 0, 0, width);
    }

    /** A {@code [label: Worldspace|Screenspace]} cycle row for one editor menu. */
    private AbstractWidget menuSpaceRow(String menu, Supplier<EditorMenuSpace> get,
            Consumer<EditorMenuSpace> set, int width) {
        String key = "gui.dungeontrain.editor_settings." + menu;
        return withTip(
                CycleButton.<EditorMenuSpace>builder(DungeonTrainClientOptionsScreen::menuSpaceLabel)
                        .withValues(EditorMenuSpace.values())
                        .withInitialValue(get.get())
                        .create(0, 0, width, ROW_H, Component.translatable(key), (btn, val) -> set.accept(val)),
                key + ".tip");
    }

    /** Localized name for a menu space — the enum constant is a config token, not player-facing prose. */
    private static Component menuSpaceLabel(EditorMenuSpace space) {
        return Component.translatable("gui.dungeontrain.menu_space." + space.name().toLowerCase(Locale.ROOT));
    }

    /** Attaches a word-wrapping hover tooltip from a lang key and hands the widget straight back. */
    private static Component catchUpBurstLabel(CatchUpBurstMode mode) {
        return Component.translatable("gui.dungeontrain.options.catch_up_burst."
                + mode.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static <T extends AbstractWidget> T withTip(T widget, String key) {
        widget.setTooltip(Tooltip.create(Component.translatable(key)));
        return widget;
    }

    private static Component resolutionLabel(int value) {
        return value <= 0
                ? Component.translatable("gui.dungeontrain.options.snapshot_max_res.auto")
                : Component.literal(value + "p"); // "1080p" — a unit, not prose
    }

    /**
     * The "Backups per version" slider, built like {@code DisplayScaleOption}: the stored value is
     * read once, at construction, which is right because rows are built in {@code init()}.
     *
     * <p>The value commits through {@link ClientDisplayConfig#setBackupsPerVersion} — on release,
     * and again via {@code applyUnsavedChanges()} when the screen closes, which {@link #onClose()}
     * already calls for every tab.</p>
     */
    private static OptionInstance<Integer> backupsPerVersionOption() {
        String key = "gui.dungeontrain.options.backups_per_version";
        return new OptionInstance<>(
                key,
                OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tip")),
                DungeonTrainClientOptionsScreen::backupsPerVersionLabel,
                new OptionInstance.IntRange(BACKUPS_PER_VERSION_MIN, BACKUPS_PER_VERSION_MAX),
                Mth.clamp(ClientDisplayConfig.getBackupsPerVersion(),
                        BACKUPS_PER_VERSION_MIN, BACKUPS_PER_VERSION_MAX),
                ClientDisplayConfig::setBackupsPerVersion);
    }

    /** {@code "Backups per version: 5"}, through the shared caption/value pattern. */
    private static Component backupsPerVersionLabel(Component caption, int perVersion) {
        return Component.translatable("gui.dungeontrain.options.value_row",
                caption, Integer.toString(perVersion));
    }

    /** Bytes held by archives in BOTH roots — the figure the Clear button reports. */
    private static long totalBackupBytes() {
        long total = PlayerDataBackup.totalSize(PlayerDataPaths.backupsRoot());
        return total + PlayerDataPaths.externalBackupsRoot()
            .map(PlayerDataBackup::totalSize).orElse(0L);
    }

    /**
     * Ask before deleting, then delete from both roots.
     *
     * <p>The message names the out-of-instance folder explicitly. "Clear all backups" that quietly
     * spared a folder the player cannot see would be the worse surprise of the two, and this is the
     * only place that folder is ever surfaced.</p>
     *
     * <p>The label carries the size, so it has to be rebuilt afterwards. Returning to this screen is
     * NOT enough on its own: {@code Screen.init(Minecraft, int, int)} only calls {@code init()} the
     * first time and merely repositions an already-initialised screen, so the button kept reporting
     * the space it had just freed. {@link #rebuildWidgets()} is the call that actually re-runs
     * {@code init()}, and it happens after the screen is current again.</p>
     */
    private void confirmClearBackups() {
        Path inside = PlayerDataPaths.backupsRoot();
        Optional<Path> outside = PlayerDataPaths.externalBackupsRoot();
        int count = PlayerDataBackup.listArchives(inside).size()
            + outside.map(p -> PlayerDataBackup.listArchives(p).size()).orElse(0);
        Component where = outside
            .map(p -> (Component) Component.translatable(
                "gui.dungeontrain.options.clear_backups.confirm.both", inside.toString(), p.toString()))
            .orElseGet(() -> Component.translatable(
                "gui.dungeontrain.options.clear_backups.confirm.one", inside.toString()));
        this.minecraft.setScreen(new ConfirmScreen(
                proceed -> {
                    this.minecraft.setScreen(this);
                    if (!proceed) return;
                    PlayerDataBackup.clear(inside);
                    outside.ifPresent(PlayerDataBackup::clear);
                    // Re-run init() so the button re-reads the (now zero) size on disk.
                    rebuildWidgets();
                },
                Component.translatable("gui.dungeontrain.options.clear_backups.confirm.title", count),
                where,
                Component.translatable("gui.dungeontrain.options.clear_backups.confirm.yes"),
                CommonComponents.GUI_CANCEL));
    }

    /** On / Instanced / Off, each with its own translated label. */
    private static Component backupModeLabel(BackupMode mode) {
        return Component.translatable("gui.dungeontrain.options.backups."
                + mode.name().toLowerCase(Locale.ROOT));
    }

    /** What the currently-selected backup mode actually protects against. */
    private static Tooltip backupModeTip(BackupMode mode) {
        return Tooltip.create(Component.translatable("gui.dungeontrain.options.backups."
                + mode.name().toLowerCase(Locale.ROOT) + ".tip"));
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
