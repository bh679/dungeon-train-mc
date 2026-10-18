package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.ClientInputPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PlayerPausedPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import javax.annotation.Nullable;

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
 * <p>The pause state is reported from the screen events themselves, not from the next client tick.
 * Packets sent as a screen closes ({@code BookReadClosedPacket}, {@code PhotographViewClosedPacket})
 * go out from other {@code ScreenEvent.Closing} handlers; reporting a tick later let them reach the
 * server first, where they were judged as sent by a paused — so AFK — player. The tick check stays as
 * a fallback for any screen change that bypasses {@code setScreen}.</p>
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

    /**
     * The screen an {@code Opening} event is replacing the current one with, kept for the
     * {@code Closing} event that {@code Minecraft.setScreen} fires straight after it. Null when the
     * screen is closing to the world.
     */
    @Nullable
    private static Screen pendingOpening = null;

    private static double lastCursorX = Double.NaN;
    private static double lastCursorY = Double.NaN;

    /**
     * Client-tick counter, and the tick each kind of report was last sent on. Seeded one cooldown
     * in the past so the first report is due immediately — NOT to {@link Integer#MIN_VALUE}, whose
     * distance from a small tick count overflows int and comes out negative, wedging the limiter
     * shut for good.
     */
    private static int clientTick = 0;
    private static int lastLookReportTick = -REPORT_COOLDOWN_TICKS;
    private static int lastInputReportTick = -REPORT_COOLDOWN_TICKS;

    private ClientActivityReporter() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getConnection() == null) {
            // Left the world — the server drops our state on logout; start clean on the next join.
            reportedPaused = false;
            pendingOpening = null;
            lastCursorX = Double.NaN;
            lastCursorY = Double.NaN;
            clientTick = 0;
            lastLookReportTick = -REPORT_COOLDOWN_TICKS;
            lastInputReportTick = -REPORT_COOLDOWN_TICKS;
            return;
        }
        clientTick++;

        // Fallback only: the screen events below normally report the change before anything else
        // the screen sends, and this finds nothing left to say.
        reportPaused(mc.screen != null && mc.screen.isPauseScreen());

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

    /**
     * A screen is about to open. LOWEST so the screen seen is the one that actually opens, after
     * any handler has swapped or cancelled it (cancelled events never reach here).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        // setScreen fires Closing for the outgoing screen next — only if there is one.
        pendingOpening = event.getCurrentScreen() != null ? next : null;
        reportPaused(next != null && next.isPauseScreen());
    }

    /**
     * A screen is closing. HIGHEST so the un-pause goes out before any packet another
     * {@code Closing} handler sends for the same screen.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onScreenClosing(ScreenEvent.Closing event) {
        Screen next = nextScreen(pendingOpening, event.getScreen());
        pendingOpening = null;
        reportPaused(next != null && next.isPauseScreen());
    }

    /**
     * Which screen follows the one closing? The one the matching {@code Opening} named, or none
     * (back to the world). A pending screen equal to the closing one is left over from when that
     * screen itself opened, not a replacement.
     */
    @Nullable
    static <S> S nextScreen(@Nullable S pendingOpening, S closing) {
        return pendingOpening != null && pendingOpening != closing ? pendingOpening : null;
    }

    /** Tell the server the pause state, only when it differs from what it last heard. */
    private static void reportPaused(boolean paused) {
        if (paused == reportedPaused) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getConnection() == null) return;
        reportedPaused = paused;
        DungeonTrainNet.sendToServer(new PlayerPausedPacket(paused));
    }

    @SubscribeEvent
    public static void onScreenMouseButton(ScreenEvent.MouseButtonPressed.Pre event) {
        reportInput();
    }

    /** Pre, not Post: Post only fires for keys the screen ignored, which misses Esc, Enter and arrows. */
    @SubscribeEvent
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
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
        return due(clientTick, lastSentTick, REPORT_COOLDOWN_TICKS);
    }

    /**
     * Is a report due? Pure so the seeding can be tested — a sentinel far enough below zero makes
     * this subtraction overflow and silently disable every report.
     */
    static boolean due(int nowTick, int lastSentTick, int cooldownTicks) {
        return nowTick - lastSentTick >= cooldownTicks;
    }
}
