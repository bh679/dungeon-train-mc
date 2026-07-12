package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.net.relay.RelayOutbox;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Drains the {@link RelayOutbox} telemetry spool: once when the server boots (delivering anything
 * queued from a previous, offline session) and then periodically while it runs.
 *
 * <p>Not {@code Dist.CLIENT} — telemetry fires on dedicated servers too, so unlike
 * {@link WorldLifecycleEvents} (client-only) this must load on both sides. Modelled on
 * {@link games.brennan.dungeontrain.echo.EchoEncounterEvents}: a {@link LevelTickEvent.Post} gated to
 * the overworld and throttled by game-time so the flush attempt runs about every 30&nbsp;s regardless
 * of how many levels (or Sable sub-levels) are ticking. {@link RelayOutbox#flush()} is a cheap no-op
 * when the queue is empty.</p>
 */
public final class RelayOutboxFlushEvents {

    /** ~30 s at 20 tps — telemetry is not latency-sensitive, so a coarse cadence keeps overhead nil. */
    private static final int FLUSH_PERIOD_TICKS = 600;

    private RelayOutboxFlushEvents() {}

        public static void onServerStarted(net.minecraft.server.MinecraftServer server) {
        RelayOutbox.get().flush();
    }

        public static void onLevelTick(net.minecraft.world.level.Level tickedLevel) {
        if (!(tickedLevel instanceof ServerLevel level)) {
            return;
        }
        // Gate to a single, always-ticking level so the cadence is one flush per period, not one
        // per loaded dimension/sub-level.
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (level.getGameTime() % FLUSH_PERIOD_TICKS != 0) {
            return;
        }
        RelayOutbox.get().flush();
    }
}
