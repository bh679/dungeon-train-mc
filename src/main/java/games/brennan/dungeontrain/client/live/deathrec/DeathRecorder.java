package games.brennan.dungeontrain.client.live.deathrec;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.live.FfmpegSupport;
import games.brennan.dungeontrain.client.live.LiveAspect;
import games.brennan.dungeontrain.client.live.LiveDeathClip;
import games.brennan.dungeontrain.client.live.LiveFrameGrabber;
import games.brennan.dungeontrain.client.live.audio.LiveAudioPump;
import games.brennan.dungeontrain.compat.ReplayModRecordingProbe;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Saves the death cinematic as a video (Options &gt; General &gt; Record Deaths, off by default).
 *
 * <p>While the setting is on and the player is in a world, the finished frame is grabbed every
 * render ({@link LiveFrameGrabber}, the Live Feed's grabber) and encoded with the game's sound
 * ({@link LiveAudioPump}) by {@link DeathRecEncoder} into memory ({@link DeathRecRing}), which keeps
 * only the last {@link #PRE_ROLL_MS} and a second's margin. The death cinematic opening
 * ({@link #markDeath}) holds the ring; the cinematic closing ({@link #finish}) hands everything held
 * to {@link DeathVideoSaver} on a worker thread. The picture is what the player saw: HUD before the
 * death, the cinematic's wash and prompts after it.</p>
 *
 * <p>With the setting off nothing runs: no grabber, no ffmpeg, no audio mirror. Without ffmpeg the
 * setting waits for it. Render thread, except the encoder's own threads and the save.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DeathRecorder {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final int FPS = 30;
    static final int BOX_W = 1280;
    static final int BOX_H = 720;
    /** Seconds kept from before the death. */
    static final long PRE_ROLL_MS = 8_000;
    /** After the death, wait this long before cutting the death page's replay: the encoder runs a little behind. */
    static final long SCREEN_CLIP_DELAY_MS = 1_000;
    /** On top of the pre-roll: the ring's front can sit up to one keyframe interval before a decodable frame. */
    static final long KEYFRAME_MARGIN_MS = 1_000;
    /** A held recording stops growing after this long (a death screen left open). */
    static final long HOLD_CAP_MS = 180_000;
    /** After a failed start or an encoder that died, wait this long before trying again. */
    static final long RETRY_MS = 60_000;
    static final long CLOSE_GRACE_MS = 3_000;

    /** How long game shutdown waits for a save in progress (the raw file is written first, so it is kept). */
    static final long SHUTDOWN_SAVE_WAIT_MS = 5_000;
    private static final ExecutorService SAVER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dt-death-rec-save");
        t.setDaemon(true);
        return t;
    });

    @Nullable private static LiveFrameGrabber grabber;
    @Nullable private static DeathRecEncoder encoder;
    @Nullable private static LiveAudioPump pump;
    /** The running encoder's memory. Each encoder gets a new one, so a late write from the last can't reach it. */
    @Nullable private static DeathRecRing ring;
    /** The game is closing: saves skip the remux and keep the raw recording. */
    private static volatile boolean shuttingDown;
    /**
     * Between login and logout. {@code mc.level} outlives the logout event by a few ticks, so it
     * alone would start a new encoder while the old world is being torn down.
     */
    private static boolean connected;
    /** When the held death began, for the death page's replay; -1 once it is cut (or there is none). */
    private static long screenClipDueMs = -1;
    /** The death screen clip's death ({@link LiveDeathClip#generation()} at the death). */
    private static int screenClipGeneration;
    private static long lastGrabNs;
    private static long retryAtMs;

    private DeathRecorder() {}

    /** Whether the recorder is running now (setting on, in a world, ffmpeg ready). */
    public static boolean recording() {
        return encoder != null;
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean want = connected && !shuttingDown && ClientDisplayConfig.isDeathRecordingEnabled()
            && mc.level != null && mc.player != null
            && !ReplayModRecordingProbe.isReplaying();
        DeathRecEncoder enc = encoder;
        if (enc != null && enc.died()) {
            LOGGER.warn("[DungeonTrain] death recording encoder stopped; retrying in {} s", RETRY_MS / 1000);
            finish();
            stop(false);
            retryAtMs = System.currentTimeMillis() + RETRY_MS;
            return;
        }
        cutScreenClipWhenDue(mc);
        if (want && encoder == null && System.currentTimeMillis() >= retryAtMs) tryStart(mc);
        // Never mid-death: the cinematic's own close saves first.
        if (!want && encoder != null && !holding()) stop(false);
    }

    private static void tryStart(Minecraft mc) {
        FFmpeg ffmpeg = FfmpegSupport.get();
        if (ffmpeg == null) return; // not installed (yet): wait for it
        CompletableFuture<Boolean> aac = FfmpegSupport.aacEncoder();
        if (!aac.isDone()) return; // asked once per launch off-thread; start silent-or-not once it answers
        boolean sound = Boolean.TRUE.equals(aac.getNow(false));
        RenderTarget main = mc.getMainRenderTarget();
        int[] size = LiveAspect.frameSize(BOX_W, BOX_H, main.width, main.height);
        LiveFrameGrabber grab = new LiveFrameGrabber(size[0], size[1], false);
        LiveAudioPump audio = sound ? LiveAudioPump.open(true) : null;
        DeathRecRing memory = new DeathRecRing(PRE_ROLL_MS + KEYFRAME_MARGIN_MS, HOLD_CAP_MS);
        try {
            DeathRecEncoder enc = new DeathRecEncoder(ffmpeg, grab, FPS, audio == null ? 0 : audio.port(), memory);
            if (audio != null) audio.start(enc::framesWritten, FPS);
            grabber = grab;
            pump = audio;
            ring = memory;
            encoder = enc;
            LOGGER.info("[DungeonTrain] death recording on: {}x{} at {} fps, {}", size[0], size[1], FPS,
                audio != null ? "with sound" : "silent");
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] death recording could not start: {}", e.toString());
            if (audio != null) audio.close();
            grab.close();
            retryAtMs = System.currentTimeMillis() + RETRY_MS;
        }
    }

    /**
     * Ends the encoder. {@code now} (game shutdown): the short grace on this thread; otherwise on a
     * worker, the grabber then closed back on the render thread as it must be.
     */
    private static void stop(boolean now) {
        DeathRecEncoder enc = encoder;
        LiveAudioPump audio = pump;
        LiveFrameGrabber grab = grabber;
        encoder = null;
        pump = null;
        grabber = null;
        ring = null;
        if (enc == null) return;
        LOGGER.info("[DungeonTrain] death recording off");
        if (now) {
            enc.close(500);
            if (audio != null) audio.close();
            if (grab != null) grab.close();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Thread t = new Thread(() -> {
            enc.close(CLOSE_GRACE_MS);
            if (audio != null) audio.close();
            if (grab != null) mc.execute(grab::close);
        }, "dt-death-rec-stop");
        t.setDaemon(true);
        t.start();
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        connected = true;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        connected = false;
        finish();
        stop(false);
    }

    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        // No time for a remux: keep the raw recording (it plays as it is). A save the logout
        // already queued is waited for, so the game does not exit in the middle of writing it.
        shuttingDown = true;
        finish();
        SAVER.shutdown();
        try {
            if (!SAVER.awaitTermination(SHUTDOWN_SAVE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                LOGGER.warn("[DungeonTrain] death video still saving as the game closes");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stop(true);
    }

    // ---- capture ------------------------------------------------------------------------------

    /** The finished frame, as the Live Feed grabs it: NORMAL priority, before LOW/LOWEST overlays draw. */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onRenderFrameEnd(RenderFrameEvent.Post event) {
        LiveFrameGrabber grab = grabber;
        if (grab == null || encoder == null) return;
        long now = System.nanoTime();
        long period = 1_000_000_000L / FPS;
        if (now - lastGrabNs < period * 9 / 10) return;
        lastGrabNs = now;
        grab.grab(Minecraft.getInstance().getMainRenderTarget());
    }

    // ---- the death ----------------------------------------------------------------------------

    /** The death cinematic opened: keep the pre-roll and everything from here. */
    public static void markDeath() {
        DeathRecRing memory = ring;
        if (encoder == null || memory == null) return;
        long now = System.currentTimeMillis();
        memory.hold(now);
        screenClipDueMs = now + SCREEN_CLIP_DELAY_MS;
        screenClipGeneration = LiveDeathClip.generation();
        LOGGER.info("[DungeonTrain] death recording: holding {} s before the death", memory.spanMs(now) / 1000);
    }

    /**
     * A second after the death, the last seconds before it go to the death page as its looping
     * backdrop, the way a streamer's own death replays there ({@link LiveDeathClip#offerLocal}).
     * The full recording keeps going until the cinematic ends.
     */
    private static void cutScreenClipWhenDue(Minecraft mc) {
        if (screenClipDueMs < 0 || System.currentTimeMillis() < screenClipDueMs) return;
        screenClipDueMs = -1;
        DeathRecRing memory = ring;
        FFmpeg ffmpeg = FfmpegSupport.get();
        if (memory == null || !memory.holding() || ffmpeg == null || SAVER.isShutdown()) return;
        byte[] ts = memory.snapshot();
        int gen = screenClipGeneration;
        Path out = LiveDeathClip.localClipPath();
        SAVER.execute(() -> {
            Path clip = DeathScreenClip.cut(ffmpeg, ts, out);
            if (clip != null) mc.execute(() -> LiveDeathClip.offerLocal(clip, gen));
        });
    }

    private static boolean holding() {
        DeathRecRing memory = ring;
        return memory != null && memory.holding();
    }

    /** The death cinematic closed (any way): save what was held. A no-op when nothing is held. */
    public static void finish() {
        DeathRecRing memory = ring;
        if (memory == null || !memory.holding()) return;
        byte[] ts = memory.takeAndReset();
        if (ts.length == 0) return;
        LocalDateTime when = LocalDateTime.now();
        FFmpeg ffmpeg = FfmpegSupport.get();
        Path dir = videoDir();
        Minecraft mc = Minecraft.getInstance();
        if (SAVER.isShutdown()) {
            // Past the shutdown wait (a screen closed during the game's own teardown): write it here.
            DeathVideoSaver.save(dir, ts, when, null, false);
            return;
        }
        SAVER.execute(() -> {
            Path file = DeathVideoSaver.save(dir, ts, when, ffmpeg, !shuttingDown);
            if (file != null) LOGGER.info("[DungeonTrain] death video saved: {} ({} KB)", file, ts.length / 1024);
            if (!shuttingDown) mc.execute(() -> DeathVideoSaver.announce(file));
        });
    }

    static Path videoDir() {
        return PlayerDataPaths.dir(PlayerDataPaths.DEATH_VIDEOS);
    }
}
