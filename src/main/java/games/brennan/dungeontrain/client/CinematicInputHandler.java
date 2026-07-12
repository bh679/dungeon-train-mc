package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Locks player input while the spawn intro cinematic is active and routes the
 * skip control.
 *
 * <ul>
 *   <li>{@link ClientTickEvent.Pre} → drive the cinematic clock + re-assert the
 *       frozen movement input.</li>
 *   <li>{@link InputEvent.Key} → Space ends the cinematic; any other key reveals
 *       the "Press Space to skip" prompt. (Key events aren't cancelable, but
 *       movement is frozen and screens/interactions are blocked, so non-Space
 *       keys do nothing except surface the hint.)</li>
 *   <li>Mouse buttons / scroll / interaction key-mappings → cancelled (swallow
 *       attack/use/zoom) and surface the hint.</li>
 *   <li>{@link ScreenEvent.Opening} → opening inventory / chat <em>straight from the
 *       cutscene</em> is cancelled to protect the shot, but the pause menu is allowed
 *       (window-focus-loss / Escape). Once a screen is already open, navigating onward to
 *       its sub-screens (Options / Advancements / Stats / confirm-link …) is allowed — gated
 *       on {@code getCurrentScreen() != null}. While any screen owns input the in-world
 *       blockers above stand down and {@link CinematicCameraController#clientTick()} suspends
 *       the clock, so the menu is fully usable and the cinematic resumes from where it paused
 *       on close.</li>
 *   <li>{@link ClientPlayerNetworkEvent.LoggingOut} → hard reset.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CinematicInputHandler {

    private CinematicInputHandler() {}

    public static void onClientTick() {
        CinematicCameraController.clientTick();
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!CinematicCameraController.isActive()) return;
        if (Minecraft.getInstance().screen != null) return;
        if (event.getAction() != InputConstants.PRESS) return;
        if (event.getKey() == InputConstants.KEY_SPACE) {
            CinematicCameraController.skip();
        } else {
            CinematicSkipHudOverlay.show();
        }
    }

    public static boolean onMouseButton(int button, int action, int modifiers) {
        if (!CinematicCameraController.isActive()) return false;
        if (Minecraft.getInstance().screen != null) return false;
        if (action == InputConstants.PRESS) {
            CinematicSkipHudOverlay.show();
        }
        return true; // former event.setCanceled(true)
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!CinematicCameraController.isActive()) return;
        if (Minecraft.getInstance().screen != null) return;
        event.setCanceled(true);
        CinematicSkipHudOverlay.show();
    }

    public static void onInteraction(games.brennan.dungeontrain.platform.event.DtInteractionInput input) {
        if (!CinematicCameraController.isActive()) return;
        if (Minecraft.getInstance().screen != null) return;
        input.setCanceled(true);
        CinematicSkipHudOverlay.show();
    }

    public static void onScreenOpening(games.brennan.dungeontrain.platform.event.DtScreenOpening event) {
        if (!CinematicCameraController.isActive()) return;
        // A screen is already open ⇒ the cinematic is suspended and the player is in
        // menu-land; allow every in-menu navigation (Options / Advancements / Stats /
        // confirm-link, etc.). getCurrentScreen() is the screen being replaced — it is
        // null only when opening straight from the cutscene (HUD-only, no screen).
        if (event.getCurrentScreen() != null) return;
        // From the cutscene itself, allow only the pause menu (window-focus-loss
        // auto-pause / Escape) so the cinematic suspends and resumes when it closes.
        if (event.getNewScreen() instanceof PauseScreen) return;
        // Keep blocking inventory / chat / etc. to preserve the cutscene.
        event.setCanceled(true);
    }

    public static boolean onRenderHand() {
        // The camera is detached during the cinematic; suppress the first-person
        // held item so it doesn't float in the shot. Returning true cancels the
        // hand render (former event.setCanceled(true)).
        return CinematicCameraController.isActive();
    }

    public static void onLoggingOut() {
        CinematicCameraController.forceStop();
    }
}
