package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.CinematicIntroPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.ChunkSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * Client-side gate that holds the spawn intro cinematic behind a short loading
 * screen ({@link CinematicLoadingScreen}) until the terrain around the shot has
 * streamed in — so the fly-up reveals a fully-rendered world instead of chunks
 * popping in.
 *
 * <p><b>Why.</b> The server triggers the cinematic the instant the train settles
 * ({@code PlayerJoinEvents.tryPlace} → {@code CinematicIntroService.play}), which
 * is right after the client is teleported onto the moving train and is still
 * downloading the surrounding chunks. Starting the camera then shows a half-built
 * world. This gate defers {@link CinematicCameraController#start} until the
 * chunks near the camera-start position are present client-side (or a cap
 * elapses), covering the gap the vanilla world-load screen can't reach.</p>
 *
 * <p><b>Readiness.</b> The gate keys off the packet's camera-start world XZ
 * ({@code camX/camZ}) — a real world-space position in the starting dimension
 * (not the player's on-ship, shipyard-space coords) whose surrounding terrain is
 * exactly what the shot reveals. It waits until every chunk within
 * {@link #PRELOAD_CHUNK_RADIUS} of that column is present
 * ({@link ChunkSource#hasChunk}), then a short {@link #SETTLE_TICKS} cushion for
 * mesh builds to finish, enforcing a {@link #MIN_SHOW_TICKS} floor and the
 * packet-supplied max-wait ceiling.</p>
 *
 * <p>All methods run on the client main thread (packet {@code enqueueWork},
 * client tick). Single-instance static state — only one local player.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CinematicPreloadGate {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Chunks each side of the camera-start column that must be present before the reveal. */
    private static final int PRELOAD_CHUNK_RADIUS = 4;

    /** Ticks of full-presence cushion before starting — lets section mesh builds finish. */
    private static final int SETTLE_TICKS = 15;

    /** Never flash the loading screen for less than this — avoids a one-frame blink. */
    private static final int MIN_SHOW_TICKS = 10;

    private static boolean active = false;
    private static CinematicIntroPacket pending;
    private static int elapsedTicks;
    private static int readyStreak;
    private static int capTicks;
    private static double lastFraction;

    private CinematicPreloadGate() {}

    /**
     * Begin the preload wait for {@code packet} (called from
     * {@link CinematicIntroPacket#handle} when a preload budget is set). Opens
     * the loading screen and arms the per-tick readiness check. If the world
     * isn't available (defensive — the packet arrives post-join), the cinematic
     * starts immediately.
     */
    public static void begin(CinematicIntroPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            CinematicCameraController.start(packet);
            return;
        }
        pending = packet;
        elapsedTicks = 0;
        readyStreak = 0;
        lastFraction = 0.0;
        capTicks = Math.max(1, packet.preloadMaxWaitTicks());
        active = true;
        mc.setScreen(new CinematicLoadingScreen());
        LOGGER.info("[DungeonTrain] Cinematic preload gate: waiting for chunks around ({}, {}) cap={}t",
            String.format("%.1f", packet.camX()), String.format("%.1f", packet.camZ()), capTicks);
    }

    /** Live 0..1 chunk-load fraction for {@link CinematicLoadingScreen} to render. */
    public static double progress() {
        return lastFraction;
    }

    /** True while the gate is holding the cinematic behind the loading screen. */
    public static boolean isActive() {
        return active;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            reset();
            return;
        }
        // Single-player pause (focus-loss / Escape from an overlay): the
        // integrated server is stopped, so no chunks stream — hold without
        // advancing the clock, and don't fight the pause menu for the screen.
        if (mc.isPaused()) return;

        // Keep our loading screen up (belt-and-suspenders: our screen blocks
        // Escape and focus-loss auto-pause, but re-assert if anything cleared it).
        if (!(mc.screen instanceof CinematicLoadingScreen)) {
            mc.setScreen(new CinematicLoadingScreen());
        }

        lastFraction = computeFraction(mc);
        if (lastFraction >= 1.0) {
            readyStreak++;
        } else {
            readyStreak = 0;
        }
        elapsedTicks++;

        boolean settled = elapsedTicks >= MIN_SHOW_TICKS && readyStreak >= SETTLE_TICKS;
        boolean cappedOut = elapsedTicks >= capTicks;
        if (settled || cappedOut) {
            finish(mc, cappedOut && !settled);
        }
    }

    /** Fraction of chunks present within {@link #PRELOAD_CHUNK_RADIUS} of the camera-start column. */
    private static double computeFraction(Minecraft mc) {
        ChunkSource source = mc.level.getChunkSource();
        int centerCx = (int) Math.floor(pending.camX()) >> 4;
        int centerCz = (int) Math.floor(pending.camZ()) >> 4;
        int total = 0;
        int present = 0;
        for (int dx = -PRELOAD_CHUNK_RADIUS; dx <= PRELOAD_CHUNK_RADIUS; dx++) {
            for (int dz = -PRELOAD_CHUNK_RADIUS; dz <= PRELOAD_CHUNK_RADIUS; dz++) {
                total++;
                if (source.hasChunk(centerCx + dx, centerCz + dz)) present++;
            }
        }
        return total == 0 ? 1.0 : present / (double) total;
    }

    /** Close the loading screen and hand off to the cinematic. */
    private static void finish(Minecraft mc, boolean timedOut) {
        CinematicIntroPacket packet = pending;
        int elapsed = elapsedTicks;
        double fraction = lastFraction;
        reset();
        if (mc.screen instanceof CinematicLoadingScreen) {
            mc.setScreen(null);
        }
        LOGGER.info("[DungeonTrain] Cinematic preload gate done ({}): {}t elapsed, fraction={}",
            timedOut ? "cap reached" : "chunks ready", elapsed, String.format("%.2f", fraction));
        if (packet != null) {
            CinematicCameraController.start(packet);
        }
    }

    private static void reset() {
        active = false;
        pending = null;
        readyStreak = 0;
        lastFraction = 0.0;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }
}
