package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.narrative.LeaderboardPool;
import games.brennan.dungeontrain.narrative.LeaderboardRankSchedule;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.UUID;

/**
 * Keeps {@link LeaderboardPool} warm, and keeps each player's own standings current.
 *
 * <p>The board half is deliberately unhurried. The tick asks for at most one board every
 * {@link #REFRESH_PERIOD_TICKS} and only once a leaderboard book has actually been rolled into the
 * world, so the twenty-four boards cycle in about twelve minutes and a server whose loot never
 * produces one never contacts the relay at all. The relay serves these behind a five-minute edge
 * cache anyway — a board that is a few minutes old is not wrong in any way a reader could notice.</p>
 *
 * <p>The per-player half fetches at login and again after every death, which is the only moment a
 * player's own position actually moves. Both carry the player's uuid and name, so both wait on the
 * same network-consent setting as every other relay call that identifies somebody.</p>
 *
 * <h2>Why a death schedules rather than fetches</h2>
 * <p>The death's own telemetry — the run summary and death detail the relay scores from — only leaves
 * in the trailing flush of {@code RunStatsEvents.onPlayerDeath}'s {@code RelayOutbox.runBatched(...)}
 * block. Asking for ranks inside the death handler would therefore read back the position the player
 * held BEFORE the death that just happened, which is the exact staleness this is here to fix. So the
 * death puts the player on {@link #PENDING} and the first tick {@link #DEATH_RANK_DELAY_MS} later does
 * the fetching, by which time the relay has ingested the death and {@code /leaderboard/me} — which
 * reads {@code player_scores} live and is never edge-cached — answers with the new number.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LeaderboardRefreshEvents {

    /** One board per this many server ticks (20 ticks = 1 s → ~30 s). */
    static final int REFRESH_PERIOD_TICKS = 600;

    /**
     * How long after a death the rank refetch runs. Long enough for the death's telemetry batch to
     * reach the relay and be scored; short enough that a player who dies, respawns and picks up a
     * leaderboard book reads a current position.
     *
     * <p>Wall-clock, not ticks. This waits on a network round trip, and the seconds right after a
     * death are the ones the server is least likely to tick on time: measured as 100 ticks, this wait
     * ran 17–20 s in testing, alongside "Can't keep up … 42 ticks behind" in the same second of log.
     * The tick handler is only what drives the check; the deadline itself is real time.</p>
     */
    static final long DEATH_RANK_DELAY_MS = 5_000L;

    /** Deaths waiting on their delay. See the class note on why a death cannot fetch on the spot. */
    private static final LeaderboardRankSchedule PENDING = new LeaderboardRankSchedule();

    private static int tickCounter = 0;

    private LeaderboardRefreshEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!PENDING.isEmpty()) drainDueRanks(event.getServer(), System.currentTimeMillis());
        if (++tickCounter < REFRESH_PERIOD_TICKS) return;
        tickCounter = 0;
        LeaderboardPool.warmNext();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Asking "where does THIS player rank" means naming them to the relay, so it waits on the
        // same consent as the telemetry that put them on the board in the first place. Without it the
        // books still read fine — they just close on "you are not on this board" instead.
        if (!DungeonTrainConfig.isWorldInfoToRelay()) return;
        LeaderboardPool.refreshRanks(player.getUUID(), player.getName().getString());
    }

    /**
     * A death moves the player's own numbers, so their standings are re-asked for shortly after.
     *
     * <p>LOWEST priority so {@code RunStatsEvents.onPlayerDeath} (LOW) — the handler that accrues the
     * lifetime counters and enqueues the telemetry — has already run. A Free Play run is skipped
     * outright: its death writes no score, so there would be nothing new to read.</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!DungeonTrainConfig.isWorldInfoToRelay()) return;
        if (RunIntegrity.isCheated(player)) return;
        PENDING.schedule(player.getUUID(), System.currentTimeMillis() + DEATH_RANK_DELAY_MS);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING.cancel(player.getUUID());
            LeaderboardPool.forget(player.getUUID());
        }
    }

    /**
     * Fetch ranks for everyone whose post-death delay has elapsed. The live {@link ServerPlayer} is
     * resolved here rather than captured at death time so the request carries the name the relay
     * knows them by — the boards keyed by credit name (translations, donations) need it — and so a
     * player who left in the interval is simply dropped instead of fetched for.
     */
    private static void drainDueRanks(MinecraftServer server, long nowMs) {
        for (UUID id : PENDING.drainDue(nowMs)) {
            ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(id);
            if (player == null) continue;
            // Inside the per-player cooldown, this death waits for it rather than being dropped —
            // otherwise a death shortly after joining (or a second death in quick succession) would
            // never reach the relay at all, and that player's book would keep a standing the game
            // already knows is out of date. Re-scheduling costs one map entry.
            long wait = LeaderboardPool.rankRefreshWaitMs(id, nowMs);
            if (wait > 0L) {
                PENDING.schedule(id, nowMs + wait);
                continue;
            }
            LeaderboardPool.refreshRanks(id, player.getName().getString());
        }
    }
}
