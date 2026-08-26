package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.narrative.LeaderboardPool;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Keeps {@link LeaderboardPool} warm, and fetches each player's own standings once when they join.
 *
 * <p>Both halves are deliberately unhurried. The tick asks for at most one board every
 * {@link #REFRESH_PERIOD_TICKS} and only once a leaderboard book has actually been rolled into the
 * world, so the twenty-four boards cycle in about twelve minutes and a server whose loot never
 * produces one never contacts the relay at all. The relay serves these behind a five-minute edge
 * cache anyway — a board that is a few minutes old is not wrong in any way a reader could notice.</p>
 *
 * <p>The per-player fetch happens at login, once, which is what lets a book's closing "where you
 * stand" line cost nothing at the moment the book is opened. It carries the player's uuid and name,
 * so it is gated on the same network-consent setting as every other relay call that identifies
 * somebody.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LeaderboardRefreshEvents {

    /** One board per this many server ticks (20 ticks = 1 s → ~30 s). */
    static final int REFRESH_PERIOD_TICKS = 600;

    private static int tickCounter = 0;

    private LeaderboardRefreshEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
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

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LeaderboardPool.forget(player.getUUID());
        }
    }
}
