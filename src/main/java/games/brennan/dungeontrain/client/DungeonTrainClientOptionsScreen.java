package games.brennan.dungeontrain.client;

import games.brennan.discordpresence.client.NetworkConsentScreen;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.ContentMode;
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
 * <p>Most labels are plain literals, matching the existing plain-English DT options screens. Two rows
 * are deliberate exceptions and ARE localized, and new rows of that kind should follow them rather
 * than the literals around them:</p>
 * <ul>
 *   <li>The Adult / Kid content row — the only place outside the first-launch consent card that says
 *       what Kid mode does, and where a parent goes to change it, so it must be readable on a
 *       non-English client.</li>
 *   <li>The Political Filter row — shown only to players on a Chinese-language client (see
 *       {@link PoliticalFilterPrefs}), so by construction nobody who reads it is reading the rest of
 *       this screen in English; a plain literal there would be the one label its entire audience
 *       cannot read.</li>
 * </ul>
 */
public final class DungeonTrainClientOptionsScreen extends Screen {

    private static final int ROW_W = 210;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 24;

    /** Ceiling ladder shared with the X-menu row: 0 = AUTO, then fixed long-edge caps. */
    private static final List<Integer> RESOLUTION_VALUES = List.of(0, 1080, 1440, 2160);

    private final Screen parent;

    public DungeonTrainClientOptionsScreen(Screen parent) {
        super(Component.literal("Dungeon Train Options"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int left = cx - ROW_W / 2;
        int y = this.height / 3;

        // Editor / display-scale settings live on their own sub-screen.
        addRenderableWidget(Button.builder(Component.literal("Editor Settings…"),
                        b -> this.minecraft.setScreen(new DungeonTrainEditorSettingsScreen(this)))
                .bounds(left, y, ROW_W, ROW_H).build())
                .setTooltip(tip("Display scale for Dungeon Train's in-world menus, HUD and debug labels."));
        y += ROW_GAP;

        // Master network / internet-connection switch (DP's one-time "use the internet?" consent). OFF
        // revokes immediately; turning it ON routes through DP's informed consent screen rather than
        // silently granting — granting network access gates leaderboard / dev chat / book share / telemetry.
        addRenderableWidget(Button.builder(internetLabel(), b -> toggleInternet())
                .bounds(left, y, ROW_W, ROW_H).build())
                .setTooltip(tip("Master switch for online features (leaderboard, developer chat, community books, telemetry). OFF disables all network use; ON opens the consent screen."));
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
                // Localized, unlike this screen's other rows. Those describe display scale and a master
                // on/off; this one is the only place outside the one-time card that says what Kid mode
                // actually does, and it is where a parent comes to change it — a parent reading a
                // non-English client should not be shown three lines of English to make that decision.
                .setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.options.content_mode.tip")));
        y += ROW_GAP;

        // Snapshot chat log ON/OFF.
        addRenderableWidget(CycleButton.onOffBuilder(ClientDisplayConfig.isRideSnapshotChatLogEnabled())
                .create(left, y, ROW_W, ROW_H, Component.literal("Snapshot Chat Log"),
                        (btn, on) -> ClientDisplayConfig.setRideSnapshotChatLog(on)))
                .setTooltip(tip("Print a chat line each time a ride photo is auto-captured, showing its tag and reason. A debug aid; off by default."));
        y += ROW_GAP;

        // Snapshot max resolution ceiling (0 = AUTO).
        int currentRes = ClientDisplayConfig.getRideSnapshotMaxResolution();
        addRenderableWidget(CycleButton.<Integer>builder(DungeonTrainClientOptionsScreen::resolutionLabel)
                .withValues(RESOLUTION_VALUES)
                .withInitialValue(RESOLUTION_VALUES.contains(currentRes) ? currentRes : 0)
                .create(left, y, ROW_W, ROW_H, Component.literal("Snapshot Max Resolution"),
                        (btn, value) -> ClientDisplayConfig.setRideSnapshotMaxResolution(value)))
                .setTooltip(tip("Upper limit for ride-photo capture resolution. AUTO uses 1080p, rising to 1440p/2160p with Distant Horizons + shaders or Fabulous graphics."));
        y += ROW_GAP;

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(cx - 100, y + 6, 200, ROW_H).build());
    }

    private static Component resolutionLabel(int value) {
        return Component.literal(value <= 0 ? "AUTO" : value + "p");
    }

    /** Localized name for a content mode — same keys the first-launch consent card's question uses. */
    private static Component contentModeLabel(ContentMode mode) {
        return Component.translatable(mode.isKid()
                ? "gui.dungeontrain.content_mode.kid"
                : "gui.dungeontrain.content_mode.adult");
    }

    /** A word-wrapping hover tooltip from plain text. */
    private static Tooltip tip(String text) {
        return Tooltip.create(Component.literal(text));
    }

    private static Component internetLabel() {
        return Component.literal("Internet Connection: " + (DiscordPresenceClientConfig.isGranted() ? "ON" : "OFF"));
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

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
