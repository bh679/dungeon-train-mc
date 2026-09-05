package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.builder.BuilderProfileScreen;
import games.brennan.dungeontrain.client.builder.BuilderWorldCheck;
import games.brennan.dungeontrain.client.menu.AbandonConfirmScreen;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.shaders.ShaderMenuScreen;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.menu.PauseMenuActionButton;
import games.brennan.dungeontrain.client.version.VersionCheckState;
import games.brennan.dungeontrain.client.version.VersionStatusButton;
import games.brennan.dungeontrain.net.AbandonRunPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Reframes the singleplayer pause menu around Dungeon Train's roguelike loop:
 * the vanilla <b>Save and Quit to Title</b> slot ({@code menu.returnToMenu})
 * becomes a single <b>red "Abandon This Run"</b> button that ends the current
 * run, with the normal exits tucked behind Shift.
 *
 * <ul>
 *   <li><b>Default:</b> red "Abandon This Run" → an {@link AbandonConfirmScreen} that says
 *       outright that abandoning kills you; confirming closes the menu (unpausing the
 *       integrated server) and sends {@link AbandonRunPacket}, which kills the
 *       player server-side → the narrative death screen (same flow as a normal
 *       death).</li>
 *   <li><b>Shift held:</b> the red button is replaced in-place by two muted
 *       buttons — <b>Exit to Title</b> (grey → {@link DeathScreenLayoutHandler#goToTitleScreen()})
 *       and <b>Quit Game</b> (dark grey → {@link DeathScreenLayoutHandler#quitToDesktop()}).</li>
 * </ul>
 *
 * <p>This handler lays the three buttons over the original slot, hides the
 * vanilla button, and — because {@code AbstractWidget.render} is {@code final} —
 * drives the Shift swap from a {@code ScreenEvent.Render.Pre} pass that toggles
 * each {@link PauseMenuActionButton}'s {@code visible} flag every frame.</p>
 *
 * <p>Also mirrors the title screen's top-left {@link VersionStatusButton}
 * (version label + release-check status) onto the pause menu, for both
 * singleplayer and multiplayer, skipping the widgetless F3+Esc pause.</p>
 *
 * <p>A creative player also gets a <b>My Builds</b> row immediately above that slot — the
 * relay profile of everything they have authored and uploaded, from the Train Editor or the
 * Train Builder. See {@link #addMyBuildsRow}. It is the same button the Train Builder's own
 * pause menu carries; this is what makes it reachable from an ordinary world, where the
 * editor's sky plots live and the only other way in is the worldspace editor menu (which
 * needs you to be standing in a plot).</p>
 *
 * <p>The Abandon-run reshuffle is gated to singleplayer (integrated server present) — multiplayer keeps the
 * vanilla "Disconnect" button, and with it loses the My Builds row, since both hang off
 * the slot this handler takes over. If the Save-and-Quit button can't be located
 * (a third-party mod rewrote the menu) the menu is left untouched, mirroring
 * {@link TitleScreenLayoutHandler}'s defensive stance.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PauseMenuLayoutHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Vanilla singleplayer "Save and Quit to Title" button. */
    private static final Component RETURN_TO_MENU_KEY = Component.translatable("menu.returnToMenu");

    private static final Component ABANDON_LABEL = Component.translatable("gui.dungeontrain.abandon_run");
    private static final Component EXIT_LABEL = Component.translatable("gui.dungeontrain.exit_to_title");
    private static final Component QUIT_LABEL = Component.translatable("menu.quit");
    /** The same key the Train Builder's own pause menu uses — one name for one screen. */
    private static final Component MY_BUILDS_LABEL = Component.translatable("gui.dungeontrain.builder.profile");

    private static final Component MODS_KEY = Component.translatable("fml.menu.mods");
    private static final Component SHADERS_LABEL = Component.translatable("gui.dungeontrain.shaders.button");

    private static final int GAP = 4;

    private PauseMenuLayoutHandler() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen pauseScreen)) {
            return;
        }

        // Same combined version label + release-check widget the title screen
        // carries, in the same top-left spot. Skipped for the invisible
        // F3+Esc pause (no menu shown) and independent of the singleplayer
        // gate below so multiplayer players still see the version line.
        if (pauseScreen.showsPauseMenu()) {
            VersionCheckState.ensureChecked();
            event.addListener(new VersionStatusButton(4, 4));
        }

        // Shaders beside Mods, the same pairing the title screen makes: both answer "what else is
        // installed". Done before the singleplayer gate below so it is there in multiplayer too —
        // a shader pack is a client-side choice and has nothing to do with whose world this is.
        if (pauseScreen.showsPauseMenu()) {
            addShadersBesideMods(event);
        }

        if (!Minecraft.getInstance().hasSingleplayerServer()) {
            return;
        }
        // Train Builder worlds keep vanilla's Save-and-Quit slot untouched: there is no run to
        // abandon, and "Save and Quit to Title" is exactly what you want when you're done
        // building. Leaving the takeover out entirely (rather than just hiding the red button)
        // avoids an empty slot that only fills in when Shift is held.
        if (BuilderWorldCheck.isBuilderWorld()) {
            return;
        }

        Button returnToMenu = findButton(event, RETURN_TO_MENU_KEY);
        if (returnToMenu == null) {
            LOGGER.warn("PauseMenuLayout: could not locate Save-and-Quit button ({}); leaving menu untouched.",
                    RETURN_TO_MENU_KEY.getString());
            return;
        }

        int slotX = returnToMenu.getX();
        int slotY = returnToMenu.getY();
        int slotW = returnToMenu.getWidth();
        int slotH = returnToMenu.getHeight();
        int halfW = (slotW - GAP) / 2;

        // Neutralise the vanilla button but leave it in the listener list (harmless).
        returnToMenu.visible = false;
        returnToMenu.active = false;

        // My Builds, for a creative player — see addMyBuildsRow. It takes the Save-and-Quit slot and
        // pushes the exits down a row, which puts it last among the things you can *do* and first
        // above the ways out: the same place it sits in the Train Builder's own pause menu.
        if (addMyBuildsRow(event, slotX, slotY, slotW, slotH)) {
            slotY += slotH + GAP;
        }

        // Red "Abandon This Run" — full slot, shown when Shift is NOT held.
        PauseMenuActionButton abandon = new PauseMenuActionButton(
                slotX, slotY, slotW, slotH, ABANDON_LABEL,
                1.0F, 0.30F, 0.30F, false,
                b -> confirmAbandonRun(event.getScreen()));
        event.addListener(abandon);

        // Shift-revealed pair, splitting the same slot: Exit to Title (grey) | Quit Game (dark grey).
        PauseMenuActionButton exitTitle = new PauseMenuActionButton(
                slotX, slotY, halfW, slotH, EXIT_LABEL,
                1.0F, 1.0F, 1.0F, true,
                b -> DeathScreenLayoutHandler.goToTitleScreen());
        event.addListener(exitTitle);

        PauseMenuActionButton quitGame = new PauseMenuActionButton(
                slotX + halfW + GAP, slotY, slotW - halfW - GAP, slotH, QUIT_LABEL,
                0.50F, 0.50F, 0.50F, true,
                b -> DeathScreenLayoutHandler.quitToDesktop());
        event.addListener(quitGame);

        applyShiftVisibility(abandon, exitTitle, quitGame);
    }

    /**
     * Add the <b>My Builds</b> row, or don't — the caller shifts the exits down only when it lands.
     *
     * <p>Shown to a <b>creative</b> player and nobody else. What the screen lists is the templates
     * you have authored and uploaded, from the Train Editor or the Train Builder, and both of those
     * are creative-mode tools; a survival run has nothing to put in it. Read fresh on every init,
     * which is every press of ESC, so switching game mode is reflected the next time the menu opens.
     *
     * <p>Builder worlds never reach here (the caller returns early for them) — they have this button
     * already, in a pause menu of their own.
     *
     * @return true when the row was added and the slot below it is now spoken for
     */
    private static boolean addMyBuildsRow(ScreenEvent.Init.Post event, int x, int y, int width, int height) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !player.isCreative()) {
            return false;
        }
        Screen screen = event.getScreen();
        event.addListener(new DarkTintedButton(x, y, width, height, MY_BUILDS_LABEL,
                b -> Minecraft.getInstance().setScreen(new BuilderProfileScreen(screen))));
        return true;
    }

    /**
     * Toggle each {@link PauseMenuActionButton}'s visibility against the live
     * Shift state every frame, before the screen paints. {@code visible} gates
     * both rendering and click handling, so the Abandon button and the
     * Exit/Quit pair swap cleanly as Shift is pressed and released.
     */
    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof PauseScreen screen)) {
            return;
        }
        for (GuiEventListener listener : screen.children()) {
            if (listener instanceof PauseMenuActionButton button) {
                applyShiftVisibility(button);
            }
        }
    }

    private static void applyShiftVisibility(PauseMenuActionButton... buttons) {
        boolean shift = Screen.hasShiftDown();
        for (PauseMenuActionButton button : buttons) {
            button.visible = shift == button.visibleWhenShift();
        }
    }

    /**
     * Ask first. The button says "abandon"; what it does is kill you, and a misclick on the pause
     * menu should not be able to end a run silently. {@link AbandonConfirmScreen} says the quiet
     * part — you will die — and only then runs {@link #abandonRun()}.
     *
     * @param pauseScreen where cancelling goes back to
     */
    private static void confirmAbandonRun(Screen pauseScreen) {
        Minecraft.getInstance().setScreen(
                new AbandonConfirmScreen(pauseScreen, PauseMenuLayoutHandler::abandonRun));
    }

    /**
     * Close the pause screen first — in singleplayer that unpauses the integrated
     * server so it can process the kill — then ask the server to end the run.
     *
     * <p>The coming death is flagged as an abandon first: with the
     * {@code doImmediateRespawn} game rule on, {@link InstantRespawnReboard} would
     * otherwise reboard straight into a fresh world, and a player who deliberately
     * ended the run should see the recap and pick what happens next.</p>
     */
    private static void abandonRun() {
        InstantRespawnReboard.expectAbandonedRun();
        Minecraft.getInstance().setScreen(null);
        DungeonTrainNet.sendToServer(new AbandonRunPacket());
    }

    /**
     * Halve the Mods button's slot and put Shaders in the other half.
     *
     * <p>Splitting the slot rather than inserting a row means this does not need to know what else
     * shares that row or how the rest of the pause menu is laid out — including if another mod has
     * already rearranged it. If Mods is not there at all, nothing is added: an orphan Shaders button
     * floating where a row used to be is worse than no button.</p>
     */
    private static void addShadersBesideMods(ScreenEvent.Init.Post event) {
        Button mods = findButton(event, MODS_KEY);
        if (mods == null) {
            LOGGER.warn("PauseMenuLayout: could not locate the Mods button; skipping Shaders.");
            return;
        }
        int halfW = (mods.getWidth() - GAP) / 2;
        int shadersX = mods.getX() + halfW + GAP;
        mods.setWidth(halfW);
        event.addListener(new DarkTintedButton(shadersX, mods.getY(), halfW, mods.getHeight(),
                SHADERS_LABEL, b -> {
                    UiAnalytics.click(UiAnalytics.SURFACE_PAUSE_MENU, UiAnalytics.TARGET_SHADERS);
                    Minecraft.getInstance().setScreen(new ShaderMenuScreen(event.getScreen()));
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
}
