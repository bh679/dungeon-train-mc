package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.DeathNotePool;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives the target-side download of Death Note curses (see {@link DeathNotePool}): a player pulls
 * their unspawned notes at login and again every {@link #REFRESH_EVERY_CARRIAGES} carriages of their
 * own travel, so newly-authored curses surface as they journey. All fetches are fail-closed on
 * {@link DeathNoteGate#canSync} (feature enabled + network consent).
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DeathNoteRefreshEvents {

    /** Re-download a target's curses every this many carriages of their own travel. */
    private static final int REFRESH_EVERY_CARRIAGES = 30;
    /** Server-tick throttle for the per-player carriage scan (~1s). */
    private static final int SCAN_PERIOD_TICKS = 20;

    /** playerUuid → carriage index at their last download (to fire every REFRESH_EVERY_CARRIAGES). */
    private static final Map<UUID, Integer> LAST_REFRESH_CARRIAGE = new ConcurrentHashMap<>();

    private DeathNoteRefreshEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        LAST_REFRESH_CARRIAGE.remove(player.getUUID());
        if (DeathNoteGate.canSync(player)) refresh(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        DeathNotePool.forget(player.getUUID());
        LAST_REFRESH_CARRIAGE.remove(player.getUUID());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;            // run once per game tick
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (!DeathNoteGate.canSync(player)) continue;
            Integer cur = TrainCarriageAppender.lastCarriageIndex(player.getUUID());
            if (cur == null) continue;                               // not near a train
            Integer last = LAST_REFRESH_CARRIAGE.get(player.getUUID());
            if (last != null && Math.abs(cur - last) < REFRESH_EVERY_CARRIAGES) continue;
            LAST_REFRESH_CARRIAGE.put(player.getUUID(), cur);
            refresh(player);
        }
    }

    private static void refresh(ServerPlayer player) {
        String worldKey = String.valueOf(DungeonTrainWorldData.get(player.serverLevel()).getGenerationSeed());
        DeathNotePool.refreshForPlayer(player.getUUID(), player.getGameProfile().getName(), worldKey);
    }
}
