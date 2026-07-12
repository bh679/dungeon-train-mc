package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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
 * <p>Defensive reset: {@link TitleScreenLayoutHandler} clears the flag on every
 * title-screen init — covers the "user bailed back to title before world
 * finished loading" case. A previous version also cleared on
 * {@code ClientPlayerNetworkEvent.LoggingOut}, but that event fires spuriously
 * during the integrated-server handshake when transitioning from the title
 * screen into a fresh world and would wipe the flag before the tick handler
 * ever saw it.</p>
 */
public final class EditorAutoOpenHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DISPATCH_DELAY_TICKS = 40; // 2s @ 20Hz

    private static volatile boolean pending = false;
    private static int delayTicksRemaining = -1; // -1 = not yet started counting
    private static long waitTickLogCounter = 0;

    private EditorAutoOpenHandler() {}

    public static void queueAutoOpen() {
        pending = true;
        delayTicksRemaining = -1;
        waitTickLogCounter = 0;
        LOGGER.info("EditorAutoOpen: armed (pending=true)");
    }

    public static void clear() {
        if (pending) {
            LOGGER.info("EditorAutoOpen: cleared while pending (caller={})",
                    Thread.currentThread().getStackTrace()[2]);
        }
        pending = false;
        delayTicksRemaining = -1;
    }

    public static void onClientTickPost() {
        if (!pending) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean playerReady = mc.player != null;
        boolean levelReady = mc.level != null;
        boolean connReady = mc.getConnection() != null;
        boolean gameModeReady = mc.gameMode != null;
        if (!playerReady || !levelReady || !connReady || !gameModeReady) {
            if (++waitTickLogCounter % 20 == 0) {
                LOGGER.info("EditorAutoOpen: waiting — player={}, level={}, conn={}, gameMode={}",
                        playerReady, levelReady, connReady, gameModeReady);
            }
            return;
        }
        if (delayTicksRemaining < 0) {
            delayTicksRemaining = DISPATCH_DELAY_TICKS;
            LOGGER.info("EditorAutoOpen: conditions met — dispatching `dungeontrain editor` in {} ticks",
                    DISPATCH_DELAY_TICKS);
            return;
        }
        if (--delayTicksRemaining > 0) {
            return;
        }
        pending = false;
        delayTicksRemaining = -1;
        waitTickLogCounter = 0;
        LOGGER.info("EditorAutoOpen: dispatching `/dungeontrain editor` now");

        // Visible-in-chat dispatch:
        //  1. show a heads-up in the chat overlay so the player knows the
        //     auto-open just fired (and any error from the server side will
        //     land in the same chat below it).
        //  2. add the command to chat history (up-arrow recall).
        //  3. send via player.connection.sendCommand — the same path the
        //     vanilla chat screen uses for typed commands, which produces
        //     normal server-side feedback (success/error messages).
        if (mc.gui != null) {
            mc.gui.getChat().addMessage(Component.literal("§7[Train Editor] auto-running /dungeontrain editor"));
            mc.gui.getChat().addMessage(Component.literal("§e[Train Editor] please wait a few seconds…"));
            mc.gui.getChat().addRecentChat("/dungeontrain editor");
        }
        mc.player.connection.sendCommand("dungeontrain editor");
    }

    // Intentionally NOT clearing on ClientPlayerNetworkEvent.LoggingOut.
    // That event fires during the integrated-server handshake when transitioning
    // from title screen to a fresh world (no prior connection to log out from),
    // which would wipe the freshly-armed flag before the tick handler ever sees
    // it ready. The TitleScreenLayoutHandler.onScreenInitPost clear handles the
    // genuine "user bailed back to title" case on its own.
}
