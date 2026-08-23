package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.CustomContentPreference;
import games.brennan.dungeontrain.net.CustomContentChoicePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * Client-side entry point for the custom-content prompt. The server asks once per
 * world; the client either surfaces the screen or — if the player ticked
 * "Remember decision" on a previous world — answers silently so the world starts
 * with no interruption.
 *
 * <p>Mirrors {@link FreePlayConfirmClient}, with two differences. The preference
 * is tri-state rather than a single opt-out flag, so the remembered answer has to
 * carry <em>which</em> answer it is. And the prompt arrives at login, while the
 * terrain-loading screen is still up — calling {@code setScreen} there would put
 * the prompt behind a screen the client is about to replace, and the player would
 * never see it. So the request is parked and opened from the client tick once the
 * player is actually in the world with nothing else on screen, the same hazard
 * {@link DeveloperWelcomePopupHandler} works around.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CustomContentPromptClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Package summary from the server while a prompt is outstanding; null = nothing pending.
     *
     * <p>Deliberately NOT cleared when the screen opens — only when the player actually answers
     * ({@link #answered()}). Anything else calling {@code setScreen} replaces our screen via
     * {@code removed()} rather than {@code onClose()}, so the prompt would vanish without sending
     * a response and the player would never see it. Keeping the request pending means the next
     * clear-screen tick puts it straight back up.</p>
     */
    private static String pendingPackages = null;

    /** Ticks between re-open attempts. Something else holding the screen is usually transient. */
    private static final int RETRY_TICKS = 40;
    /** Give up after this many attempts (~40s) rather than fight for the screen forever. */
    private static final int MAX_ATTEMPTS = 20;

    private static int retryCooldown = 0;
    private static int attempts = 0;

    private CustomContentPromptClient() {}

    public static void onShow(String packages) {
        CustomContentPreference remembered = ClientDisplayConfig.getCustomContentPreference();
        if (!remembered.asks()) {
            LOGGER.info("[DungeonTrain] Custom content prompt answered from the remembered preference: {}",
                remembered);
            DungeonTrainNet.sendToServer(new CustomContentChoicePacket(remembered.keepsContent()));
            return;
        }
        LOGGER.info("[DungeonTrain] Custom content prompt received ({}) — waiting for a clear screen.",
            packages);
        pendingPackages = packages;
        retryCooldown = 0;
        attempts = 0;
    }

    /** Drop a parked prompt — called when leaving a world so it can't surface in the next one. */
    public static void forget() {
        pendingPackages = null;
        retryCooldown = 0;
        attempts = 0;
    }

    /**
     * The player answered: stop re-showing the prompt. Called by
     * {@link CustomContentPromptScreen} at the moment it sends the response, so the only thing
     * that retires a pending prompt is a real answer.
     */
    public static void answered() {
        pendingPackages = null;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (pendingPackages == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            // Disconnected before the prompt could be shown — the world keeps its UNSET choice and
            // will ask again next join.
            LOGGER.info("[DungeonTrain] Left the world before the custom content prompt could open.");
            forget();
            return;
        }
        // The cinematic intro owns the screen while it runs: CinematicPreloadGate.onClientTick
        // reinstates its own CinematicLoadingScreen whenever anything else takes mc.screen, and it
        // runs on this same event. Opening here would be a tug-of-war neither side wins — and the
        // player is watching the intro anyway. Wait it out without spending a retry.
        if (CinematicPreloadGate.isActive() || CinematicCameraController.isActive()) return;

        // Wait for a clear screen: the terrain-loading screen is still up right after login, and
        // anything opened underneath it is discarded when it closes.
        if (mc.screen != null) return;

        // Throttle. The prompt survives being replaced (see pendingPackages), but if something is
        // closing screens every tick — a cinematic intro, another mod's takeover — retrying at tick
        // rate turns that into an open/close storm. Back off, and eventually concede: the run is
        // already carrying the Free Play effect, and the world stays UNSET so the next join asks
        // again, which is a far better outcome than a screen that flickers forever.
        if (retryCooldown > 0) {
            retryCooldown--;
            return;
        }
        if (attempts >= MAX_ATTEMPTS) {
            LOGGER.warn("[DungeonTrain] Gave up opening the custom content prompt after {} attempts "
                + "— something keeps closing it. The world stays unanswered and will ask again.",
                attempts);
            forget();
            return;
        }
        retryCooldown = RETRY_TICKS;
        attempts++;
        if (attempts == 1) {
            LOGGER.info("[DungeonTrain] Opening the custom content prompt.");
        } else {
            LOGGER.info("[DungeonTrain] Re-opening the custom content prompt (attempt {}) — it was "
                + "closed before it could be answered.", attempts);
        }
        mc.setScreen(new CustomContentPromptScreen(pendingPackages));
    }
}
