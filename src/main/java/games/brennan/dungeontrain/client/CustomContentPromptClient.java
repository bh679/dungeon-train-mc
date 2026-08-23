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

    /** Package summary from the server while a prompt is waiting to be shown; null = nothing pending. */
    private static String pendingPackages = null;

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
    }

    /** Drop a parked prompt — called when leaving a world so it can't surface in the next one. */
    public static void forget() {
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
        // Wait for a clear screen: the terrain-loading screen is still up right after login, and
        // anything opened underneath it is discarded when it closes.
        if (mc.screen != null) return;

        String packages = pendingPackages;
        forget();
        LOGGER.info("[DungeonTrain] Opening the custom content prompt.");
        mc.setScreen(new CustomContentPromptScreen(packages));
    }
}
