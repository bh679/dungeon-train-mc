package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.LapThemeProgress;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.LapTheme;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Records how far each player gets through each theme lap ({@link LapThemeProgress}), which later
 * worlds read when they choose a lap's theme. Once a second, per player on the overworld: every theme
 * lap of the player's current run that starts behind them is raised to the fraction they have
 * covered — a lap wholly behind them counts as 100%, so crossing the end of a lap's End band completes
 * that theme. The last lap of the previous run is included, so the step out of it still lands.
 *
 * <p>Skipped for spectators and Free Play (cheated) runs, the same gate the lifetime stats use, and
 * in worlds without a train (no band cycle). Only already-decided laps count: a lap under a player has
 * been generated, so its theme is always decided there.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LapThemeProgressEvents {

    private static final int SCAN_PERIOD_TICKS = 20;

    private LapThemeProgressEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!Level.OVERWORLD.equals(level.dimension())) return;
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0 || level.players().isEmpty()) return;
        if (!DungeonTrainWorldData.get(level).startsWithTrain()) return;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        int groups = cycle.themeGroupsPerRun();
        if (groups == 0) return;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || RunIntegrity.isCheated(player)) continue;
            record(cycle, groups, player);
        }
    }

    private static void record(WorldGenCycle cycle, int groups, ServerPlayer player) {
        int px = player.getBlockX();
        long run = cycle.cycleIndex(px);
        if (run < 0L) return;
        long first = Math.max(0L, run * groups - 1L);
        long last = run * groups + groups - 1L;
        for (long n = first; n <= last; n++) {
            long[] range = cycle.themeLapRange(n);
            if (range == null || px < range[0]) continue;
            LapTheme theme = cycle.peekThemeOfLap(n);
            if (theme == null) continue;
            double span = Math.max(1L, range[1] - range[0]);
            LapThemeProgress.raise(player.getUUID(), theme, Math.min(1.0, (px - range[0]) / span));
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        LapThemeProgress.flush(player.getUUID());
        LapThemeProgress.evict(player.getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LapThemeProgress.flushAll();
    }
}
