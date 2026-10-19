package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PlayerPausedPacket;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Opens the pause menu when nobody has touched the keyboard or mouse for a while: at least
 * {@link #IDLE_PAUSE_MS} (2 min) in the world, or {@link #IDLE_PAUSE_SCREEN_MS} (5 min) with a screen
 * open (a book, a chest, the inventory, the photo viewer, a DT popup). Reading or sorting takes longer
 * than looking around, so a screen gets the longer grace.
 *
 * <p><b>Timed here, not on the server.</b> {@link games.brennan.dungeontrain.event.PlayerActivityTracker}
 * calls a player idle after 30 s of a still mouse, which is right for stopping the train-time clocks
 * but far too quick for a menu dropping over the game. And in singleplayer a book or photo is a pause
 * screen, so the integrated server stops ticking and stops reporting. The client keeps its own
 * wall-clock stamp of the last real input instead: a key press, mouse button or scroll, the camera
 * turning, or the cursor moving on a screen. A key merely held down does not count, as on the server.</p>
 *
 * <p>The server's not-getting-anywhere rule ({@code PlayerActivityTracker.Reason.NO_PROGRESS}) no
 * longer pauses on its own: a pause may only come after the idle wait, and by then this one fires
 * anyway. It still stops the train clock and advancements, as before.</p>
 *
 * <p><b>One pause per idle spell.</b> Once fired, nothing more until the next input, so a player who
 * leaves the menu open is not hit again.</p>
 *
 * <p>In singleplayer the integrated server stops ticking behind the pause menu, so the train does not
 * carry an absent player into danger. In multiplayer it only opens the menu. Never on the pause menu
 * itself or its settings screens, never in Free Play (read from the client-visible Free Play badge, as
 * {@link AdvancementTabGate} does), and never in VR, where a menu dropping into the headset
 * unannounced is jarring.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class AfkAutoPause {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Idle this long (2 min) in the world and the pause menu opens. */
    public static final long IDLE_PAUSE_MS = 120_000L;

    /** Idle this long (5 min) with a screen open and the pause menu opens. */
    public static final long IDLE_PAUSE_SCREEN_MS = 300_000L;

    /** Camera turn (degrees, either axis) that counts as the mouse moving. Matches the server's. */
    private static final float LOOK_EPSILON_DEG = 0.5f;

    /** Cursor movement (pixels) on a screen below this is jitter. Matches {@link ClientActivityReporter}. */
    private static final double CURSOR_EPSILON_PX = 1.0;

    /** Wall-clock ms of the last real input; {@code 0} = not in a world yet. */
    private static long lastInputMs = 0L;

    /** Has this idle spell already paused the game? Cleared by the next input. */
    private static boolean firedThisSpell = false;

    private static float lastYaw = Float.NaN;
    private static float lastPitch = Float.NaN;
    private static double lastCursorX = Double.NaN;
    private static double lastCursorY = Double.NaN;

    private AfkAutoPause() {}

    // ------------------------------------------------------------ pure rules

    /** How long idle before the pause menu opens: longer with a screen open. */
    public static long thresholdMs(boolean screenOpen) {
        return screenOpen ? IDLE_PAUSE_SCREEN_MS : IDLE_PAUSE_MS;
    }

    /**
     * Pure decision: pause once the player has been idle past {@link #thresholdMs}, once per idle
     * spell, with the setting on, not already on the pause menu or its settings, not in Free Play and
     * not in VR.
     */
    public static boolean shouldPause(long idleMs, boolean screenOpen, boolean onPauseMenu,
                                      boolean firedThisSpell, boolean enabled, boolean freePlay,
                                      boolean vrActive) {
        if (!enabled || onPauseMenu || freePlay || vrActive || firedThisSpell) return false;
        return idleMs >= thresholdMs(screenOpen);
    }

    /**
     * Does the camera turn from {@code prev} to now count as the mouse moving? Yaw on the shortest
     * arc, so 359° → 1° is 2°. {@code NaN} means no baseline yet: not movement.
     */
    static boolean lookMoved(float prevYaw, float prevPitch, float yaw, float pitch) {
        if (Float.isNaN(prevYaw) || Float.isNaN(prevPitch)) return false;
        return Math.abs(Mth.wrapDegrees(yaw - prevYaw)) > LOOK_EPSILON_DEG
            || Math.abs(pitch - prevPitch) > LOOK_EPSILON_DEG;
    }

    // ------------------------------------------------------------------ input

    /** The player did something: restart the idle clock and re-arm the pause. */
    private static void markInput() {
        lastInputMs = System.currentTimeMillis();
        firedThisSpell = false;
    }

    /** {@code receiveCanceled}: a key a cinematic or menu swallowed was still pressed. */
    @SubscribeEvent(receiveCanceled = true)
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() == InputConstants.PRESS) markInput();
    }

    @SubscribeEvent(receiveCanceled = true)
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        markInput();
    }

    @SubscribeEvent(receiveCanceled = true)
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        markInput();
    }

    /** Screens can consume input before the {@link InputEvent}s fire; these cover that case. */
    @SubscribeEvent(receiveCanceled = true)
    public static void onScreenKey(ScreenEvent.KeyPressed.Pre event) {
        markInput();
    }

    @SubscribeEvent(receiveCanceled = true)
    public static void onScreenMouseButton(ScreenEvent.MouseButtonPressed.Pre event) {
        markInput();
    }

    @SubscribeEvent(receiveCanceled = true)
    public static void onScreenScroll(ScreenEvent.MouseScrolled.Pre event) {
        markInput();
    }

    // ------------------------------------------------------------------- tick

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            // Left the world: the next join starts fresh, as if just at the keyboard.
            lastInputMs = 0L;
            firedThisSpell = false;
            lastYaw = lastPitch = Float.NaN;
            lastCursorX = lastCursorY = Double.NaN;
            return;
        }
        if (lastInputMs == 0L) markInput();
        trackMouse(mc);

        if (!mc.player.isAlive() || mc.player.isSpectator()) {
            markInput();
            return;
        }
        boolean screenOpen = mc.screen != null;
        long idleMs = System.currentTimeMillis() - lastInputMs;
        if (shouldPause(idleMs, screenOpen, ClientActivityReporter.onGameMenu(), firedThisSpell,
                ClientDisplayConfig.isAfkPauseEnabled(), mc.player.hasEffect(ModMobEffects.FREE_PLAY),
                VrCompat.isVivecraftPresent())) {
            firedThisSpell = true;
            LOGGER.info("[DungeonTrain] Auto-pause: idle {} s{}", idleMs / 1000L,
                screenOpen ? " on " + mc.screen.getClass().getSimpleName() : "");
            mc.pauseGame(false);
            // After the ordinary pause report, so the server counts this pause as an auto-pause.
            DungeonTrainNet.sendToServer(new PlayerPausedPacket(true, screenOpen
                ? PlayerPausedPacket.CAUSE_AUTO_SCREEN : PlayerPausedPacket.CAUSE_AUTO_WORLD));
        }
    }

    /**
     * The camera turning with no screen up, or the cursor moving with one, is the mouse in use. The
     * baselines are reset across the switch so opening a screen is not itself read as movement.
     */
    private static void trackMouse(Minecraft mc) {
        if (mc.screen == null) {
            lastCursorX = lastCursorY = Double.NaN;
            float yaw = mc.player.getYRot();
            float pitch = mc.player.getXRot();
            if (lookMoved(lastYaw, lastPitch, yaw, pitch)) markInput();
            lastYaw = yaw;
            lastPitch = pitch;
            return;
        }
        lastYaw = lastPitch = Float.NaN;
        double x = mc.mouseHandler.xpos();
        double y = mc.mouseHandler.ypos();
        boolean moved = !Double.isNaN(lastCursorX)
            && (Math.abs(x - lastCursorX) > CURSOR_EPSILON_PX
                || Math.abs(y - lastCursorY) > CURSOR_EPSILON_PX);
        lastCursorX = x;
        lastCursorY = y;
        if (moved) markInput();
    }
}
