package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.ApprovedModListFetcher;
import games.brennan.dungeontrain.cheat.CheatModListFetcher;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.builder.TrainBuilderMenuButton;
import games.brennan.dungeontrain.client.builder.TrainBuilderScreen;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.videotools.VideoToolsScreen;
import games.brennan.dungeontrain.client.localization.LocalizationCredit;
import games.brennan.dungeontrain.client.localization.LocalizationCreditLabel;
import games.brennan.dungeontrain.client.localization.LocalizationCreditRegistry;
import games.brennan.dungeontrain.client.version.LauncherDetector;
import games.brennan.dungeontrain.client.version.VersionCheckState;
import games.brennan.dungeontrain.client.version.VersionStatusButton;
import games.brennan.dungeontrain.editor.EditorDevMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.List;

/**
 * Restructures the title screen so the NeoForge "Mods" button slot is replaced
 * by a 50/50 split of <b>Train Editor</b> + <b>Video Tools</b>, and the
 * vanilla Options/Quit row absorbs the displaced Mods button as a 33/33/33
 * split of <b>Mods | Options | Quit Game</b>.
 *
 * <p>Video Tools opens the filming guide for content creators. It holds the slot
 * Discord used to occupy — Discord now rides the icon column above Credits (see
 * {@code TitleScreenCreditsButton}), where its logomark says what a word had to
 * say here.</p>
 *
 * <p>The first slot holds a {@link TrainBuilderMenuButton}: normally <b>Train Editor</b>, and
 * <b>Train Builder</b> while Shift is held. The Editor leads because it is the finished tool; the
 * Builder sits behind Shift while it is still being built. Both open {@link TrainBuilderScreen},
 * the four-tile picker — you say what you are building before a world is made either way, and the
 * tile decides which editor category you land in (Editor) or which mode gets stamped (Builder).
 * The world itself, and the one-shot {@link EditorDevMode#queueOnForNextStart()} that forces
 * editor mode on regardless of the {@code CarriageTemplateStore.sourceTreeAvailable()} gate, are
 * the picker's business now — nothing happens here beyond opening it.</p>
 *
 * <p>If any of Mods/Options/Quit can't be located on the title screen (e.g.
 * a third-party mod has already rewritten the menu), this handler logs a
 * warning and leaves the menu untouched rather than half-modifying it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TitleScreenLayoutHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Component VIDEO_TOOLS_LABEL = Component.translatable("gui.dungeontrain.video_tools.button");

    private static final Component MODS_KEY = Component.translatable("fml.menu.mods");
    private static final Component OPTIONS_KEY = Component.translatable("menu.options");
    private static final Component QUIT_KEY = Component.translatable("menu.quit");
    private static final Component LANGUAGE_KEY = Component.translatable("options.language");

    private static final int GAP = 4;

    private TitleScreenLayoutHandler() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
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
        // Same session-memoized title-screen trigger for the official-links overlay: one
        // anonymous relay GET so Discord/Patreon/payment/affiliate links stay current on
        // shipped jars, baked fallbacks when offline.
        OfficialLinks.ensureFetched();
        // Warm the cheat-mod list overlay the same way: one anonymous relay GET so the detection
        // list stays current on shipped jars, baked ∪ disk-cache when offline. (Also refreshed at
        // server boot for dedicated servers, which have no title screen.)
        CheatModListFetcher.ensureFetched();
        // And the approved-mod whitelist, which carries the enforcement switch as well as the list.
        ApprovedModListFetcher.ensureFetched();
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

        // Train Editor by default; holding Shift swaps this same widget to the unfinished Train
        // Builder. The slot is only half a row wide (Video Tools has the other half), so a second
        // button would not fit alongside it. Constructor argument order is (openBuilder,
        // openEditor) — which of the two is the default lives in the button.
        TrainBuilderMenuButton editor = new TrainBuilderMenuButton(slotX, slotY, halfW, slotH,
                () -> openBuilder(titleScreen),
                () -> openEditor(titleScreen));
        event.addListener(editor);

        // Video Tools — the filming guide for content creators. Holds the slot Discord used to
        // occupy; Discord itself now rides the icon column above Credits (TitleScreenCreditsButton),
        // where its logomark carries the meaning a word had to carry here.
        event.addListener(new DarkTintedButton(slotX + halfW + GAP, slotY, halfW, slotH,
                VIDEO_TOOLS_LABEL, b -> {
                    UiAnalytics.click(UiAnalytics.SURFACE_TITLE_SCREEN, UiAnalytics.TARGET_VIDEO_TOOLS);
                    Minecraft.getInstance().setScreen(new VideoToolsScreen(titleScreen));
                }));
    }

    private static Button findButton(ScreenEvent.Init.Post event, Component message) {
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof Button button && message.equals(button.getMessage())) {
                return button;
            }
        }
        return null;
    }

    private static void openBuilder(Screen parent) {
        LOGGER.info("TitleScreenLayout: Train Builder button clicked — opening the builder picker");
        Minecraft.getInstance().setScreen(new TrainBuilderScreen(parent));
    }

    private static void openEditor(Screen parent) {
        LOGGER.info("TitleScreenLayout: Train Editor button clicked — opening the picker");
        Minecraft.getInstance().setScreen(TrainBuilderScreen.forEditor(parent));
    }
}
