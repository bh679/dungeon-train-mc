package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.builder.BuilderMode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * One-shot post-world-load dispatcher for the title screen's Train Editor / Train Builder
 * buttons.
 *
 * <p>Both buttons create a fresh world and then need to do something once it is actually up.
 * That "wait for player + level + connection + gameMode, then wait a beat, then act" loop is
 * the tricky part, so it lives here once and the armed action decides what happens at the end
 * of it:</p>
 *
 * <ul>
 *   <li>{@link #queueAutoOpen()} — fires the chat command {@code /dungeontrain editor} (the
 *       same command the X-menu "Editor" entry runs).</li>
 *   <li>{@link #queueBuilderStub(BuilderMode)} — posts a "coming soon" line for the chosen
 *       builder mode. The four builder editors are a follow-up task; this is the seam where
 *       each one gets hooked up.</li>
 * </ul>
 *
 * <p>Defensive reset: {@link TitleScreenLayoutHandler} clears the flag on every
 * title-screen init — covers the "user bailed back to title before world
 * finished loading" case. A previous version also cleared on
 * {@code ClientPlayerNetworkEvent.LoggingOut}, but that event fires spuriously
 * during the integrated-server handshake when transitioning from the title
 * screen into a fresh world and would wipe the flag before the tick handler
 * ever saw it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class EditorAutoOpenHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DISPATCH_DELAY_TICKS = 40; // 2s @ 20Hz

    /**
     * What to do once the world is up. A {@code null} {@code builderMode} means the technical
     * editor; any other value means that builder mode's stub.
     */
    private record PendingAction(BuilderMode builderMode) {
        boolean isEditor() {
            return builderMode == null;
        }
    }

    private static volatile PendingAction pending = null;
    private static int delayTicksRemaining = -1; // -1 = not yet started counting
    private static long waitTickLogCounter = 0;

    private EditorAutoOpenHandler() {}

    public static void queueAutoOpen() {
        arm(new PendingAction(null), "editor");
    }

    /** Arm the "coming soon" stub for a Train Builder mode once its flat world has loaded. */
    public static void queueBuilderStub(BuilderMode mode) {
        arm(new PendingAction(mode), "builder:" + mode.id());
    }

    private static void arm(PendingAction action, String describe) {
        pending = action;
        delayTicksRemaining = -1;
        waitTickLogCounter = 0;
        LOGGER.info("EditorAutoOpen: armed ({})", describe);
    }

    public static void clear() {
        if (pending != null) {
            LOGGER.info("EditorAutoOpen: cleared while pending (caller={})",
                    Thread.currentThread().getStackTrace()[2]);
        }
        pending = null;
        delayTicksRemaining = -1;
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        PendingAction action = pending;
        if (action == null) {
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
            LOGGER.info("EditorAutoOpen: conditions met — dispatching in {} ticks", DISPATCH_DELAY_TICKS);
            return;
        }
        if (--delayTicksRemaining > 0) {
            return;
        }
        pending = null;
        delayTicksRemaining = -1;
        waitTickLogCounter = 0;

        if (action.isEditor()) {
            dispatchEditor(mc);
        } else {
            dispatchBuilderStub(mc, action.builderMode());
        }
    }

    private static void dispatchEditor(Minecraft mc) {
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

    private static void dispatchBuilderStub(Minecraft mc, BuilderMode mode) {
        LOGGER.info("EditorAutoOpen: builder mode '{}' selected — posting coming-soon stub", mode.id());
        if (mc.gui == null) {
            return;
        }
        mc.gui.getChat().addMessage(Component.translatable("gui.dungeontrain.builder.coming_soon",
                        Component.translatable(mode.labelKey()))
                .withStyle(ChatFormatting.YELLOW));
    }

    // Intentionally NOT clearing on ClientPlayerNetworkEvent.LoggingOut.
    // That event fires during the integrated-server handshake when transitioning
    // from title screen to a fresh world (no prior connection to log out from),
    // which would wipe the freshly-armed flag before the tick handler ever sees
    // it ready. The TitleScreenLayoutHandler.onScreenInitPost clear handles the
    // genuine "user bailed back to title" case on its own.
}
