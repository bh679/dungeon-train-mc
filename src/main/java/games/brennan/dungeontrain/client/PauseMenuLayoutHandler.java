package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.PauseMenuActionButton;
import games.brennan.dungeontrain.net.AbandonRunPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
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
 *   <li><b>Default:</b> red "Abandon This Run" → closes the menu (unpausing the
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
 * <p>Gated to singleplayer (integrated server present) — multiplayer keeps the
 * vanilla "Disconnect" button. If the Save-and-Quit button can't be located
 * (a third-party mod rewrote the menu) the menu is left untouched, mirroring
 * {@link TitleScreenLayoutHandler}'s defensive stance.</p>
 */
public final class PauseMenuLayoutHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Vanilla singleplayer "Save and Quit to Title" button. */
    private static final Component RETURN_TO_MENU_KEY = Component.translatable("menu.returnToMenu");

    private static final Component ABANDON_LABEL = Component.translatable("gui.dungeontrain.abandon_run");
    private static final Component EXIT_LABEL = Component.translatable("gui.dungeontrain.exit_to_title");
    private static final Component QUIT_LABEL = Component.translatable("menu.quit");

    private static final int GAP = 4;

    private PauseMenuLayoutHandler() {}

    public static void onScreenInitPost(games.brennan.dungeontrain.platform.event.DtScreenInit event) {
        if (!(event.getScreen() instanceof PauseScreen)) {
            return;
        }
        if (!Minecraft.getInstance().hasSingleplayerServer()) {
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

        // Red "Abandon This Run" — full slot, shown when Shift is NOT held.
        PauseMenuActionButton abandon = new PauseMenuActionButton(
                slotX, slotY, slotW, slotH, ABANDON_LABEL,
                1.0F, 0.30F, 0.30F, false,
                b -> abandonRun());
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
     * Toggle each {@link PauseMenuActionButton}'s visibility against the live
     * Shift state every frame, before the screen paints. {@code visible} gates
     * both rendering and click handling, so the Abandon button and the
     * Exit/Quit pair swap cleanly as Shift is pressed and released.
     */
    public static void onScreenRenderPre(net.minecraft.client.gui.screens.Screen screen0) {
        if (!(screen0 instanceof PauseScreen screen)) {
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
     * Close the pause screen first — in singleplayer that unpauses the integrated
     * server so it can process the kill — then ask the server to end the run.
     */
    private static void abandonRun() {
        Minecraft.getInstance().setScreen(null);
        DungeonTrainNet.sendToServer(new AbandonRunPacket());
    }

    private static Button findButton(games.brennan.dungeontrain.platform.event.DtScreenInit event, Component message) {
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof Button button && message.equals(button.getMessage())) {
                return button;
            }
        }
        return null;
    }
}
