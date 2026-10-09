package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.FreePlayText;
import games.brennan.dungeontrain.client.bugresponse.BugIssue;
import games.brennan.dungeontrain.client.bugresponse.BugResponse;
import games.brennan.dungeontrain.client.bugresponse.BugResponseCard;
import games.brennan.dungeontrain.client.bugresponse.LagTips;
import games.brennan.dungeontrain.client.credits.CommunityLinkClient;
import games.brennan.dungeontrain.client.credits.DiscordAccountState;
import games.brennan.dungeontrain.client.credits.DiscordLinkScreen;
import games.brennan.dungeontrain.client.version.compare.VersionCompareScreen;
import games.brennan.dungeontrain.client.display.DisplayScaleOption;
import games.brennan.dungeontrain.client.localization.edit.TranslationScreen;
import games.brennan.dungeontrain.client.policy.AiPolicyScreen;
import games.brennan.dungeontrain.client.localization.edit.TranslationTarget;
import games.brennan.dungeontrain.client.sound.TrainVolumeOption;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.train.CatchUpBurstAuto;
import games.brennan.dungeontrain.train.CatchUpBurstMode;
import games.brennan.dungeonbackup.client.BackupOptionsWidgets;
import games.brennan.dungeontrain.config.ContentMode;
import games.brennan.dungeontrain.config.CustomContentPreference;
import games.brennan.dungeontrain.config.EditorMenuSpace;
import games.brennan.dungeontrain.discord.PingType;
import games.brennan.ediblebackpacks.config.EBClientConfig;
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
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * Ceiling ladder shared with the X-menu row: 0 = AUTO, then fixed long-edge caps. 720 is the rung the
     * Performance tab's "Lower resolution" sets, so it must be here or the row would misreport it as AUTO.
     */
    private static final List<Integer> RESOLUTION_VALUES = List.of(0, 720, 1080, 1440, 2160);

    /** Slider bounds for "Backups per version". Kept in step with the config's own range. */
    private static final int BACKUPS_PER_VERSION_MIN = 1;
    private static final int BACKUPS_PER_VERSION_MAX = 20;


    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
    private final List<OptionsTab> tabs = new ArrayList<>();
    private TabNavigationBar tabNavigationBar;

    /** Empty on a release en_us client, which is why the Translate row is conditional. */
    private String translateTarget = "";

    /**
     * Whether this screen has asked the relay for the Discord link yet. Per screen instance, and it
     * survives {@code rebuildWidgets()} — the answer's own rebuild must not ask again.
     */
    private boolean accountRequested = false;

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
        // The catch-up row writes ONE global value in the COMMON config, which is loaded from
        // mod construction — so it is editable with no world open, and the title screen sets the
        // same value a world does. Hidden only on a multiplayer client, where the value that
        // counts is the server's and our write would change nothing they can see.
        Minecraft mc = Minecraft.getInstance();
        boolean trainSettingsWritable = mc.level == null || mc.hasSingleplayerServer();

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
            if (row == ClientOptionsTab.Row.PERFORMANCE_TIPS) {
                if (pending != null) {
                    list.addSmall(pending, null);
                    pending = null;
                }
                addPerformanceTips(list);
                continue;
            }
            if (row == ClientOptionsTab.Row.DISCORD_ACCOUNT) {
                if (pending != null) {
                    list.addSmall(pending, null);
                    pending = null;
                }
                addDiscordAccount(list);
                continue;
            }
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
     * The Performance tab: every lag tip the death screen's bug-report card can offer, worded for this
     * setup. The ones that apply come first under their own caption; the rest follow dimmed under
     * "Other performance settings", still with their buttons, so every setting that costs frames can be
     * found here even when nothing needs fixing. When the player is behind, a last line says by how
     * many releases, with the card's See changes button.
     *
     * <p>Every screen a tip's button opens returns here, and returning re-runs {@code init()}, so a fixed
     * tip moves down to the other group; the one action that stays on this screen (lowering the photo
     * resolution) rebuilds it.</p>
     */
    private void addPerformanceTips(OptionsList list) {
        List<LagTips.Tip> tips = LagTips.all(this);
        List<LagTips.Tip> worth = tips.stream().filter(LagTips.Tip::applies).toList();
        List<LagTips.Tip> other = tips.stream().filter(t -> !t.applies()).toList();

        list.addSmall(PerformanceTipRow.caption(this.font, WIDE_W, ROW_H, Component.translatable(
                worth.isEmpty() ? "gui.dungeontrain.options.performance.none"
                        : "gui.dungeontrain.options.performance.intro")), null);
        for (LagTips.Tip tip : worth) {
            list.addSmall(PerformanceTipRow.tip(this.font, WIDE_W, ROW_H, tip, this::rebuildIfShown), null);
        }
        if (!other.isEmpty()) {
            list.addSmall(PerformanceTipRow.caption(this.font, WIDE_W, ROW_H,
                    Component.translatable("gui.dungeontrain.options.performance.other")), null);
            for (LagTips.Tip tip : other) {
                list.addSmall(PerformanceTipRow.tip(this.font, WIDE_W, ROW_H, tip, this::rebuildIfShown), null);
            }
        }

        // Read from whatever release listings have arrived; the title screen starts that fetch, so by
        // the time a player is in Options it has usually landed. A miss just leaves the line out.
        Minecraft mc = Minecraft.getInstance();
        boolean multiplayer = mc.level != null && !mc.hasSingleplayerServer();
        BugResponse.Result versions = BugResponseCard.decideNow(BugIssue.LAG, multiplayer);
        if (versions.behind()) {
            list.addSmall(PerformanceTipRow.action(this.font, WIDE_W, ROW_H, BugResponseCard.outdated(versions),
                    Component.translatable("gui.dungeontrain.bug_response.button.changes"),
                    () -> mc.setScreen(new VersionCompareScreen(this))), null);
        }
    }

    /**
     * The Account tab: the player's Discord link as the relay last reported it ({@link DiscordAccountState}).
     * Not linked → a line saying what linking does and a Link button (the Credits link screen). Linked →
     * the master "All Discord pings" switch, then one toggle per {@link PingType}, greyed while the master
     * is off. Every toggle writes straight to the relay and the tab redraws from its answer, so a failed
     * write puts the toggle back and says so.
     *
     * <p>The first draw asks the relay once; its answer rebuilds the screen.</p>
     */
    private void addDiscordAccount(OptionsList list) {
        if (!this.accountRequested) {
            this.accountRequested = true;
            DiscordAccountState.refresh(this::rebuildIfShown);
        }
        CommunityLinkClient.Status status = DiscordAccountState.status();
        if (status == null) {
            if (DiscordAccountState.phase() == DiscordAccountState.Phase.FAILED) {
                CommunityLinkClient.Error error = DiscordAccountState.error();
                Component why = Component.translatable(error == CommunityLinkClient.Error.NO_CONSENT
                        ? "gui.dungeontrain.credits.link.no_consent" : "gui.dungeontrain.credits.link.failed");
                list.addSmall(PerformanceTipRow.action(this.font, WIDE_W, ROW_H, why,
                        Component.translatable("gui.dungeontrain.options.account.retry"), () -> {
                            this.accountRequested = false;
                            DiscordAccountState.invalidate();
                            rebuildIfShown();
                        }), null);
            } else {
                list.addSmall(PerformanceTipRow.caption(this.font, WIDE_W, ROW_H,
                        Component.translatable("gui.dungeontrain.options.account.checking")), null);
            }
            return;
        }
        // Who is linked to whom: the Minecraft account signed in here, and the Discord account the
        // relay has it tied to — so a player with two Discords can see which one gets the pings.
        // Side by side on one row: the two halves of the one link.
        list.addSmall(PerformanceTipRow.caption(this.font, ROW_W, ROW_H, Component.translatable(
                        "gui.dungeontrain.options.account.minecraft", this.minecraft.getUser().getName())),
                PerformanceTipRow.caption(this.font, ROW_W, ROW_H, !status.linked()
                        ? Component.translatable("gui.dungeontrain.options.account.discord_none")
                        : status.discordName().isEmpty()
                                ? Component.translatable("gui.dungeontrain.options.account.discord_linked")
                                : Component.translatable("gui.dungeontrain.options.account.discord", status.discordName())));
        if (!status.linked()) {
            // What linking does on its own line, then the button across the full width — the one
            // thing to do on this tab until the account is linked.
            list.addSmall(PerformanceTipRow.caption(this.font, WIDE_W, ROW_H,
                    Component.translatable("gui.dungeontrain.options.account.not_linked")), null);
            list.addSmall(withTip(Button.builder(Component.translatable("gui.dungeontrain.credits.community.link_action"),
                                    b -> {
                                        // Coming back re-runs init(); ask again then, so a fresh link shows at once.
                                        this.accountRequested = false;
                                        DiscordAccountState.invalidate();
                                        this.minecraft.setScreen(new DiscordLinkScreen(this, null));
                                    })
                            .bounds(0, 0, WIDE_W, ROW_H).build(),
                    "gui.dungeontrain.options.account.not_linked"), null);
            return;
        }
        list.addSmall(PerformanceTipRow.caption(this.font, WIDE_W, ROW_H,
                Component.translatable("gui.dungeontrain.options.account.linked")), null);
        if (DiscordAccountState.toggleFailed()) {
            list.addSmall(PerformanceTipRow.caption(this.font, WIDE_W, ROW_H,
                    Component.translatable("gui.dungeontrain.options.account.toggle_failed")), null);
        }
        list.addSmall(withTip(CycleButton.onOffBuilder(status.pings())
                        .create(0, 0, WIDE_W, ROW_H, Component.translatable("gui.dungeontrain.options.account.pings_all"),
                                (btn, on) -> DiscordAccountState.setMaster(on, this::rebuildIfShown)),
                "gui.dungeontrain.options.account.pings_all.tip"), null);

        boolean narrow = true;
        for (PingType type : PingType.values()) {
            for (Component c : onOffCandidates(pingKey(type))) {
                if (this.font.width(c) > ROW_W - TEXT_PADDING) narrow = false;
            }
        }
        AbstractWidget pending = null;
        for (PingType type : PingType.values()) {
            CycleButton<Boolean> toggle = CycleButton.onOffBuilder(status.types().getOrDefault(type, true))
                    .create(0, 0, narrow ? ROW_W : WIDE_W, ROW_H, Component.translatable(pingKey(type)),
                            (btn, on) -> DiscordAccountState.setType(type, on, this::rebuildIfShown));
            toggle.active = status.pings();
            withTip(toggle, pingKey(type) + ".tip");
            if (!narrow) {
                list.addSmall(toggle, null);
            } else if (pending == null) {
                pending = toggle;
            } else {
                list.addSmall(pending, toggle);
                pending = null;
            }
        }
        if (pending != null) list.addSmall(pending, null);
    }

    private static String pingKey(PingType type) {
        return "gui.dungeontrain.options.account.pings." + type.key();
    }

    /** Rebuilds after a tip's action, unless that action already moved on to another screen. */
    private void rebuildIfShown() {
        if (this.minecraft.screen == this) {
            rebuildWidgets();
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
            case UPDATE_NOTICE_CHAT -> onOffCandidates("gui.dungeontrain.options.update_notice_chat");
            case LOW_MEMORY_NOTICE_CHAT -> onOffCandidates("gui.dungeontrain.options.low_memory_notice_chat");
            case OTHER_ADVANCEMENT_TABS -> onOffCandidates("gui.dungeontrain.options.other_advancement_tabs");
            case CINEMATIC_HOTKEY -> onOffCandidates("gui.dungeontrain.options.cinematic_hotkey");
            case SNAPSHOT_CHAT_LOG -> onOffCandidates("gui.dungeontrain.options.snapshot_chat_log");
            case BACKPACK_BUTTON -> onOffCandidates("gui.dungeontrain.options.backpack_button");
            // Every mode, because the row must fit its LONGEST value — the button shows the
            // caption and the value together, and "Fill all" is not the longest in every locale.
            // AUTO is the longest of all: it names the mode it resolved to inside its own label.
            case CATCH_UP_BURST -> Arrays.stream(CatchUpBurstMode.values())
                    .map(m -> value("gui.dungeontrain.options.catch_up_burst", catchUpBurstLabel(m)))
                    .toList();
            case AI_POLICY -> List.of(Component.translatable("gui.dungeontrain.options.ai_policy"));
            case TRANSLATE -> List.of(Component.translatable("gui.dungeontrain.options.translate"));
            case CUSTOM_CONTENT -> {
                List<Component> out = new ArrayList<>();
                for (CustomContentPreference pref : customContentValues()) {
                    out.add(value("gui.dungeontrain.options.custom_content", customContentLabel(pref)));
                }
                yield out;
            }
            case BACKUPS_PER_VERSION -> List.of(BackupOptionsWidgets.perVersionWidestLabel());
            case CONFIRM_BUILD_RESTORE -> onOffCandidates("gui.dungeontrain.options.confirm_build_restore");
            // The size is read at build time, so the candidate has to stand in for the widest it
            // could ever be rather than whatever it happens to be right now.
            case CLEAR_BACKUPS -> List.of(BackupOptionsWidgets.clearWidestLabel());
            case BACKUPS -> BackupOptionsWidgets.modeLabels();
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
            case CREATIVE_MOD_BLOCK_TABS -> onOffCandidates("gui.dungeontrain.editor_settings.mod_block_tabs");
            case CREATIVE_MOD_BLOCKS_IN_SEARCH ->
                    onOffCandidates("gui.dungeontrain.editor_settings.mod_blocks_in_search");
            // Never measured: pack() expands it into full-width tip rows before asking.
            case PERFORMANCE_TIPS, DISCORD_ACCOUNT -> List.of();
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

            // "Dungeon Train X is out" when a real release lands mid-session.
            case UPDATE_NOTICE_CHAT -> withTip(
                    CycleButton.onOffBuilder(ClientDisplayConfig.isUpdateNoticeChatEnabled())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.update_notice_chat"),
                                    (btn, on) -> ClientDisplayConfig.setUpdateNoticeChat(on)),
                    "gui.dungeontrain.options.update_notice_chat.tip");

            // "Minecraft only has N GB of memory" — title-screen card + chat line, once a session each.
            case LOW_MEMORY_NOTICE_CHAT -> withTip(
                    CycleButton.onOffBuilder(ClientDisplayConfig.isLowMemoryNoticeChatEnabled())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.low_memory_notice_chat"),
                                    (btn, on) -> ClientDisplayConfig.setLowMemoryNoticeChat(on)),
                    "gui.dungeontrain.options.low_memory_notice_chat.tip");

            // Minecraft's and other mods' advancement tabs. ON shows them; the config stores the inverse
            // (hideOtherTabs) because hiding is the default.
            case OTHER_ADVANCEMENT_TABS -> withTip(
                    CycleButton.onOffBuilder(!ClientDisplayConfig.isHideOtherAdvancementTabs())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.other_advancement_tabs"),
                                    (btn, on) -> ClientDisplayConfig.setHideOtherAdvancementTabs(!on)),
                    "gui.dungeontrain.options.other_advancement_tabs.tip");

            // How fast the train may re-extend once an end has fallen behind the carriages a nearby
            // player needs. One global value, not a per-world one — set it here or at the title
            // screen and every world follows. Applies live to the train already running: the
            // appender reads it at the moment it decides each spawn, so nothing waits on a reload.
            case CATCH_UP_BURST -> withTip(
                    CycleButton.<CatchUpBurstMode>builder(DungeonTrainClientOptionsScreen::catchUpBurstLabel)
                            .withValues(CatchUpBurstMode.values())
                            .withInitialValue(DungeonTrainCommonConfig.getCatchUpBurstMode())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.catch_up_burst"),
                                    (btn, mode) -> DungeonTrainCommonConfig.setCatchUpBurstMode(mode)),
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
                            .withValues(customContentValues())
                            .withInitialValue(customContentInitial())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.custom_content"),
                                    (btn, pref) -> ClientDisplayConfig.setCustomContentPreference(pref)),
                    FreePlayText.withExplanation("gui.dungeontrain.options.custom_content.tip"));

            // The three backup controls are Dungeon Backup's: it owns the setting
            // (config/dungeonbackup-client.toml) and the archives, so it builds the widgets; this
            // screen only hosts them on the Backups tab.
            case BACKUPS -> BackupOptionsWidgets.modeButton(width, ROW_H);

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
            case BACKUPS_PER_VERSION -> BackupOptionsWidgets.perVersionSlider(width);

            // Off by default: a restore is the same upload the build's next save would have made, so
            // there is normally nothing to decide. On, it shows the title-screen card instead.
            case CONFIRM_BUILD_RESTORE -> withTip(
                    CycleButton.onOffBuilder(ClientDisplayConfig.isConfirmBuildRestore())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.confirm_build_restore"),
                                    (btn, on) -> ClientDisplayConfig.setConfirmBuildRestore(on)),
                    "gui.dungeontrain.options.confirm_build_restore.tip");

            // The label carries the size on disk, so after a clear the widgets are rebuilt —
            // rebuildWidgets() is what re-runs init(); merely returning to the screen only
            // repositions it and the button would keep reporting the space it just freed.
            case CLEAR_BACKUPS -> BackupOptionsWidgets.clearButton(width, ROW_H, this, this::rebuildWidgets);

            // Snapshot max resolution ceiling (0 = AUTO).
            case SNAPSHOT_MAX_RES -> {
                int currentRes = ClientDisplayConfig.getRideSnapshotMaxResolution();
                yield withTip(
                        CycleButton.<Integer>builder(DungeonTrainClientOptionsScreen::resolutionLabel)
                                .withValues(resolutionValues(currentRes))
                                .withInitialValue(Math.max(0, currentRes))
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

            // The biome mods' creative tabs and their search entries, both hidden by default. The
            // tabs are rebuilt on the spot so the change shows without a relog.
            case CREATIVE_MOD_BLOCK_TABS -> withTip(
                    CycleButton.onOffBuilder(ClientDisplayConfig.isCreativeModBlockTabs())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.editor_settings.mod_block_tabs"),
                                    (btn, on) -> {
                                        ClientDisplayConfig.setCreativeModBlockTabs(on);
                                        CreativeTabRefresh.rebuild();
                                    }),
                    "gui.dungeontrain.editor_settings.mod_block_tabs.tip");
            case CREATIVE_MOD_BLOCKS_IN_SEARCH -> withTip(
                    CycleButton.onOffBuilder(ClientDisplayConfig.isCreativeModBlocksInSearch())
                            .create(0, 0, width, ROW_H,
                                    Component.translatable("gui.dungeontrain.editor_settings.mod_blocks_in_search"),
                                    (btn, on) -> {
                                        ClientDisplayConfig.setCreativeModBlocksInSearch(on);
                                        CreativeTabRefresh.rebuild();
                                    }),
                    "gui.dungeontrain.editor_settings.mod_blocks_in_search.tip");

            // Expanded by pack() into several rows; there is no single widget for it.
            case PERFORMANCE_TIPS -> throw new IllegalStateException("PERFORMANCE_TIPS is packed, not built");
            case DISCORD_ACCOUNT -> throw new IllegalStateException("DISCORD_ACCOUNT is packed, not built");
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

    /**
     * The label for one catch-up mode. AUTO names what it actually resolved to — "Automatic (Fill
     * all)" — because "Automatic" alone tells a player nothing about what their train will do, and
     * the resolution is available here: in singleplayer it is this same JVM.
     */
    private static Component catchUpBurstLabel(CatchUpBurstMode mode) {
        if (mode == CatchUpBurstMode.AUTO) {
            return Component.translatable("gui.dungeontrain.options.catch_up_burst.auto",
                    Component.translatable("gui.dungeontrain.options.catch_up_burst."
                            + CatchUpBurstAuto.machineMode().name().toLowerCase(Locale.ROOT)));
        }
        return Component.translatable("gui.dungeontrain.options.catch_up_burst."
                + mode.name().toLowerCase(Locale.ROOT));
    }

    private static <T extends AbstractWidget> T withTip(T widget, String key) {
        return withTip(widget, Component.translatable(key));
    }

    private static <T extends AbstractWidget> T withTip(T widget, Component tip) {
        widget.setTooltip(Tooltip.create(tip));
        return widget;
    }

    /**
     * The ladder, plus the stored value when it is off the ladder (a hand-edited toml), so the row shows
     * what is actually set instead of AUTO — and cycling past it never silently discards it until chosen.
     */
    private static List<Integer> resolutionValues(int current) {
        if (current <= 0 || RESOLUTION_VALUES.contains(current)) {
            return RESOLUTION_VALUES;
        }
        List<Integer> values = new ArrayList<>(RESOLUTION_VALUES);
        values.add(current);
        values.sort(null);
        return List.copyOf(values);
    }

    private static Component resolutionLabel(int value) {
        return value <= 0
                ? Component.translatable("gui.dungeontrain.options.snapshot_max_res.auto")
                : Component.literal(value + "p"); // "1080p" — a unit, not prose
    }

    /** ASK / CONTINUE / DISABLE, each with its own translated label. */
    /**
     * The standing answers on offer. The dev waiver is listed on dev builds only — a release
     * build neither shows nor honours it.
     */
    private static List<CustomContentPreference> customContentValues() {
        return DungeonTrain.isDevBuild()
            ? List.of(CustomContentPreference.ASK, CustomContentPreference.CONTINUE,
                CustomContentPreference.DISABLE, CustomContentPreference.DEV_IGNORE)
            : List.of(CustomContentPreference.ASK, CustomContentPreference.CONTINUE,
                CustomContentPreference.DISABLE);
    }

    /**
     * A config written on a dev build can hold DEV_IGNORE on a release build, where the cycle
     * button has no such value to show; present it as CONTINUE, which is what it plays as.
     */
    private static CustomContentPreference customContentInitial() {
        CustomContentPreference stored = ClientDisplayConfig.getCustomContentPreference();
        return customContentValues().contains(stored) ? stored : CustomContentPreference.CONTINUE;
    }

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
