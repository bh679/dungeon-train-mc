package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * One-shot post-world-load auto-opener for the editor.
 *
 * <p>Armed by the title-screen "Train Editor" button via {@link #queueAutoOpen()};
 * fires the chat command {@code /dungeontrain editor} (the same command the
 * X-menu "Editor" entry runs) on the first client tick where the local player
 * and connection are both ready.</p>
 *
 * <p>Defensive resets:</p>
 * <ul>
 *   <li>{@link ClientPlayerNetworkEvent.LoggingOut} — clears the flag if the
 *       player disconnects before the tick handler consumes it, so the next
 *       world join doesn't accidentally inherit a stale pending request.</li>
 *   <li>{@link TitleScreenLayoutHandler} also clears the flag on every
 *       title-screen init, covering the case where world loading fails and
 *       the user is bounced back to the title without a logout event.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class EditorAutoOpenHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean pending = false;

    private EditorAutoOpenHandler() {}

    public static void queueAutoOpen() {
        pending = true;
        LOGGER.info("EditorAutoOpen: armed (pending=true)");
    }

    public static void clear() {
        if (pending) {
            LOGGER.info("EditorAutoOpen: cleared while pending (caller={})",
                    Thread.currentThread().getStackTrace()[2]);
        }
        pending = false;
    }

    private static long waitTickLogCounter = 0;

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (!pending) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean playerReady = mc.player != null;
        boolean levelReady = mc.level != null;
        boolean connReady = mc.getConnection() != null;
        if (!playerReady || !levelReady || !connReady) {
            if (++waitTickLogCounter % 20 == 0) {
                LOGGER.info("EditorAutoOpen: waiting — player={}, level={}, conn={}",
                        playerReady, levelReady, connReady);
            }
            return;
        }
        waitTickLogCounter = 0;
        pending = false;
        LOGGER.info("EditorAutoOpen: dispatching `dungeontrain editor` on first ready tick");
        CommandRunner.run("dungeontrain editor");
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (pending) {
            LOGGER.info("EditorAutoOpen: cleared by LoggingOut while pending");
        }
        pending = false;
    }
}
