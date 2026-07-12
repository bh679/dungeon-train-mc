package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.CinematicIntroPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Client-side gate that keeps a themed loading screen ({@link CinematicLoadingScreen})
 * up across the whole spawn sequence — from world-entry, through the server placing
 * the player on the train, until the terrain around the intro shot has streamed in —
 * so the fly-up reveals a fully-rendered world and the player never sees the raw
 * spawn (or falls through not-yet-collided terrain).
 *
 * <p>Two phases:</p>
 * <ul>
 *   <li><b>PLACING</b> — armed at login by {@link CinematicPreloadBeginPacket} (via
 *       {@link #arm}). From the first frame the player is in-world, the screen is
 *       shown and the player's position is <em>locked</em> each tick so they don't
 *       fall while the train settles server-side. Ends when the
 *       {@link CinematicIntroPacket} arrives (body placed on the train).</li>
 *   <li><b>CHUNKS</b> — entered by {@link #begin}. The player is now riding the deck
 *       (held by {@code SpawnDeckHold}); the gate stops locking and instead waits for
 *       the chunks around the camera-start position to be present, then a short settle,
 *       then starts the cinematic. Skippable early with Space ×3 ({@link #skip}). Once
 *       that wait is satisfied, the gate additionally holds for {@link LoadingStories}
 *       to finish its story ({@link #isWaitingForStory}) — a single Space press
 *       ({@link #confirmStart}) skips just that remainder.</li>
 * </ul>
 *
 * <p>Keying chunk readiness off the packet's camera-start world XZ ({@code camX/camZ})
 * avoids the player's on-ship (shipyard-space) coords — see memory
 * {@code project_sable_tick_vs_render_coords}.</p>
 *
 * <p>All methods run on the client main thread (packet {@code enqueueWork}, client tick).</p>
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

    /** Display progress reserved for the placing phase; chunk load fills the rest. */
    private static final double PLACING_FRACTION = 0.15;

    private enum Phase { IDLE, PLACING, CHUNKS }

    private static Phase phase = Phase.IDLE;
    private static double localFraction = 0.0;

    // PLACING state.
    private static int placeElapsed;
    private static int placeTimeout;
    private static Vec3 freezePos;

    // CHUNKS state.
    private static CinematicIntroPacket pending;
    private static int elapsedTicks;
    private static int readyStreak;
    private static int capTicks;
    /** Sticky — once the chunk wait itself is satisfied, stays true even if it later regresses. */
    private static boolean loadingReady;
    private static String loadingReadyReason;

    private CinematicPreloadGate() {}

    /**
     * Arm the placing phase (called from {@link CinematicPreloadBeginPacket} at
     * login). Holds the loading screen up and freezes the player from world-entry
     * until {@link #begin} switches to the chunk wait, or {@code placeTimeoutTicks}
     * elapse (train never appeared → release the player into the world).
     */
    public static void arm(int placeTimeoutTicks) {
        if (phase != Phase.IDLE) return; // already running
        phase = Phase.PLACING;
        placeTimeout = Math.max(1, placeTimeoutTicks);
        placeElapsed = 0;
        freezePos = null;
        localFraction = 0.0;
        LOGGER.info("[DungeonTrain] Cinematic preload gate armed (placing): timeout={}t", placeTimeout);
    }

    /**
     * Enter the chunk-wait phase for {@code packet} (called from
     * {@link CinematicIntroPacket#handle} when a preload budget is set). Stops the
     * placing freeze and waits for the terrain around the shot before starting the
     * cinematic. Works standalone if {@link #arm} was never received.
     */
    public static void begin(CinematicIntroPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            CinematicCameraController.start(packet);
            reset();
            return;
        }
        pending = packet;
        phase = Phase.CHUNKS;
        elapsedTicks = 0;
        readyStreak = 0;
        freezePos = null; // stop the placing freeze — the player now rides the deck
        capTicks = Math.max(1, packet.preloadMaxWaitTicks());
        if (!(mc.screen instanceof CinematicLoadingScreen)) {
            mc.setScreen(new CinematicLoadingScreen());
        }
        LOGGER.info("[DungeonTrain] Cinematic preload gate: waiting for chunks around ({}, {}) cap={}t",
            String.format("%.1f", packet.camX()), String.format("%.1f", packet.camZ()), capTicks);
    }

    /**
     * Overall (cross-screen) progress for {@link CinematicLoadingScreen} to render —
     * this phase's own local 0..1 model, folded into the shared
     * {@link LoadingSequenceProgress} timeline so the bar never resets at the
     * handoff from the themed world-load screen.
     */
    public static double progress() {
        return LoadingSequenceProgress.reportGate(localFraction);
    }

    /** True while the gate is holding the cinematic behind the loading screen. */
    public static boolean isActive() {
        return phase != Phase.IDLE;
    }

    /** True only once the player is on the train and the wait may be skipped (chunk phase). */
    public static boolean canSkip() {
        return phase == Phase.CHUNKS;
    }

    /**
     * True once the chunk wait itself is satisfied but the loading sequence is
     * holding for {@link LoadingStories} to finish its current story before
     * revealing the cinematic — {@link CinematicLoadingScreen} shows a
     * "press Space to start" prompt in this state, and a single press (via
     * {@link #confirmStart}) skips the rest of the wait.
     */
    public static boolean isWaitingForStory() {
        return phase == Phase.CHUNKS && loadingReady && !LoadingStories.isFinished();
    }

    /**
     * Player pressed Space while {@link #isWaitingForStory()} — the world itself
     * is already ready, so a single press (not the ×3 early-skip below) is enough.
     */
    public static void confirmStart() {
        if (!isWaitingForStory()) return;
        finish(Minecraft.getInstance(), loadingReadyReason + ", confirmed by player");
    }

    /**
     * Player asked to skip the chunk wait (Space ×3 on the loading screen) — start
     * the cinematic immediately even if it isn't otherwise ready. Only valid once
     * placed on the train; a no-op during placing (skipping then would drop the
     * player mid-fall).
     */
    public static void skip() {
        if (phase != Phase.CHUNKS) return;
        finish(Minecraft.getInstance(), "skipped");
    }

    /**
     * Catches the vanilla {@link LevelLoadingScreen} closing to no screen
     * (world considered "ready" from vanilla's perspective) and substitutes
     * {@link CinematicLoadingScreen} in the same synchronous call — same
     * substitution trick as {@code DeathScreenLayoutHandler}. Without this,
     * the swap only happened on the next {@link #onClientTick}, up to a
     * client-tick's worth of raw, unrendered world showing through first.
     */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (phase == Phase.IDLE) return;
        if (!(event.getCurrentScreen() instanceof LevelLoadingScreen)) return;
        if (event.getNewScreen() instanceof CinematicLoadingScreen) return;
        event.setNewScreen(new CinematicLoadingScreen());
    }

    public static void onClientTick() {
        if (phase == Phase.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return; // wait for world-entry (logout resets)

        // Keep our loading screen up across the whole hold. Because it is non-pausing
        // and non-Esc, this also blocks the focus-loss auto-pause, so the integrated
        // server keeps streaming chunks behind it. onScreenOpening above already
        // catches the common case (vanilla closing LevelLoadingScreen) synchronously;
        // this is the fallback for any other path that clears mc.screen.
        if (!(mc.screen instanceof CinematicLoadingScreen)) {
            mc.setScreen(new CinematicLoadingScreen());
        }

        if (phase == Phase.PLACING) {
            tickPlacing(mc);
        } else {
            tickChunks(mc);
        }
    }

    /** Lock the player's position until the train placement arrives (or time out). */
    private static void tickPlacing(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (freezePos == null) {
            freezePos = p.position();
        }
        // Pin position + kill velocity every tick so client-authoritative gravity
        // can't free-fall the player through not-yet-collided terrain.
        p.setDeltaMovement(Vec3.ZERO);
        p.setPos(freezePos.x, freezePos.y, freezePos.z);
        p.xo = freezePos.x;
        p.yo = freezePos.y;
        p.zo = freezePos.z;
        p.setOnGround(true);
        p.fallDistance = 0.0f;

        placeElapsed++;
        localFraction = PLACING_FRACTION * Math.min(1.0, placeElapsed / (double) placeTimeout);
        if (placeElapsed >= placeTimeout) {
            LOGGER.warn("[DungeonTrain] Cinematic preload gate: placing timed out after {}t — releasing player", placeElapsed);
            release(mc);
        }
    }

    /**
     * Wait for the terrain around the shot, then start the cinematic — but not
     * before {@link LoadingStories} finishes its current story. Once the chunk
     * wait itself is satisfied, {@link #loadingReady} latches and every further
     * tick just re-checks whether the story is done yet ({@link #isWaitingForStory()}
     * drives the "press Space to start" prompt on {@link CinematicLoadingScreen}
     * in the meantime).
     */
    private static void tickChunks(Minecraft mc) {
        if (!loadingReady) {
            double chunkFraction = computeChunkFraction(mc);
            localFraction = Math.max(localFraction, PLACING_FRACTION + (1.0 - PLACING_FRACTION) * chunkFraction);
            if (chunkFraction >= 1.0) {
                readyStreak++;
            } else {
                readyStreak = 0;
            }
            elapsedTicks++;

            boolean settled = elapsedTicks >= MIN_SHOW_TICKS && readyStreak >= SETTLE_TICKS;
            boolean cappedOut = elapsedTicks >= capTicks;
            if (settled || cappedOut) {
                loadingReady = true;
                loadingReadyReason = settled ? "chunks ready" : "cap reached";
                localFraction = 1.0; // reads as "done" while we hold for the story
            }
        }

        if (loadingReady && LoadingStories.isFinished()) {
            finish(mc, loadingReadyReason);
        }
    }

    /** Fraction of chunks present within {@link #PRELOAD_CHUNK_RADIUS} of the camera-start column. */
    private static double computeChunkFraction(Minecraft mc) {
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
    private static void finish(Minecraft mc, String reason) {
        CinematicIntroPacket packet = pending;
        int elapsed = elapsedTicks;
        double fraction = localFraction;
        reset();
        if (mc.screen instanceof CinematicLoadingScreen) {
            mc.setScreen(null);
        }
        LOGGER.info("[DungeonTrain] Cinematic preload gate done ({}): {}t chunk-wait, display={}",
            reason, elapsed, String.format("%.2f", fraction));
        if (packet != null) {
            CinematicCameraController.start(packet);
        }
    }

    /** Placing timed out with no cinematic — just close the screen and let the player in. */
    private static void release(Minecraft mc) {
        reset();
        if (mc.screen instanceof CinematicLoadingScreen) {
            mc.setScreen(null);
        }
    }

    private static void reset() {
        phase = Phase.IDLE;
        pending = null;
        freezePos = null;
        readyStreak = 0;
        localFraction = 0.0;
        loadingReady = false;
        loadingReadyReason = null;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
        LoadingStories.reset();
        LoadingSequenceProgress.reset();
    }
}
