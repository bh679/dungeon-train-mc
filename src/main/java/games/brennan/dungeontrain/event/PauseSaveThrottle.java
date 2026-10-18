package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Rate-limits vanilla's singleplayer save-on-pause.
 *
 * <p>{@code IntegratedServer.tickServer} runs a full {@code saveEverything} on every
 * not-paused → paused edge. A book screen is a pause screen, so a player browsing a Stat Room's
 * shelves saved the whole world on every book: 23 saves in 3 minutes, 50–400 ms each, plus
 * Sable's {@code saveAll} (player log 17 Oct 2026). {@code IntegratedServerPauseSaveThrottleMixin}
 * asks {@link #allowPauseSave} before that call and skips it when <em>any</em> save ran in the last
 * {@link #MIN_INTERVAL_MS}. The game still pauses; only the save is skipped.</p>
 *
 * <p>Crash cost: a hard kill while paused loses at most {@link #MIN_INTERVAL_MS} of play, tighter
 * than the 5-minute autosave window an unpaused player already has. Quitting, dying and closing the
 * window all go through {@code stopServer}, which always saves.</p>
 *
 * <p>The clock is fed by {@code MinecraftServerSaveMixin} (HEAD of {@code saveEverything}), so the
 * periodic autosave, {@code /save-all} and every pause-save that does run all restart it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PauseSaveThrottle {

    /** Minimum wall-clock gap between a save and the next pause-save. */
    static final long MIN_INTERVAL_MS = 60_000L;

    /** nanoTime of the last world save; {@code 0} before the first save / after a stop. */
    private static volatile long lastSaveNanos = 0L;

    private PauseSaveThrottle() {}

    /** Pure core: may a pause-save run at {@code nowNanos}? {@code 0} = no save yet, always allowed. */
    static boolean shouldSave(long nowNanos, long prevSaveNanos, long minIntervalMs) {
        if (prevSaveNanos == 0L) return true;
        return (nowNanos - prevSaveNanos) / 1_000_000L >= minIntervalMs;
    }

    /** Called before the pause-save; true = let it run. */
    public static boolean allowPauseSave(long nowNanos) {
        return shouldSave(nowNanos, lastSaveNanos, MIN_INTERVAL_MS);
    }

    /** Whole seconds since the last save, for the skip log line. */
    public static long secondsSinceLastSave(long nowNanos) {
        long prev = lastSaveNanos;
        return prev == 0L ? -1L : (nowNanos - prev) / 1_000_000_000L;
    }

    /** Records that a world save is starting now. */
    public static void noteSave(long nowNanos) {
        lastSaveNanos = nowNanos;
    }

    static void reset() {
        lastSaveNanos = 0L;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        reset(); // the first pause in the next world always saves
    }
}
