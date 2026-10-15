package games.brennan.dungeontrain.client;

import games.brennan.discordpresence.client.NetworkConsentScreen;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.bugresponse.BugIssue;
import games.brennan.dungeontrain.client.bugresponse.BugResponse;
import games.brennan.dungeontrain.client.bugresponse.BugResponseCard;
import games.brennan.dungeontrain.client.bugresponse.LagTips;
import games.brennan.dungeontrain.client.credits.CommunityLinkClient;
import games.brennan.dungeontrain.client.credits.DiscordAccountState;
import games.brennan.dungeontrain.client.credits.DiscordLinkScreen;
import games.brennan.dungeontrain.client.credits.DonationPrefsClient;
import games.brennan.dungeontrain.client.credits.DonationPrefsState;
import games.brennan.dungeontrain.client.display.DisplayScaleOption;
import games.brennan.dungeontrain.client.live.StreamNameTag;
import games.brennan.dungeontrain.client.live.StreamPreviewWidget;
import games.brennan.dungeontrain.client.localization.edit.TranslationScreen;
import games.brennan.dungeontrain.client.localization.edit.TranslationTarget;
import games.brennan.dungeontrain.client.policy.AiPolicyScreen;
import games.brennan.dungeontrain.client.sound.TrainVolumeOption;
import games.brennan.dungeontrain.client.version.compare.VersionCompareScreen;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.ContentMode;
import games.brennan.dungeontrain.config.CustomContentPreference;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.EditorMenuSpace;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.discord.PingType;
import games.brennan.dungeontrain.train.CatchUpBurstAuto;
import games.brennan.dungeontrain.train.CatchUpBurstMode;
import games.brennan.dungeonbackup.api.BackupSettings;
import games.brennan.dungeonbackup.client.BackupOptionsWidgets;
import games.brennan.dungeonbackup.core.BackupArchiver;
import games.brennan.dungeonbackup.core.BackupMode;
import games.brennan.ediblebackpacks.client.ClientAutoCollect;
import games.brennan.ediblebackpacks.config.AutoCollectMode;
import games.brennan.ediblebackpacks.config.EBClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

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
 * tab owns a scrolling {@link SettingsRowList} sized to whatever height is left. Add rows through
 * {@link ClientOptionsTab}; never position widgets by hand here again.</p>
 *
 * <p><b>Every row is one line of the settings list:</b> an icon ({@link ClientOptionsIcons}), the
 * setting's name, a one-line grey hint ({@code <captionKey>.hint}) and, at the right, a control that
 * shows only its value. The rows used to be 150px buttons packed two across, each carrying its whole
 * meaning in its own label ("Book Author Chat: OFF"); ten of those to a tab was a wall of text that
 * had to be read before it could be scanned. The long explanation each button used to hold as its
 * tooltip is now the whole row's tooltip.</p>
 *
 * <p><b>Every row here is localized</b> — names, hints and tooltips alike — and new rows must follow suit
 * rather than reaching for {@link Component#literal}. This screen used to be plain-English literals
 * with the content-mode and Political Filter rows as localized exceptions; a screenshot of a Chinese
 * client settled it, because the exceptions are the reason the rest has to be translated too. The
 * consent flow asks a player for the Kid-mode and Chinese-filter answers <em>in their own language</em>,
 * then sends them here to change them: arriving at a screen where two rows are Chinese and four are
 * English is worse than either extreme. The on/off states come from vanilla's own {@code options.on} /
 * {@code options.off}, which are already translated for every locale MC ships.</p>
 */
public final class DungeonTrainClientOptionsScreen extends OptionsSubScreen {

    private static final int ROW_W = SettingsRowList.ROW_W;
    private static final int ROW_H = SettingsRowList.CONTROL_H;
    private static final int CONTROL_W = SettingsRowList.CONTROL_W;

    /**
     * Ceiling ladder shared with the X-menu row: 0 = AUTO, then fixed long-edge caps. 720 is the rung the
     * Performance tab's "Lower resolution" sets, so it must be here or the row would misreport it as AUTO.
     */
    private static final List<Integer> RESOLUTION_VALUES = List.of(0, 720, 1080, 1440, 2160);

    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
    private final List<OptionsTab> tabs = new ArrayList<>();
    private TabNavigationBar tabNavigationBar;
    /**
     * Added to {@link #layout}'s footer once. The layout is a per-screen field that outlives
     * {@code rebuildWidgets()}, and {@code HeaderAndFooterLayout} has no remove, so adding a fresh
     * button on every {@code init()} stacked one Done per rebuild.
     */
    private Button doneButton;

    /** Empty on a release en_us client, which is why the Translate row is conditional. */
    private String translateTarget = "";

    /**
     * Whether this screen has asked the relay for the Discord link yet. Per screen instance, and it
     * survives {@code rebuildWidgets()} — the answer's own rebuild must not ask again.
     */
    private boolean accountRequested = false;
    /**
     * Set when this screen opens a child that can change what it shows (the consent card, the Discord
     * link screen): run when this screen is shown again, then rebuild. Showing a screen again does NOT
     * re-run {@code init()} in 1.21 — {@code Screen#init} only repositions an already-initialised
     * screen — so {@link #added()} rebuilds instead.
     */
    @Nullable private Runnable onReturn;
    private boolean donationPrefsRequested = false;

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
                    ClientOptionsTab.rowsFor(tab, chinese, !this.translateTarget.isEmpty(), trainSettingsWritable,
                            DungeonTrain.isDevBuild())));
        }

        this.tabNavigationBar = TabNavigationBar.builder(this.tabManager, this.width)
                .addTabs(this.tabs.toArray(new Tab[0]))
                .build();
        addRenderableWidget(this.tabNavigationBar);

        if (this.doneButton == null) {
            this.doneButton = Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build();
            this.layout.addToFooter(this.doneButton);
        }
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
    public void added() {
        super.added();
        if (this.onReturn != null) {
            Runnable run = this.onReturn;
            this.onReturn = null;
            run.run();
            rebuildWidgets();
        }
    }

    /** Open {@code child} over this screen; when it comes back, run {@code onReturn} and rebuild. */
    private void openChild(Screen child, Runnable onReturn) {
        this.onReturn = onReturn;
        this.minecraft.setScreen(child);
    }

    /**
     * Forget the Account tab's answer so its next draw asks the relay again. Run when a child screen
     * closes, not when it opens: an answer that lands while the child is up was asked under the old
     * settings, and would otherwise be kept.
     */
    private void forgetAccount() {
        this.accountRequested = false;
        DiscordAccountState.invalidate();
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

        /** The list's top and bottom padding around its rows. */
        private static final int LIST_PAD = 8;
        private static final int PREVIEW_MARGIN = 20;

        private final ClientOptionsTab id;
        private final SettingsRowList list;
        /** Stream tab only: what viewers would see, under the rows that change it. */
        @Nullable private final StreamPreviewWidget preview;

        OptionsTab(ClientOptionsTab id, List<ClientOptionsTab.Row> rows) {
            this.id = id;
            this.list = new SettingsRowList(DungeonTrainClientOptionsScreen.this.minecraft,
                    DungeonTrainClientOptionsScreen.this.width, 0, 0);
            for (ClientOptionsTab.Row row : rows) {
                addRow(this.list, row);
            }
            this.preview = id == ClientOptionsTab.STREAM ? new StreamPreviewWidget() : null;
        }

        @Override
        public Component getTabTitle() {
            return Component.translatable(this.id.titleKey());
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
            consumer.accept(this.list);
            if (this.preview != null) consumer.accept(this.preview);
        }

        @Override
        public void doLayout(ScreenRectangle rectangle) {
            if (this.preview == null) {
                this.list.updateSizeAndPosition(rectangle.width(), rectangle.height(), rectangle.top());
                return;
            }
            // The list shrinks to its rows; the preview takes the rest (the widget hides itself
            // when that is too small to show a picture).
            int listH = Math.min(rectangle.height(), this.list.rowCount() * SettingsRowList.ROW_H + LIST_PAD);
            this.list.updateSizeAndPosition(rectangle.width(), listH, rectangle.top());
            this.preview.setX(rectangle.left() + PREVIEW_MARGIN);
            this.preview.setY(rectangle.top() + listH + 4);
            this.preview.setWidth(Math.max(0, rectangle.width() - 2 * PREVIEW_MARGIN));
            this.preview.setHeight(Math.max(0, rectangle.height() - listH - 8));
        }
    }

    /**
     * Adds one row of the model to a tab's list: a setting row with its icon, name, hint and control,
     * or — for the two rows that stand for several — the expanded lines.
     */
    private void addRow(SettingsRowList list, ClientOptionsTab.Row row) {
        switch (row) {
            case PERFORMANCE_TIPS -> addPerformanceTips(list);
            case DISCORD_ACCOUNT -> addDiscordAccount(list);
            default -> {
                Setting setting = build(row);
                list.addSetting(ClientOptionsIcons.of(row), Component.translatable(row.captionKey()),
                        setting.hint != null ? setting.hint : Component.translatable(row.captionKey() + ".hint"),
                        setting.control, setting.tooltip);
            }
        }
    }

    /**
     * A row's control, the explanation shown for the whole row, and — for the one row whose hint carries
     * a live value — the hint itself; null means the row's own {@code <captionKey>.hint}.
     */
    private record Setting(AbstractWidget control, @Nullable Component tooltip, @Nullable Component hint) {
        Setting(AbstractWidget control, @Nullable Component tooltip) {
            this(control, tooltip, null);
        }

        static Setting of(AbstractWidget control, String tipKey) {
            return new Setting(control, Component.translatable(tipKey));
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
    private void addPerformanceTips(SettingsRowList list) {
        List<LagTips.Tip> tips = LagTips.all(this);
        List<LagTips.Tip> worth = tips.stream().filter(LagTips.Tip::applies).toList();
        List<LagTips.Tip> other = tips.stream().filter(t -> !t.applies()).toList();

        list.addWidget(caption(Component.translatable(
                worth.isEmpty() ? "gui.dungeontrain.options.performance.none"
                        : "gui.dungeontrain.options.performance.intro")));
        for (LagTips.Tip tip : worth) {
            list.addWidget(PerformanceTipRow.tip(this.font, ROW_W, ROW_H, tip, this::rebuildIfShown));
        }
        if (!other.isEmpty()) {
            list.addWidget(caption(Component.translatable("gui.dungeontrain.options.performance.other")));
            for (LagTips.Tip tip : other) {
                list.addWidget(PerformanceTipRow.tip(this.font, ROW_W, ROW_H, tip, this::rebuildIfShown));
            }
        }

        // Read from whatever release listings have arrived; the title screen starts that fetch, so by
        // the time a player is in Options it has usually landed. A miss just leaves the line out.
        Minecraft mc = Minecraft.getInstance();
        boolean multiplayer = mc.level != null && !mc.hasSingleplayerServer();
        BugResponse.Result versions = BugResponseCard.decideNow(BugIssue.LAG, multiplayer);
        if (versions.behind()) {
            list.addWidget(PerformanceTipRow.action(this.font, ROW_W, ROW_H, BugResponseCard.outdated(versions),
                    Component.translatable("gui.dungeontrain.bug_response.button.changes"),
                    () -> mc.setScreen(new VersionCompareScreen(this))));
        }
    }

    /**
     * The Account tab: the player's Discord link as the relay last reported it ({@link DiscordAccountState}).
     * Not linked → a Link row saying what linking does (the Credits link screen). Linked → the master
     * "All Discord pings" switch, then one row per {@link PingType}, greyed while the master is off.
     * Every toggle writes straight to the relay and the tab redraws from its answer, so a failed
     * write puts the toggle back and says so.
     *
     * <p>The first draw asks the relay once; its answer rebuilds the screen.</p>
     */
    private void addDiscordAccount(SettingsRowList list) {
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
                list.addWidget(PerformanceTipRow.action(this.font, ROW_W, ROW_H, why,
                        Component.translatable("gui.dungeontrain.options.account.retry"), () -> {
                            this.accountRequested = false;
                            DiscordAccountState.invalidate();
                            rebuildIfShown();
                        }));
            } else {
                list.addWidget(caption(Component.translatable("gui.dungeontrain.options.account.checking")));
            }
            return;
        }
        // Who is linked to whom: the Minecraft account signed in here, and the Discord account the
        // relay has it tied to — so a player with two Discords can see which one gets the pings.
        // One line: the two halves of the one link.
        Component discord = !status.linked()
                ? Component.translatable("gui.dungeontrain.options.account.discord_none")
                : status.discordName().isEmpty()
                        ? Component.translatable("gui.dungeontrain.options.account.discord_linked")
                        : Component.translatable("gui.dungeontrain.options.account.discord", status.discordName());
        list.addWidget(caption(Component.translatable("gui.dungeontrain.options.account.minecraft",
                this.minecraft.getUser().getName()).copy().append("   ").append(discord)));
        if (!status.linked()) {
            // The one thing to do on this tab until the account is linked: what linking does as the
            // hint, and the Link button as the control.
            Component why = Component.translatable("gui.dungeontrain.options.account.not_linked");
            Button link = Button.builder(Component.translatable("gui.dungeontrain.options.account.link"), b -> {
                        // Coming back rebuilds this screen and asks again, so a fresh link shows at once.
                        openChild(new DiscordLinkScreen(this, null), this::forgetAccount);
                    })
                    .bounds(0, 0, CONTROL_W, ROW_H).build();
            list.addSetting(ClientOptionsIcons.DISCORD_LINK,
                    Component.translatable("gui.dungeontrain.credits.community.link_action"), why,
                    link, why);
            addDonationPrefs(list); // works without a Discord link — it is keyed by the Minecraft account
            return;
        }
        if (DiscordAccountState.toggleFailed()) {
            list.addWidget(caption(Component.translatable("gui.dungeontrain.options.account.toggle_failed")));
        }
        String allKey = "gui.dungeontrain.options.account.pings_all";
        Component allTip = Component.translatable(allKey + ".tip");
        list.addSetting(ClientOptionsIcons.PING, Component.translatable(allKey),
                Component.translatable(allKey + ".hint"),
                onOff(Component.translatable(allKey), status.pings(), on -> DiscordAccountState.setMaster(on, this::rebuildIfShown)),
                allTip);

        for (PingType type : PingType.values()) {
            // The tip is one short line ("Ping me when my death report is posted."), so it IS the hint.
            Component hint = Component.translatable(pingKey(type) + ".tip");
            CycleButton<Boolean> toggle = onOff(Component.translatable(pingKey(type)), status.types().getOrDefault(type, true),
                    on -> DiscordAccountState.setType(type, on, this::rebuildIfShown));
            toggle.active = status.pings();
            list.addSetting(ClientOptionsIcons.PING, Component.translatable(pingKey(type)), hint, toggle, null);
        }
        addDonationPrefs(list);
    }

    /**
     * "Donation messages": how the developer hears about this player's donations on Discord —
     * {@link DonationPrefsClient.Visibility} public / anonymous / private / off, and whether their Discord
     * is tagged. Stored on the relay ({@link DonationPrefsState}); shown linked or not, since it is keyed
     * by the Minecraft account. The tag switch greys out where it has no effect (anonymous, off).
     */
    private void addDonationPrefs(SettingsRowList list) {
        if (!this.donationPrefsRequested) {
            this.donationPrefsRequested = true;
            DonationPrefsState.refresh(this::rebuildIfShown);
        }
        list.addWidget(caption(Component.translatable("gui.dungeontrain.options.account.donation.header")));
        DonationPrefsClient.Prefs prefs = DonationPrefsState.prefs();
        if (prefs == null) {
            if (DonationPrefsState.phase() == DonationPrefsState.Phase.FAILED) {
                list.addWidget(PerformanceTipRow.action(this.font, ROW_W, ROW_H,
                        Component.translatable("gui.dungeontrain.options.account.donation.failed"),
                        Component.translatable("gui.dungeontrain.options.account.retry"), () -> {
                            this.donationPrefsRequested = false;
                            DonationPrefsState.invalidate();
                            rebuildIfShown();
                        }));
            } else {
                list.addWidget(caption(Component.translatable("gui.dungeontrain.options.account.checking")));
            }
            return;
        }
        if (DonationPrefsState.saveFailed()) {
            list.addWidget(caption(Component.translatable("gui.dungeontrain.options.account.toggle_failed")));
        }
        String visKey = "gui.dungeontrain.options.account.donation.visibility";
        CycleButton<DonationPrefsClient.Visibility> visibility =
                CycleButton.<DonationPrefsClient.Visibility>builder(DungeonTrainClientOptionsScreen::donationVisibilityLabel)
                        .withValues(List.of(DonationPrefsClient.Visibility.values()))
                        .withInitialValue(prefs.visibility())
                        .displayOnlyValue()
                        .create(0, 0, CONTROL_W, ROW_H, Component.translatable(visKey),
                                (btn, v) -> DonationPrefsState.setVisibility(v, this::rebuildIfShown));
        // Explanations live on the label side of the row, never on the control.
        list.addSetting(ClientOptionsIcons.DONATION, Component.translatable(visKey),
                Component.translatable(visKey + ".hint"), visibility, Component.translatable(visKey + ".tip"));

        String tagKey = "gui.dungeontrain.options.account.donation.tag";
        Component tagTip = Component.translatable(tagKey + ".tip");
        CycleButton<Boolean> tag = onOff(Component.translatable(tagKey), prefs.tag(), on -> DonationPrefsState.setTag(on, this::rebuildIfShown));
        tag.active = prefs.visibility() == DonationPrefsClient.Visibility.PUBLIC
                || prefs.visibility() == DonationPrefsClient.Visibility.PRIVATE;
        list.addSetting(ClientOptionsIcons.DONATION_TAG, Component.translatable(tagKey),
                Component.translatable(tagKey + ".hint"), tag, tagTip);
    }

    private static Component donationVisibilityLabel(DonationPrefsClient.Visibility v) {
        return Component.translatable(donationKey(v));
    }

    private static String donationKey(DonationPrefsClient.Visibility v) {
        return "gui.dungeontrain.options.account.donation.visibility." + v.key();
    }

    private static String pingKey(PingType type) {
        return "gui.dungeontrain.options.account.pings." + type.key();
    }

    /** A gold caption line across the row, with nothing to click. */
    private PerformanceTipRow caption(Component text) {
        return PerformanceTipRow.caption(this.font, ROW_W, ROW_H, text);
    }

    /**
     * Rebuilds after a tip's action, unless that action already moved on to another screen.
     *
     * <p>Always deferred to the next client tick. The account refresh in {@link #addDiscordAccount}
     * is requested from inside {@code init()}, and when the relay's answer is already cached its
     * future is complete, so the callback ran synchronously — a {@code rebuildWidgets()} nested inside
     * the very {@code init()} that was still adding its own tab bar, Done button and list. The outer
     * init then finished on top of the rebuilt screen, and every reopen stacked another tab bar.</p>
     */
    private void rebuildIfShown() {
        this.minecraft.tell(() -> {
            if (this.minecraft.screen == this) {
                rebuildWidgets();
            }
        });
    }

    /**
     * The value-only control for one row and the explanation its row shows.
     *
     * <p>Positions are left at {@code 0,0} on purpose — the list assigns every control's real x/y as it
     * renders, so setting them here would only be overwritten. Width is {@value #CONTROL_W}; the list
     * enforces it.</p>
     */
    private Setting build(ClientOptionsTab.Row row) {
        String key = row.captionKey();
        return switch (row) {
            // Adult / Kid content mode — the second home for the choice made once on the first-launch
            // consent card, so a player who dismissed that card (or an existing install that answered
            // consent before this feature shipped, and so never sees it) can still find the setting.
            // Its tooltip is the longest on the screen, and the one that most has to land: it is the
            // only place outside the one-time card that says what Kid mode actually does, and it is
            // where a parent comes to change it.
            //
            // The button shows the current mode and reopens that same consent card rather than flipping
            // the mode in place, so the choice is always made seeing what it switches on and off — and
            // confirming the card turns the internet connection on, which every online feature also
            // needs. The card's answer writes the mode and syncs it to the server (DungeonTrain's
            // ConsentChoice); coming back rebuilds this screen (openChild), so this row redraws.
            case CONTENT_MODE -> Setting.of(
                    Button.builder(contentModeLabel(ClientDisplayConfig.getContentMode()), b -> {
                                // The card can turn the connection and Developer chat on or off, so the
                                // Account tab's last answer (e.g. "needs the internet connection") is
                                // stale once it closes: forget it then, and the tab asks again.
                                DungeonTrain.markConsentCardReopened();
                                openChild(new NetworkConsentScreen(this), this::forgetAccount);
                            })
                            .bounds(0, 0, CONTROL_W, ROW_H).build(),
                    key + ".tip");

            // The consent card's Livestreaming switch, findable again — the card's footnote promises
            // "change anytime in options".
            case LIVESTREAM -> Setting.of(onOff(Component.translatable(key), LiveFeedClientConfig.streamingEnabled(), on -> {
                LiveFeedClientConfig.setStreamingEnabled(on);
                ContentModeSyncClient.syncNow();
            }), key + ".tip");

            // What viewers see; the preview under the rows follows both switches live.
            case STREAM_GUI -> Setting.of(onOff(Component.translatable(key), LiveFeedClientConfig.streamGui(), LiveFeedClientConfig::setStreamGui),
                    key + ".tip");

            // Kid mode never streams chat: the switch shows Off and is greyed, whatever is stored.
            case STREAM_CHAT -> {
                boolean kid = ClientDisplayConfig.isKidMode();
                CycleButton<Boolean> streamChat = onOff(Component.translatable(key), !kid && LiveFeedClientConfig.streamChat(),
                        LiveFeedClientConfig::setStreamChat);
                streamChat.active = !kid;
                yield Setting.of(streamChat, kid ? key + ".kid_tip" : key + ".tip");
            }

            // The game's sound on the stream; read when the next stream starts.
            case STREAM_AUDIO -> Setting.of(onOff(Component.translatable(key), LiveFeedClientConfig.streamAudio(),
                    LiveFeedClientConfig::setStreamAudio), key + ".tip");

            // Decoding live feeds onto TVs and the pinned viewer; off = static, and the idle
            // watchdog stops the decoder. On the Performance tab too, as a frame-rate cost.
            case LIVE_FEED_VIEWING -> Setting.of(onOff(Component.translatable(key), LiveFeedClientConfig.viewingEnabled(),
                    LiveFeedClientConfig::setViewingEnabled), key + ".tip");

            // Whose name viewers see above the hotbar. Kid mode shows None, greyed, whatever is stored.
            case STREAM_NAME_TAG -> {
                boolean kid = ClientDisplayConfig.isKidMode();
                CycleButton<LiveFeedClientConfig.NameTag> nameTag =
                        CycleButton.<LiveFeedClientConfig.NameTag>builder(DungeonTrainClientOptionsScreen::streamNameTagLabel)
                                .withValues(LiveFeedClientConfig.NameTag.values())
                                .withInitialValue(StreamNameTag.effective(LiveFeedClientConfig.streamNameTag(), kid))
                                .displayOnlyValue()
                                .create(0, 0, CONTROL_W, ROW_H, Component.translatable(key),
                                        (btn, tag) -> LiveFeedClientConfig.setStreamNameTag(tag));
                nameTag.active = !kid;
                yield Setting.of(nameTag, kid ? key + ".kid_tip" : key + ".tip");
            }

            // The Discord live chat bridge (LiveChatBridge); Kid mode keeps it off and greyed.
            case LIVE_CHAT -> {
                boolean kid = ClientDisplayConfig.isKidMode();
                CycleButton<Boolean> liveChat = onOff(Component.translatable(key), !kid && LiveFeedClientConfig.liveChat(),
                        LiveFeedClientConfig::setLiveChat);
                liveChat.active = !kid;
                yield Setting.of(liveChat, kid ? key + ".kid_tip" : key + ".tip");
            }

            // Broadcast width; the preview re-captures at it on the next frame.
            case STREAM_SIZE -> {
                int current = LiveFeedClientConfig.captureWidth();
                yield Setting.of(
                        CycleButton.<Integer>builder(DungeonTrainClientOptionsScreen::streamSizeLabel)
                                .withValues(LiveFeedClientConfig.captureSizeValues(current))
                                .withInitialValue(current)
                                .displayOnlyValue()
                                .create(0, 0, CONTROL_W, ROW_H, Component.translatable(key),
                                        (btn, w) -> LiveFeedClientConfig.setCaptureWidth(w)),
                        key + ".tip");
            }

            // The pinned top-right feed; the preview outlines it at the chosen size.
            case HEAD_VIEWER_SIZE -> Setting.of(
                    CycleButton.<Integer>builder(DungeonTrainClientOptionsScreen::headViewerSizeLabel)
                            .withValues(LiveFeedClientConfig.headViewerSizeIndexes())
                            .withInitialValue(LiveFeedClientConfig.headViewerSize(false))
                            .displayOnlyValue()
                            .create(0, 0, CONTROL_W, ROW_H, Component.translatable(key),
                                    (btn, i) -> LiveFeedClientConfig.setHeadViewerSize(i)),
                    key + ".tip");

            // The consent card's Developer chat switch. Inert (greyed) in Kid mode, where the Kid rule
            // keeps developer chat off whatever this says; flipping it re-syncs the inbound gate.
            case DEV_CHAT -> {
                CycleButton<Boolean> devChat = onOff(Component.translatable(key), ClientDisplayConfig.isDevChatEnabled(), on -> {
                    ClientDisplayConfig.setDevChatEnabled(on);
                    DevMessageConsentClient.resync();
                });
                devChat.active = !ClientDisplayConfig.isKidMode();
                yield Setting.of(devChat, key + ".tip");
            }

            // Offered only where it is a live concern (Chinese-language clients), so the row is absent
            // rather than merely inert for everyone else. Translated; see the class javadoc.
            case POLITICAL_FILTER -> Setting.of(onOff(Component.translatable(key), PoliticalFilterPrefs.isEnabled(), PoliticalFilterPrefs::answer),
                    key + ".tooltip");

            // "The book by X burns" as each DT book catches fire.
            case BOOK_AUTHOR_CHAT -> Setting.of(onOff(Component.translatable(key), ClientDisplayConfig.isBookAuthorBurnChatEnabled(),
                    ClientDisplayConfig::setBookAuthorBurnChat), key + ".tip");

            // "Dungeon Train X is out" when a real release lands mid-session.
            case UPDATE_NOTICE_CHAT -> Setting.of(onOff(Component.translatable(key), ClientDisplayConfig.isUpdateNoticeChatEnabled(),
                    ClientDisplayConfig::setUpdateNoticeChat), key + ".tip");

            // "Minecraft only has N GB of memory" — title-screen card + chat line, once a session each.
            case LOW_MEMORY_NOTICE_CHAT -> Setting.of(onOff(Component.translatable(key), ClientDisplayConfig.isLowMemoryNoticeChatEnabled(),
                    ClientDisplayConfig::setLowMemoryNoticeChat), key + ".tip");

            // Minecraft's and other mods' advancement tabs. ON shows them; the config stores the inverse
            // (hideOtherTabs) because hiding is the default.
            case OTHER_ADVANCEMENT_TABS -> Setting.of(onOff(Component.translatable(key), !ClientDisplayConfig.isHideOtherAdvancementTabs(),
                    on -> ClientDisplayConfig.setHideOtherAdvancementTabs(!on)), key + ".tip");

            // How fast the train may re-extend once an end has fallen behind the carriages a nearby
            // player needs. One global value, not a per-world one — set it here or at the title
            // screen and every world follows. Applies live to the train already running: the
            // appender reads it at the moment it decides each spawn, so nothing waits on a reload.
            case CATCH_UP_BURST -> Setting.of(
                    CycleButton.<CatchUpBurstMode>builder(DungeonTrainClientOptionsScreen::catchUpBurstLabel)
                            .withValues(CatchUpBurstMode.values())
                            .withInitialValue(DungeonTrainCommonConfig.getCatchUpBurstMode())
                            .displayOnlyValue()
                            .create(0, 0, CONTROL_W, ROW_H, Component.translatable(key),
                                    (btn, mode) -> DungeonTrainCommonConfig.setCatchUpBurstMode(mode)),
                    key + ".tip");

            // The binding itself lives in vanilla Controls (Dungeon Train category); this only decides
            // whether it does anything, so a player who wants the key back for something else can free
            // it without hunting through the keybind list.
            case CINEMATIC_HOTKEY -> Setting.of(onOff(Component.translatable(key), ClientDisplayConfig.isCinematicHotkeyEnabled(),
                    ClientDisplayConfig::setCinematicHotkeyEnabled), key + ".tip");

            // The top-left developer read-out. Listed on dev builds only; takes effect next frame.
            case DEV_HUD -> Setting.of(onOff(Component.translatable(key), ClientDisplayConfig.isDevHudEnabled(),
                    ClientDisplayConfig::setDevHud), key + ".tip");

            // Scribble's book-screen buttons. Applied when the book is next opened —
            // ScribbleBookButtonsToggle reads these in ScreenEvent.Init.Post.
            case BOOK_FILE_BUTTONS -> Setting.of(onOff(Component.translatable(key), ClientDisplayConfig.isScribbleFileButtonsVisible(),
                    ClientDisplayConfig::setScribbleFileButtonsVisible), key + ".tip");
            case BOOK_INSERT_PAGE_BUTTON -> Setting.of(onOff(Component.translatable(key), ClientDisplayConfig.isScribbleInsertPageButtonVisible(),
                    ClientDisplayConfig::setScribbleInsertPageButtonVisible), key + ".tip");

            // "Was any of this made by AI?" answered in full. Unconditional, and deliberately a
            // page rather than a tooltip: the honest answer is longer than a row can carry. Also
            // reachable from the Credits page — see AiPolicyScreen.
            case AI_POLICY -> Setting.of(open(() -> this.minecraft.setScreen(new AiPolicyScreen(this))), key + ".tip");

            // Shown only when there is a language to edit — on en_us in a release build there is none
            // and the row would be a dead end; a dev build points it at the dev target instead, so the
            // editor stays testable with the UI still in English.
            case TRANSLATE -> Setting.of(open(() -> this.minecraft.setScreen(
                    new TranslationScreen(this, this.translateTarget))), key + ".tip");

            // A page of its own: a list of languages with a weight each. Re-synced to the server on
            // return so the next book found already uses the new mix.
            case BOOK_LANGUAGES -> Setting.of(open(() -> openChild(new ReadingLanguagesScreen(this),
                    ReadingLanguagesSyncClient::syncNow)), key + ".tip");

            // Literally the same control vanilla's Music & Sounds screen carries (put there by
            // SoundOptionsScreenTrainVolumeMixin) — one OptionInstance definition over one config
            // accessor, so a player who came looking here rather than there finds the same control at
            // the same value. The slider carries the sound screen's long tooltip; the row has its own short one.
            case TRAIN_VOLUME -> Setting.of(slider(TrainVolumeOption.valueOnly()), key + ".tip");

            // The standing answer to the start-of-world prompt shown when the player has Train Editor
            // edits or an imported dtpack. This is the "can be changed in options" half of that
            // prompt's "Remember decision" checkbox.
            case CUSTOM_CONTENT -> new Setting(
                    CycleButton.<CustomContentPreference>builder(DungeonTrainClientOptionsScreen::customContentLabel)
                            .withValues(customContentValues())
                            .withInitialValue(customContentInitial())
                            .displayOnlyValue()
                            .create(0, 0, CONTROL_W, ROW_H, Component.translatable(key),
                                    (btn, pref) -> ClientDisplayConfig.setCustomContentPreference(pref)),
                    Component.translatable(key + ".tip"));

            // The three backup settings are Dungeon Backup's: it owns the setting
            // (config/dungeonbackup-client.toml) and the archives. Its own widgets carry their caption
            // in the label, so the value-only controls are built here over its public BackupSettings,
            // with its labels and tips. Per-value tips: "Instanced" means nothing on its own, and the
            // whole point of the setting is the difference in what each option survives.
            case BACKUPS -> {
                CycleButton<BackupMode> mode = CycleButton.<BackupMode>builder(BackupOptionsWidgets::modeLabel)
                        .withValues(List.of(BackupMode.EXTERNAL, BackupMode.INSTANCE, BackupMode.OFF))
                        .withInitialValue(BackupSettings.storedMode())
                        .displayOnlyValue()
                        .create(0, 0, CONTROL_W, ROW_H, Component.translatable(key), (btn, value) -> {
                            BackupSettings.setMode(value);
                            rebuildIfShown(); // the label's explanation is per value
                        });
                if (BackupSettings.isOverridden()) {
                    mode.active = false;
                    mode.setTooltip(Tooltip.create(Component.translatable(BackupOptionsWidgets.KEY_MODE + ".overridden",
                            BackupSettings.operatorOverride().name().toLowerCase(Locale.ROOT))));
                }
                // The label carries the current mode's own explanation (Dungeon Backup's per-value tip).
                yield new Setting(mode, Component.translatable(BackupOptionsWidgets.KEY_MODE + "."
                        + BackupSettings.storedMode().name().toLowerCase(Locale.ROOT) + ".tip"));
            }
            case BACKUPS_PER_VERSION -> Setting.of(slider(backupsPerVersionOption()), key + ".tip");

            // The label carries the size on disk, so after a clear the widgets are rebuilt —
            // rebuildWidgets() is what re-runs init(); merely returning to the screen only
            // repositions it and the button would keep reporting the space it just freed.
            case CLEAR_BACKUPS -> {
                // Dungeon Backup's button puts the size in its label; here the control is one verb and
                // the size is the hint, so the label fits the value column.
                Button clear = BackupOptionsWidgets.clearButton(CONTROL_W, ROW_H, this, this::rebuildWidgets);
                clear.setMessage(Component.translatable("gui.dungeontrain.options.clear"));
                yield new Setting(clear, Component.translatable(key + ".tip"),
                        Component.translatable(key + ".hint", BackupArchiver.formatBytes(BackupSettings.totalSize())));
            }

            // Off by default: a restore is the same upload the build's next save would have made, so
            // there is normally nothing to decide. On, it shows the title-screen card instead.
            case CONFIRM_BUILD_RESTORE -> Setting.of(onOff(Component.translatable(key), ClientDisplayConfig.isConfirmBuildRestore(),
                    ClientDisplayConfig::setConfirmBuildRestore), key + ".tip");

            // The bundled Edible Backpacks' open/close button on the survival inventory screen.
            // Reads and writes EB's OWN client config rather than mirroring it into
            // ClientDisplayConfig: config/ediblebackpacks-client.toml already owns the button
            // (it also carries the anchor and custom x/y this row deliberately does not expose),
            // and EB re-reads the value every frame, so the change lands without reopening the
            // inventory. Turning it off is safe — EB's keybind still opens the panels.
            case BACKPACK_BUTTON -> Setting.of(onOff(Component.translatable(key), EBClientConfig.buttonEnabled(),
                    DungeonTrainClientOptionsScreen::setBackpackButtonEnabled), key + ".tip");

            // EB's own CLIENT config again (autoCollect); ClientAutoCollect.set saves it and tells the
            // server, which is where pickups are decided. A server's own autoCollect=false still wins.
            case BACKPACK_AUTO_COLLECT -> new Setting(
                    CycleButton.<AutoCollectMode>builder(DungeonTrainClientOptionsScreen::autoCollectLabel)
                            .withValues(AutoCollectMode.values())
                            .withInitialValue(EBClientConfig.autoCollect())
                            .displayOnlyValue()
                            .create(0, 0, CONTROL_W, ROW_H, Component.translatable(key),
                                    (btn, mode) -> ClientAutoCollect.set(mode)),
                    Component.translatable(key + ".tip"));

            // Snapshot max resolution ceiling (0 = AUTO).
            case SNAPSHOT_MAX_RES -> {
                int currentRes = ClientDisplayConfig.getRideSnapshotMaxResolution();
                yield Setting.of(
                        CycleButton.<Integer>builder(DungeonTrainClientOptionsScreen::resolutionLabel)
                                .withValues(resolutionValues(currentRes))
                                .withInitialValue(Math.max(0, currentRes))
                                .displayOnlyValue()
                                .create(0, 0, CONTROL_W, ROW_H, Component.translatable(key),
                                        (btn, val) -> ClientDisplayConfig.setRideSnapshotMaxResolution(val)),
                        key + ".tip");
            }

            case SNAPSHOT_CHAT_LOG -> Setting.of(onOff(Component.translatable(key), ClientDisplayConfig.isRideSnapshotChatLogEnabled(),
                    ClientDisplayConfig::setRideSnapshotChatLog), key + ".tip");

            // The three display-scale channels, inlined here rather than behind a sub-screen button.
            // Their tooltips are baked into the options; the rows repeat them.
            case SCALE_ALL -> Setting.of(slider(DisplayScaleOption.allDisplaysValueOnly()), key + ".tip");
            case SCALE_WORLDSPACE -> Setting.of(slider(DisplayScaleOption.worldspaceValueOnly()), key + ".tip");
            case SCALE_HUD -> Setting.of(slider(DisplayScaleOption.hudValueOnly()), key + ".tip");

            // Where each of the four editor menus (X/V/C/Z) draws itself — worldspace panel aimed by
            // the camera, or a screenspace window pointed at with a free mouse cursor. Same accessors
            // and lang keys the deleted DungeonTrainEditorSettingsScreen used; only the row's home moved.
            case MENU_SPACE_COMMAND -> menuSpace(key,
                    ClientDisplayConfig::getCommandMenuSpace, ClientDisplayConfig::setCommandMenuSpace);
            case MENU_SPACE_TEMPLATE_BLOCKS -> menuSpace(key,
                    ClientDisplayConfig::getTemplateBlocksMenuSpace, ClientDisplayConfig::setTemplateBlocksMenuSpace);
            case MENU_SPACE_CONTAINER_CONTENTS -> menuSpace(key,
                    ClientDisplayConfig::getContainerContentsMenuSpace, ClientDisplayConfig::setContainerContentsMenuSpace);
            case MENU_SPACE_BLOCK_VARIANT -> menuSpace(key,
                    ClientDisplayConfig::getBlockVariantMenuSpace, ClientDisplayConfig::setBlockVariantMenuSpace);

            // The biome mods' creative tabs and their search entries, both hidden by default. The
            // tabs are rebuilt on the spot so the change shows without a relog.
            case CREATIVE_MOD_BLOCK_TABS -> Setting.of(onOff(Component.translatable(key), ClientDisplayConfig.isCreativeModBlockTabs(), on -> {
                ClientDisplayConfig.setCreativeModBlockTabs(on);
                CreativeTabRefresh.rebuild();
            }), key + ".tip");
            case CREATIVE_MOD_BLOCKS_IN_SEARCH -> Setting.of(onOff(Component.translatable(key), ClientDisplayConfig.isCreativeModBlocksInSearch(), on -> {
                ClientDisplayConfig.setCreativeModBlocksInSearch(on);
                CreativeTabRefresh.rebuild();
            }), key + ".tip");

            // Expanded by addRow() into several rows; there is no single control for them.
            case PERFORMANCE_TIPS -> throw new IllegalStateException("PERFORMANCE_TIPS is expanded, not built");
            case DISCORD_ACCOUNT -> throw new IllegalStateException("DISCORD_ACCOUNT is expanded, not built");
        };
    }

    /** An {@code ON} / {@code OFF} control showing only its state; {@code name} is what narration calls it. */
    private static CycleButton<Boolean> onOff(Component name, boolean initial, Consumer<Boolean> set) {
        return CycleButton.onOffBuilder(initial)
                .displayOnlyValue()
                .create(0, 0, CONTROL_W, ROW_H, name, (btn, on) -> set.accept(on));
    }

    /** The control of a row that opens a page rather than changing a setting: one verb, "Open". */
    private static Button open(Runnable action) {
        return Button.builder(Component.translatable("gui.dungeontrain.options.open"), b -> action.run())
                .bounds(0, 0, CONTROL_W, ROW_H).build();
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

    private AbstractWidget slider(OptionInstance<Integer> option) {
        return option.createButton(this.minecraft.options, 0, 0, CONTROL_W);
    }

    /**
     * Dungeon Backup's "Backups per version" slider showing only its number: the same stored value
     * and the same commit ({@link BackupSettings#setPerVersion}) as its own
     * {@link BackupOptionsWidgets#perVersionOption}, whose label carries the caption.
     */
    private static OptionInstance<Integer> backupsPerVersionOption() {
        int initial = Math.max(BackupSettings.PER_VERSION_MIN,
                Math.min(BackupSettings.PER_VERSION_MAX, BackupSettings.perVersion()));
        return new OptionInstance<>(
                BackupOptionsWidgets.KEY_PER_VERSION,
                OptionInstance.noTooltip(),
                (caption, value) -> Component.literal(Integer.toString(value)), // a count, not prose
                new OptionInstance.IntRange(BackupSettings.PER_VERSION_MIN, BackupSettings.PER_VERSION_MAX),
                initial,
                BackupSettings::setPerVersion);
    }

    /** A {@code Worldspace} / {@code Screenspace} control for one editor menu. */
    private Setting menuSpace(String key, Supplier<EditorMenuSpace> get, Consumer<EditorMenuSpace> set) {
        return Setting.of(
                CycleButton.<EditorMenuSpace>builder(DungeonTrainClientOptionsScreen::menuSpaceLabel)
                        .withValues(EditorMenuSpace.values())
                        .withInitialValue(get.get())
                        .displayOnlyValue()
                        .create(0, 0, CONTROL_W, ROW_H, Component.translatable(key), (btn, val) -> set.accept(val)),
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

    /** A pinned-feed size index as a percentage of its normal width: "150%". */
    private static Component headViewerSizeLabel(int index) {
        return Component.literal(LiveFeedClientConfig.headViewerSizePercent(index) + "%");
    }

    /** A broadcast width as its picture height: {@code 1280} reads "720p". */
    private static Component streamNameTagLabel(LiveFeedClientConfig.NameTag tag) {
        return Component.translatable("gui.dungeontrain.options.stream_name_tag." + tag.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static Component streamSizeLabel(int width) {
        return Component.literal(LiveFeedClientConfig.heightFor(width) + "p"); // a unit, not prose
    }

    private static Component resolutionLabel(int value) {
        return value <= 0
                ? Component.translatable("gui.dungeontrain.options.snapshot_max_res.auto")
                : Component.literal(value + "p"); // "1080p" — a unit, not prose
    }

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

    private static Component autoCollectLabel(AutoCollectMode mode) {
        return Component.translatable("gui.dungeontrain.options.backpack_auto_collect."
                + mode.name().toLowerCase(Locale.ROOT));
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
