package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;

import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.menu.PulsingDiscordButton;
import games.brennan.dungeontrain.client.localization.LocalizationCredit;
import games.brennan.dungeontrain.client.localization.LocalizationCreditLabel;
import games.brennan.dungeontrain.client.localization.LocalizationCreditRegistry;
import games.brennan.dungeontrain.client.version.LauncherDetector;
import games.brennan.dungeontrain.client.version.VersionCheckState;
import games.brennan.dungeontrain.client.version.VersionStatusButton;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.editor.EditorDevMode;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.net.URI;
import java.util.List;

/**
 * Restructures the title screen so the NeoForge "Mods" button slot is replaced
 * by a 50/50 split of <b>Dungeon Train Editor</b> + <b>Discord</b>, and the
 * vanilla Options/Quit row absorbs the displaced Mods button as a 33/33/33
 * split of <b>Mods | Options | Quit Game</b>.
 *
 * <p>Discord opens {@value #DISCORD_URL} via {@link ConfirmLinkScreen}. The
 * Editor button launches a fresh creative world via
 * {@link DevQuickWorldHandler#launchEditorWorld(Screen)} — which names the
 * world "train editor N" using the lowest unused index — and arms
 * {@link EditorDevMode#queueOnForNextStart()} so editor mode is forced on
 * after the server finishes starting, regardless of the
 * {@code CarriageTemplateStore.sourceTreeAvailable()} gate.</p>
 *
 * <p>If any of Mods/Options/Quit can't be located on the title screen (e.g.
 * a third-party mod has already rewritten the menu), this handler logs a
 * warning and leaves the menu untouched rather than half-modifying it.</p>
 */
public final class TitleScreenLayoutHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DISCORD_URL = "https://discord.gg/jdKAwb6rbW";

    private static final Component DISCORD_LABEL = Component.translatable("gui.dungeontrain.discord_button");
    private static final Component EDITOR_LABEL = Component.translatable("gui.dungeontrain.editor_button");

    private static final Component MODS_KEY = Component.translatable("fml.menu.mods");
    private static final Component OPTIONS_KEY = Component.translatable("menu.options");
    private static final Component QUIT_KEY = Component.translatable("menu.quit");
    private static final Component LANGUAGE_KEY = Component.translatable("options.language");

    private static final int GAP = 4;

    private TitleScreenLayoutHandler() {}

    public static void onScreenInitPost(games.brennan.dungeontrain.platform.event.DtScreenInit event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }
        LOGGER.info("TitleScreenLayout: Init.Post fired on TitleScreen@{}",
                System.identityHashCode(titleScreen));

        // Combined version label + release-check widget — top-left, the same
        // spot the old VersionMenuOverlay used. Runs independently of the
        // Editor/Discord reshuffle below so the version line still appears
        // even if a third-party mod has already rewritten the menu. The
        // LauncherDetector touch warms its cache so the detected source is
        // logged early for diagnostics, not lazily on first click.
        VersionCheckState.ensureChecked();
        LauncherDetector.source();
        event.addListener(new VersionStatusButton(4, 4));

        // Thank-you text for whoever shipped a resource pack translating the game into
        // the player's CURRENTLY SELECTED language — sits immediately left of vanilla's
        // own language-select button so it reads as an annotation on that button, not a
        // separate menu section. Silent (no widget added) unless a credit exists for the
        // active locale, so stock installs never see it.
        Button language = findButton(event, LANGUAGE_KEY);
        if (language == null) {
            LOGGER.warn("TitleScreenLayout: could not locate the vanilla language button; skipping localization credit.");
        } else {
            String locale = Minecraft.getInstance().getLanguageManager().getSelected();
            List<LocalizationCredit> credits = LocalizationCreditRegistry.creditsFor(locale);
            LocalizationCreditLabel creditLabel = LocalizationCreditLabel.createLeftOf(
                    titleScreen, credits, language.getX(), language.getY() + language.getHeight(), GAP);
            if (creditLabel != null) {
                event.addListener(creditLabel);
            }
        }

        // Defensive: if the user bailed mid-world-load, the auto-open flag
        // would still be armed. Reaching the title screen means we have no
        // pending join, so drop it.
        EditorAutoOpenHandler.clear();

        Button mods = findButton(event, MODS_KEY);
        Button options = findButton(event, OPTIONS_KEY);
        Button quit = findButton(event, QUIT_KEY);

        if (mods == null || options == null || quit == null) {
            LOGGER.warn("TitleScreenLayout: could not locate Mods/Options/Quit (mods={}, options={}, quit={}); skipping reshuffle and not adding Editor/Discord.",
                    mods != null, options != null, quit != null);
            return;
        }
        LOGGER.info("TitleScreenLayout: found all three buttons, applying reshuffle + adding Editor/Discord");

        int slotX = mods.getX();
        int slotY = mods.getY();
        int slotW = mods.getWidth();
        int slotH = mods.getHeight();
        int halfW = (slotW - GAP) / 2;

        int rowLeft = Math.min(options.getX(), quit.getX());
        int rowRight = Math.max(options.getX() + options.getWidth(), quit.getX() + quit.getWidth());
        int rowY = options.getY();
        int rowWidth = rowRight - rowLeft;
        int thirdW = (rowWidth - 2 * GAP) / 3;

        mods.setX(rowLeft);
        mods.setY(rowY);
        mods.setWidth(thirdW);

        options.setX(rowLeft + thirdW + GAP);
        options.setY(rowY);
        options.setWidth(thirdW);

        quit.setX(rowLeft + 2 * (thirdW + GAP));
        quit.setY(rowY);
        quit.setWidth(thirdW);

        DarkTintedButton editor = new DarkTintedButton(slotX, slotY, halfW, slotH,
                EDITOR_LABEL, b -> openEditor(titleScreen));
        event.addListener(editor);

        // If the player opted out of the developer welcome popup, keep the
        // Discord affordance gently visible via a pulsing blue border —
        // they can still find their way to the channel without being
        // re-prompted by a modal.
        boolean optedOut = ClientDisplayConfig.isDeveloperPopupOptedOut();
        // Stand down while the menu-chat envelope pulses over unread messages — one pulse at a time.
        Button discord = optedOut
                ? new PulsingDiscordButton(slotX + halfW + GAP, slotY, halfW, slotH,
                        DISCORD_LABEL, b -> openDiscord(titleScreen),
                        games.brennan.dungeontrain.client.chat.MenuChatButtonHandler::hasUnreadPulse)
                : Button.builder(DISCORD_LABEL, b -> openDiscord(titleScreen))
                        .bounds(slotX + halfW + GAP, slotY, halfW, slotH)
                        .build();
        event.addListener(discord);
    }

    private static Button findButton(games.brennan.dungeontrain.platform.event.DtScreenInit event, Component message) {
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof Button button && message.equals(button.getMessage())) {
                return button;
            }
        }
        return null;
    }

    private static void openDiscord(Screen parent) {
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) {
                Util.getPlatform().openUri(URI.create(DISCORD_URL));
            }
            Minecraft.getInstance().setScreen(parent);
        }, DISCORD_URL, true));
    }

    private static void openEditor(Screen parent) {
        LOGGER.info("TitleScreenLayout: Train Editor button clicked — queueing devmode + auto-open and launching fresh world");
        EditorDevMode.queueOnForNextStart();
        EditorAutoOpenHandler.queueAutoOpen();
        DevQuickWorldHandler.launchEditorWorld(parent);
    }
}
