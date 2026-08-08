package games.brennan.dungeontrain.client;

import games.brennan.discordpresence.client.NetworkConsentScreen;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.ContentMode;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Vanilla-{@link Screen} home for Dungeon Train's client settings, opened from a "Dungeon Train…" button
 * injected into Minecraft's Options screen ({@link OptionsScreenDungeonTrainButton}) — reachable from
 * both the main-menu and the Esc/pause Options.
 *
 * <p>Unlike the in-world X-menu {@code OptionsMenuScreen} (a worldspace panel that needs a player and so
 * can't appear on the title screen), this is an ordinary GUI screen and works with or without a world.
 * It hosts an <b>Editor Settings…</b> sub-screen ({@link DungeonTrainEditorSettingsScreen}, the display
 * scale steppers) plus the ride-snapshot toggles, all reading/writing the same {@link ClientDisplayConfig}
 * accessors the X-menu uses — so the surfaces never diverge.</p>
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
public final class DungeonTrainClientOptionsScreen extends Screen {

    private static final int ROW_W = 210;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 24;

    /** Ceiling ladder shared with the X-menu row: 0 = AUTO, then fixed long-edge caps. */
    private static final List<Integer> RESOLUTION_VALUES = List.of(0, 1080, 1440, 2160);

    private final Screen parent;
    private MaxCarriagesSlider maxCarriagesSlider;

    public DungeonTrainClientOptionsScreen(Screen parent) {
        // ".client." because gui.dungeontrain.options.title is already taken by the WORLD options screen.
        super(Component.translatable("gui.dungeontrain.options.client.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int left = cx - ROW_W / 2;
        int y = this.height / 3;

        // Editor / display-scale settings live on their own sub-screen.
        addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.options.editor_settings"),
                        b -> this.minecraft.setScreen(new DungeonTrainEditorSettingsScreen(this)))
                .bounds(left, y, ROW_W, ROW_H).build())
                .setTooltip(tip("gui.dungeontrain.options.editor_settings.tip"));
        y += ROW_GAP;

        // Master network / internet-connection switch (DP's one-time "use the internet?" consent). OFF
        // revokes immediately; turning it ON routes through DP's informed consent screen rather than
        // silently granting — granting network access gates leaderboard / dev chat / book share / telemetry.
        addRenderableWidget(Button.builder(internetLabel(), b -> toggleInternet())
                .bounds(left, y, ROW_W, ROW_H).build())
                .setTooltip(tip("gui.dungeontrain.options.internet.tip"));
        y += ROW_GAP;

        // Political Filter — offered only where it is a live concern (Chinese-language clients), so the
        // row is absent rather than merely inert for everyone else. Translated; see the class javadoc.
        if (PoliticalFilterPrefs.isChineseLocale()) {
            addRenderableWidget(CycleButton.onOffBuilder(PoliticalFilterPrefs.isEnabled())
                    .create(left, y, ROW_W, ROW_H,
                            Component.translatable("gui.dungeontrain.political_filter.option"),
                            (btn, on) -> PoliticalFilterPrefs.answer(on)))
                    .setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.political_filter.option.tooltip")));
            y += ROW_GAP;
        }

        // Adult / Kid content mode — the second home for the choice made once on the first-launch
        // consent card, so a player who dismissed that card (or an existing install that answered
        // consent before this feature shipped, and so never sees it) can still find the setting.
        // Syncs to the server immediately so the per-player gates follow without a relog.
        addRenderableWidget(CycleButton.<ContentMode>builder(DungeonTrainClientOptionsScreen::contentModeLabel)
                .withValues(List.of(ContentMode.ADULT, ContentMode.KID))
                .withInitialValue(ClientDisplayConfig.getContentMode())
                .create(left, y, ROW_W, ROW_H, Component.translatable("gui.dungeontrain.options.content_mode"),
                        (btn, mode) -> {
                            ClientDisplayConfig.setContentMode(mode);
                            ContentModeSyncClient.syncNow();
                        }))
                // The longest tooltip on the screen, and the one that most has to land: it is the only
                // place outside the one-time card that says what Kid mode actually does, and it is where
                // a parent comes to change it.
                .setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.options.content_mode.tip")));
        y += ROW_GAP;

        // Snapshot chat log ON/OFF.
        addRenderableWidget(CycleButton.onOffBuilder(ClientDisplayConfig.isRideSnapshotChatLogEnabled())
                .create(left, y, ROW_W, ROW_H, Component.translatable("gui.dungeontrain.options.snapshot_chat_log"),
                        (btn, on) -> ClientDisplayConfig.setRideSnapshotChatLog(on)))
                .setTooltip(tip("gui.dungeontrain.options.snapshot_chat_log.tip"));
        y += ROW_GAP;

        // Snapshot max resolution ceiling (0 = AUTO).
        int currentRes = ClientDisplayConfig.getRideSnapshotMaxResolution();
        addRenderableWidget(CycleButton.<Integer>builder(DungeonTrainClientOptionsScreen::resolutionLabel)
                .withValues(RESOLUTION_VALUES)
                .withInitialValue(RESOLUTION_VALUES.contains(currentRes) ? currentRes : 0)
                .create(left, y, ROW_W, ROW_H, Component.translatable("gui.dungeontrain.options.snapshot_max_res"),
                        (btn, value) -> ClientDisplayConfig.setRideSnapshotMaxResolution(value)))
                .setTooltip(tip("gui.dungeontrain.options.snapshot_max_res.tip"));
        y += ROW_GAP;

        // Max carriages — the performance knob. Sits with the other client settings because it
        // describes THIS machine: it is stored client-side and follows the player onto every server.
        // Applies live — the server's appender re-reads the window every tick, so lowering it
        // shortens the train you're standing on within a tick or two, no relog and no reload.
        maxCarriagesSlider = addRenderableWidget(new MaxCarriagesSlider(left, y, ROW_W, ROW_H));
        maxCarriagesSlider.setTooltip(tip("gui.dungeontrain.options.max_carriages.tip"));
        y += ROW_GAP;

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(cx - 100, y + 6, 200, ROW_H).build());
    }

    /**
     * Auto / 4–50 slider over {@link ClientDisplayConfig#getMaxCarriages()}, shaped after vanilla's
     * Render Distance slider (which sits a few rows above it on the parent Options screen).
     *
     * <p>The leftmost notch is <b>Auto</b> — the {@code 0} sentinel — so the ladder is one notch
     * longer than the 4–50 range it exposes. Everything writes through on release rather than on
     * every drag pixel: a mid-drag write would sync a packet per frame and thrash the appender's
     * window across dozens of intermediate lengths.</p>
     */
    private static final class MaxCarriagesSlider extends AbstractSliderButton {

        /** Number of selectable values: Auto, then 4…50 inclusive. */
        private static final int STEPS =
                1 + (DungeonTrainConfig.MAX_CARRIAGES - DungeonTrainConfig.MIN_CARRIAGES_EXPLICIT + 1);

        /** Last value written to the config, so a drag only writes when it crosses a notch. */
        private int lastWritten;

        MaxCarriagesSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty(), 0.0);
            this.lastWritten = ClientDisplayConfig.getMaxCarriages();
            this.value = toSliderValue(this.lastWritten);
            updateMessage();
        }

        /** Slider position → carriage ceiling. Index 0 is Auto ({@code 0}); the rest are 4…50. */
        private int carriages() {
            int index = (int) Math.round(this.value * (STEPS - 1));
            return index <= 0 ? 0 : DungeonTrainConfig.MIN_CARRIAGES_EXPLICIT + (index - 1);
        }

        /** Carriage ceiling → slider position. Inverse of {@link #carriages()}. */
        private static double toSliderValue(int carriages) {
            if (carriages <= 0) return 0.0;
            int index = 1 + (carriages - DungeonTrainConfig.MIN_CARRIAGES_EXPLICIT);
            return (double) index / (STEPS - 1);
        }

        @Override
        protected void updateMessage() {
            int carriages = carriages();
            setMessage(Component.translatable("gui.dungeontrain.options.max_carriages",
                    carriages <= 0
                            ? Component.translatable("gui.dungeontrain.options.max_carriages.auto")
                            : Component.literal(Integer.toString(carriages))));
        }

        /**
         * Persist on every notch the drag crosses, not on release.
         *
         * <p>Releasing is not a reliable commit point: {@code AbstractContainerEventHandler}
         * dispatches {@code mouseReleased} to the widget <b>under the cursor</b>, so a drag that
         * ends past the end of the track — the most natural way to reach AUTO or 50 — never calls
         * {@link #onRelease}. Committing here instead means every path that can move the slider
         * (mouse drag, click, arrow keys) persists, because they all funnel through vanilla's
         * private {@code setValue}, which calls this whenever the value actually changes.</p>
         *
         * <p>The {@code lastWritten} guard keeps this to one small config write per notch crossed
         * rather than one per drag pixel. The server sync is NOT sent here — that would be a packet
         * per notch; it goes out on release and on close instead.</p>
         */
        @Override
        protected void applyValue() {
            int carriages = carriages();
            if (carriages == lastWritten) return;
            lastWritten = carriages;
            ClientDisplayConfig.setMaxCarriages(carriages);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            sync();
        }

        /** Push the stored value to the server so the window resizes now. Safe to call repeatedly. */
        void sync() {
            MaxCarriagesSyncClient.syncNow();
        }
    }

    private static Component resolutionLabel(int value) {
        return value <= 0
                ? Component.translatable("gui.dungeontrain.options.snapshot_max_res.auto")
                : Component.literal(value + "p"); // "1080p" — a unit, not prose
    }

    /** Localized name for a content mode — same keys the first-launch consent card's question uses. */
    private static Component contentModeLabel(ContentMode mode) {
        return Component.translatable(mode.isKid()
                ? "gui.dungeontrain.content_mode.kid"
                : "gui.dungeontrain.content_mode.adult");
    }

    /** A word-wrapping hover tooltip from a lang key. */
    private static Tooltip tip(String key) {
        return Tooltip.create(Component.translatable(key));
    }

    /** ON/OFF comes from vanilla's own already-translated {@code options.on} / {@code options.off}. */
    private static Component internetLabel() {
        return Component.translatable("gui.dungeontrain.options.internet",
                Component.translatable(DiscordPresenceClientConfig.isGranted() ? "options.on" : "options.off"));
    }

    /** ON→OFF revokes network consent immediately (+ server re-sync); OFF→ON opens DP's informed consent screen. */
    private void toggleInternet() {
        if (DiscordPresenceClientConfig.isGranted()) {
            DiscordPresenceClientConfig.setConsent(DiscordPresenceClientConfig.Consent.DENIED);
            NetworkConsentSyncClient.syncNow();
            rebuildWidgets(); // refresh the label to OFF
        } else {
            this.minecraft.setScreen(new NetworkConsentScreen(this));
        }
    }

    /**
     * Backstop for the max-carriages sync. The value itself is already persisted the moment the
     * slider crosses a notch; this covers the server push for the paths that never fire the
     * slider's {@code onRelease} — a drag released past the end of the track, or arrow keys.
     * Sending one redundant packet on close is cheaper than the window silently not resizing.
     */
    @Override
    public void removed() {
        if (maxCarriagesSlider != null) {
            maxCarriagesSlider.sync();
        }
        super.removed();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
