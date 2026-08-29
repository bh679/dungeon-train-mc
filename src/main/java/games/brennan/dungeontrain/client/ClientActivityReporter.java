package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.ClientInputPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PlayerPausedPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Reports the activity the server cannot see, for
 * {@link games.brennan.dungeontrain.event.PlayerActivityTracker}: the pause screen opening and
 * closing, and — the reason this class is more than a pause reporter — <b>anything the player does
 * while a screen is open</b>.
 *
 * <p>With a screen up (inventory, a container, a DT menu) the camera does not turn, so the player's
 * server-side yaw and pitch sit perfectly still no matter how much the mouse moves. The server's
 * 30-second mouse rule would call that idle and stop the clock on someone busy sorting their
 * inventory. So while a screen is open, cursor movement is forwarded as look activity and clicks
 * and key presses as input activity.</p>
 *
 * <p>Everything here is edge- or rate-limited: the pause packet only on a state change, the
 * activity packets at most once a second. The server's clocks only care whether something happened
 * inside a window measured in tens of seconds, so a faster stream would buy nothing.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ClientActivityReporter {

    /** At most one activity packet of each kind per second. */
    private static final int REPORT_COOLDOWN_TICKS = 20;

    /** Cursor movement (pixels) below this is jitter, not the player using the menu. */
    private static final double CURSOR_EPSILON_PX = 1.0;

    /** Last pause state sent to the server. Nothing is sent until this changes. */
    private static boolean reportedPaused = false;

    private static double lastCursorX = Double.NaN;
    private static double lastCursorY = Double.NaN;

    /** Client-tick counter, and the tick each kind of report was last sent on. */
    private static int clientTick = 0;
    private static int lastLookReportTick = Integer.MIN_VALUE;
    private static int lastInputReportTick = Integer.MIN_VALUE;

    private ClientActivityReporter() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getConnection() == null) {
            // Left the world — the server drops our state on logout; start clean on the next join.
            reportedPaused = false;
            lastCursorX = Double.NaN;
            lastCursorY = Double.NaN;
            return;
        }
        clientTick++;

        boolean paused = mc.screen != null && mc.screen.isPauseScreen();
        if (paused != reportedPaused) {
            reportedPaused = paused;
            DungeonTrainNet.sendToServer(new PlayerPausedPacket(paused));
        }

        // Only meaningful with a screen open. Without one the camera turns with the mouse and the
        // server samples that directly — no need to say anything.
        if (mc.screen == null) {
            lastCursorX = Double.NaN;
            lastCursorY = Double.NaN;
            return;
        }
        double x = mc.mouseHandler.xpos();
        double y = mc.mouseHandler.ypos();
        boolean moved = !Double.isNaN(lastCursorX)
            && (Math.abs(x - lastCursorX) > CURSOR_EPSILON_PX
                || Math.abs(y - lastCursorY) > CURSOR_EPSILON_PX);
        lastCursorX = x;
        lastCursorY = y;
        if (moved && due(lastLookReportTick)) {
            lastLookReportTick = clientTick;
            DungeonTrainNet.sendToServer(new ClientInputPacket(false));
        }
    }

    @SubscribeEvent
    public static void onScreenMouseButton(ScreenEvent.MouseButtonPressed.Pre event) {
        reportInput();
    }

    @SubscribeEvent
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Post event) {
        reportInput();
    }

    /** Clicking and typing in a menu is real input — the 5-minute clock should hear about it. */
    private static void reportInput() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getConnection() == null) return;
        if (!due(lastInputReportTick)) return;
        lastInputReportTick = clientTick;
        DungeonTrainNet.sendToServer(new ClientInputPacket(true));
    }

    private static boolean due(int lastSentTick) {
        return clientTick - lastSentTick >= REPORT_COOLDOWN_TICKS;
    }
}
