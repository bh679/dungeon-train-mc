package games.brennan.dungeontrain.client;

import games.brennan.discordpresence.client.NetworkConsentScreen;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.client.localization.edit.TranslationScreen;
import games.brennan.dungeontrain.client.localization.edit.TranslationTarget;
import games.brennan.dungeontrain.client.sound.TrainVolumeOption;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.ContentMode;
import games.brennan.dungeontrain.config.CustomContentPreference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Home for Dungeon Train's client settings, opened from a "Dungeon Train…" button injected into
 * Minecraft's Options screen ({@link OptionsScreenDungeonTrainButton}) — reachable from both the
 * main-menu and the Esc/pause Options.
 *
 * <p>Unlike the in-world X-menu {@code OptionsMenuScreen} (a worldspace panel that needs a player and
 * so can't appear on the title screen), this is an ordinary GUI screen and works with or without a
 * world. It hosts an <b>Editor Settings…</b> sub-screen ({@link DungeonTrainEditorSettingsScreen},
 * the display scale steppers) plus the ride-snapshot toggles, all reading/writing the same
 * {@link ClientDisplayConfig} accessors the X-menu uses — so the surfaces never diverge.</p>
 *
 * <p><b>It is an {@link OptionsSubScreen}, not a bare {@code Screen}, and that is load-bearing.</b>
 * It used to lay itself out by hand: one 210px column, 24px per row, starting at {@code height/3},
 * Done tacked on after the last row. Eleven rows do not fit that way — on a small window or at GUI
 * Scale 3–4 the bottom rows <em>and the Done button</em> ran off-screen with no way to reach them.
 * The base class supplies the three things that fixes: a title header, a Done button pinned in the
 * footer where it is always reachable, and a scrolling {@link net.minecraft.client.gui.components.OptionsList}
 * sized to whatever height is left. The list also packs rows two-across, which is why the screen no
 * longer wastes half its width. Add rows through {@link #addOptions()}; never position widgets by
 * hand here again.</p>
 *
 * <p><b>Every row here is localized</b> — labels and tooltips alike — and new rows must follow suit
 * rather than reaching for {@link Component#literal}. This screen used to be plain-English literals
 * with the content-mode and Political Filter rows as localized exceptions; a screenshot of a Chinese
 * client settled it, because the exceptions are the reason the rest has to be translated too. The
 * consent flow asks a player for the Kid-mode and Chinese-filter answers <em>in their own language</em>,
 * then sends them here to change them: arriving at a screen where two rows are Chinese and four are
 * English is worse than either extreme. The on/off states come from vanilla's own {@code options.on} /
 * {@code options.off}, which are already translated for every locale MC ships.</p>
 *
 * @see ClientOptionsSections the grouping and row order, kept Minecraft-free so it can be unit-tested
 */
public final class DungeonTrainClientOptionsScreen extends OptionsSubScreen {

    /** Full content width of an {@code OptionsList} row — a section header spans it. */
    private static final int HEADER_WIDTH = 310;
    /** One of the list's two columns. Vanilla's own small-option width; captions are sized to it. */
    private static final int ROW_W = 150;
    private static final int ROW_H = 20;

    /** Headers read as labels over the white button text, so they sit a step back in grey. */
    private static final int HEADER_COLOR = 0xFFA0A0A0;

    /** Ceiling ladder shared with the X-menu row: 0 = AUTO, then fixed long-edge caps. */
    private static final List<Integer> RESOLUTION_VALUES = List.of(0, 1080, 1440, 2160);

    /**
     * Held so {@link #toggleInternet()} can refresh just this button's ON/OFF label. It used to call
     * {@code rebuildWidgets()} for that, which now would also throw the list's scroll position back
     * to the top mid-interaction.
     */
    private Button internetButton;

    public DungeonTrainClientOptionsScreen(Screen parent) {
        // ".client." because gui.dungeontrain.options.title is already taken by the WORLD options screen.
        // Minecraft.getInstance() rather than this.minecraft: the field isn't populated until init().
        super(parent, Minecraft.getInstance().options,
                Component.translatable("gui.dungeontrain.options.client.title"));
    }

    @Override
    protected void addOptions() {
        // Shown only when there is a language to edit — on en_us in a release build there is none and
        // the row would be a dead end; a dev build points it at the dev target instead, so the editor
        // stays testable with the UI still in English.
        String translateTarget = TranslationTarget.resolveForClient();

        List<ClientOptionsSections.Section> sections = ClientOptionsSections.visibleSections(
                PoliticalFilterPrefs.isChineseLocale(), !translateTarget.isEmpty());

        for (ClientOptionsSections.Section section : sections) {
            // A header is a solo row: addSmall(widget, null) leaves the right column empty, so the
            // section's own rows start fresh on the next line rather than pairing with the header.
            this.list.addSmall(sectionHeader(section), null);

            List<AbstractWidget> rows = new ArrayList<>();
            for (ClientOptionsSections.Row row : section.rows()) {
                rows.add(widgetFor(row, translateTarget));
            }
            // Pairs them two-across, tolerating an odd count — which the conditional rows produce.
            this.list.addSmall(rows);
        }
    }

    /** An inert, unfocusable text row ({@code StringWidget} sets {@code active = false}). */
    private AbstractWidget sectionHeader(ClientOptionsSections.Section section) {
        return new StringWidget(0, 0, HEADER_WIDTH, ROW_H,
                Component.translatable(section.fullTitleKey()), this.font)
                .alignLeft()
                .setColor(HEADER_COLOR);
    }

    /**
     * The widget for one row. Positions are left at {@code 0,0} on purpose — {@code OptionsList}
     * assigns every row's real x/y as it renders, so setting them here would be overwritten.
     */
    private AbstractWidget widgetFor(ClientOptionsSections.Row row, String translateTarget) {
        return switch (row) {
            // Editor / display-scale settings live on their own sub-screen.
            case EDITOR_SETTINGS -> withTip(
                    Button.builder(Component.translatable("gui.dungeontrain.options.editor_settings"),
                                    b -> this.minecraft.setScreen(new DungeonTrainEditorSettingsScreen(this)))
                            .bounds(0, 0, ROW_W, ROW_H).build(),
                    "gui.dungeontrain.options.editor_settings.tip");

            // Literally the same widget vanilla's Music & Sounds screen carries (put there by
            // SoundOptionsScreenTrainVolumeMixin) — one OptionInstance definition over one config
            // accessor, so a player who came looking here rather than there finds the same control at
            // the same value. Its tooltip is baked into the option, not set here.
            case TRAIN_VOLUME -> TrainVolumeOption.forModScreen()
                    .createButton(this.minecraft.options, 0, 0, ROW_W);

            // Adult / Kid content mode — the second home for the choice made once on the first-launch
            // consent card, so a player who dismissed that card (or an existing install that answered
            // consent before this feature shipped, and so never sees it) can still find the setting.
            // Syncs to the server immediately so the per-player gates follow without a relog.
            case CONTENT_MODE -> withTip(
                    CycleButton.<ContentMode>builder(DungeonTrainClientOptionsScreen::contentModeLabel)
                            .withValues(List.of(ContentMode.ADULT, ContentMode.KID))
                            .withInitialValue(ClientDisplayConfig.getContentMode())
                            .create(0, 0, ROW_W, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.content_mode"),
                                    (btn, mode) -> {
                                        ClientDisplayConfig.setContentMode(mode);
                                        ContentModeSyncClient.syncNow();
                                    }),
                    // The longest tooltip on the screen, and the one that most has to land: it is the
                    // only place outside the one-time card that says what Kid mode actually does, and
                    // it is where a parent comes to change it.
                    "gui.dungeontrain.options.content_mode.tip");

            // The standing answer to the start-of-world prompt shown when the player has Train Editor
            // edits or an imported dtpack. This is the "can be changed in options" half of that
            // prompt's "Remember decision" checkbox.
            case CUSTOM_CONTENT -> withTip(
                    CycleButton.<CustomContentPreference>builder(
                                    DungeonTrainClientOptionsScreen::customContentLabel)
                            .withValues(List.of(CustomContentPreference.ASK, CustomContentPreference.CONTINUE,
                                    CustomContentPreference.DISABLE))
                            .withInitialValue(ClientDisplayConfig.getCustomContentPreference())
                            .create(0, 0, ROW_W, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.custom_content"),
                                    (btn, pref) -> ClientDisplayConfig.setCustomContentPreference(pref)),
                    "gui.dungeontrain.options.custom_content.tip");

            // Offered only where it is a live concern (Chinese-language clients), so the row is absent
            // rather than merely inert for everyone else. Translated; see the class javadoc.
            case POLITICAL_FILTER -> withTip(
                    CycleButton.onOffBuilder(PoliticalFilterPrefs.isEnabled())
                            .create(0, 0, ROW_W, ROW_H,
                                    Component.translatable("gui.dungeontrain.political_filter.option"),
                                    (btn, on) -> PoliticalFilterPrefs.answer(on)),
                    "gui.dungeontrain.political_filter.option.tooltip");

            // Snapshot max resolution ceiling (0 = AUTO).
            case SNAPSHOT_MAX_RES -> {
                int currentRes = ClientDisplayConfig.getRideSnapshotMaxResolution();
                yield withTip(
                        CycleButton.<Integer>builder(DungeonTrainClientOptionsScreen::resolutionLabel)
                                .withValues(RESOLUTION_VALUES)
                                .withInitialValue(RESOLUTION_VALUES.contains(currentRes) ? currentRes : 0)
                                .create(0, 0, ROW_W, ROW_H,
                                        Component.translatable("gui.dungeontrain.options.snapshot_max_res"),
                                        (btn, value) -> ClientDisplayConfig.setRideSnapshotMaxResolution(value)),
                        "gui.dungeontrain.options.snapshot_max_res.tip");
            }

            case SNAPSHOT_CHAT_LOG -> withTip(
                    CycleButton.onOffBuilder(ClientDisplayConfig.isRideSnapshotChatLogEnabled())
                            .create(0, 0, ROW_W, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.snapshot_chat_log"),
                                    (btn, on) -> ClientDisplayConfig.setRideSnapshotChatLog(on)),
                    "gui.dungeontrain.options.snapshot_chat_log.tip");

            // "The book by X burns" as each DT book catches fire.
            case BOOK_AUTHOR_CHAT -> withTip(
                    CycleButton.onOffBuilder(ClientDisplayConfig.isBookAuthorBurnChatEnabled())
                            .create(0, 0, ROW_W, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.book_author_chat"),
                                    (btn, on) -> ClientDisplayConfig.setBookAuthorBurnChat(on)),
                    "gui.dungeontrain.options.book_author_chat.tip");

            // The binding itself lives in vanilla Controls (Dungeon Train category); this only decides
            // whether it does anything, so a player who wants the key back for something else can free
            // it without hunting through the keybind list.
            case CINEMATIC_HOTKEY -> withTip(
                    CycleButton.onOffBuilder(ClientDisplayConfig.isCinematicHotkeyEnabled())
                            .create(0, 0, ROW_W, ROW_H,
                                    Component.translatable("gui.dungeontrain.options.cinematic_hotkey"),
                                    (btn, on) -> ClientDisplayConfig.setCinematicHotkeyEnabled(on)),
                    "gui.dungeontrain.options.cinematic_hotkey.tip");

            // Master network / internet-connection switch (DP's one-time "use the internet?" consent).
            // OFF revokes immediately; turning it ON routes through DP's informed consent screen rather
            // than silently granting — granting network access gates leaderboard / dev chat / book
            // share / telemetry.
            case INTERNET -> {
                internetButton = Button.builder(internetLabel(), b -> toggleInternet())
                        .bounds(0, 0, ROW_W, ROW_H).build();
                yield withTip(internetButton, "gui.dungeontrain.options.internet.tip");
            }

            case TRANSLATE -> withTip(
                    Button.builder(Component.translatable("gui.dungeontrain.options.translate"),
                                    b -> this.minecraft.setScreen(new TranslationScreen(this, translateTarget)))
                            .bounds(0, 0, ROW_W, ROW_H).build(),
                    "gui.dungeontrain.options.translate.tip");
        };
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
            // Just this label — rebuilding the whole screen would reset the list's scroll position.
            internetButton.setMessage(internetLabel());
        } else {
            this.minecraft.setScreen(new NetworkConsentScreen(this));
        }
    }
}
