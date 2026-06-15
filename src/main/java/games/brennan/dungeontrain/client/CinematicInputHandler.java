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
 *   <li>{@link ScreenEvent.Opening} → inventory / chat are cancelled to protect the
 *       cutscene, but the pause menu is allowed (window-focus-loss / Escape). While any
 *       screen owns input the in-world blockers above stand down and
 *       {@link CinematicCameraController#clientTick()} suspends the clock, so the menu is
 *       fully usable and the cinematic resumes from where it paused on close.</li>
 *   <li>{@link ClientPlayerNetworkEvent.LoggingOut} → hard reset.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CinematicInputHandler {

    private CinematicInputHandler() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
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

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!CinematicCameraController.isActive()) return;
        if (Minecraft.getInstance().screen != null) return;
        event.setCanceled(true);
        if (event.getAction() == InputConstants.PRESS) {
            CinematicSkipHudOverlay.show();
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!CinematicCameraController.isActive()) return;
        if (Minecraft.getInstance().screen != null) return;
        event.setCanceled(true);
        CinematicSkipHudOverlay.show();
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!CinematicCameraController.isActive()) return;
        if (Minecraft.getInstance().screen != null) return;
        event.setCanceled(true);
        CinematicSkipHudOverlay.show();
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!CinematicCameraController.isActive()) return;
        // Allow the pause menu (window-focus-loss auto-pause / Escape) to open so the
        // player can use it; the cinematic suspends and resumes when the menu closes.
        if (event.getNewScreen() instanceof PauseScreen) return;
        // Keep blocking inventory / chat / etc. to preserve the cutscene.
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        // The camera is detached during the cinematic; suppress the first-person
        // held item so it doesn't float in the shot.
        if (CinematicCameraController.isActive()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        CinematicCameraController.forceStop();
    }
}
