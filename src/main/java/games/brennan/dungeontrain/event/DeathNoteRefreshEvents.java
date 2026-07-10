package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.discord.DeathNoteReporter;
import games.brennan.dungeontrain.narrative.DeathNotePool;
import games.brennan.dungeontrain.narrative.DeathNoteSpawnMessage;
import games.brennan.dungeontrain.train.DeathNoteEchoSpawner;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.PendingDeathNotes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Target-side driver for the Death Note curse:
 * <ol>
 *   <li><b>Arrival spawn</b> — spawns the author's echo just ahead of a target the moment they reach
 *       the carriage the author died at. The death carriage is <em>always</em> generated before the
 *       note exists (the author generated it by dying there), and culled carriages reload without
 *       re-firing contents spawns, so a generation-time trigger can never fire — arrival is the only
 *       robust signal. Dev + release.</li>
 *   <li><b>Relay download</b> — RELEASE only: pulls a player's unspawned curses at login + every
 *       {@link #REFRESH_EVERY_CARRIAGES} carriages, fail-closed on {@link DeathNoteGate#canSync}. In
 *       DEV the pool is armed locally by {@code DeathNoteEvents} (the bare local relay isn't reachable
 *       by the Java client), so the download is skipped — it would only wipe the local arming.</li>
 * </ol>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DeathNoteRefreshEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Re-download a target's curses every this many carriages of their own travel (release). */
    private static final int REFRESH_EVERY_CARRIAGES = 30;
    /** Server-tick throttle for the per-player scan (~1s). */
    private static final int SCAN_PERIOD_TICKS = 20;
    /** Spawn the echo once the target is within this many carriages of the death carriage. */
    private static final int ARRIVAL_LEAD = 1;

    /** playerUuid → carriage index at their last download (to fire every REFRESH_EVERY_CARRIAGES). */
    private static final Map<UUID, Integer> LAST_REFRESH_CARRIAGE = new ConcurrentHashMap<>();

    private DeathNoteRefreshEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        LAST_REFRESH_CARRIAGE.remove(player.getUUID());
        if (!DungeonTrain.isDevBuild() && DeathNoteGate.canSync(player)) refresh(player);
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
        if (level.dimension() != Level.OVERWORLD) return;            // run the scan once per game tick
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;
        boolean dev = DungeonTrain.isDevBuild();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            // A dead player (incl. the death that just armed a self-curse) must not spawn an echo —
            // the echo comes in a LATER life, the next time the target reaches the death carriage ALIVE.
            if (!player.isAlive()) continue;
            Integer cur = TrainCarriageAppender.lastCarriageIndex(player.getUUID());
            if (cur == null) continue;                               // not near a train

            // (1) Arrival spawn — dev (persisted armed store) or release (relay pool).
            spawnArrivedEchoes(player, cur, dev);

            // (2) Relay download — RELEASE only (dev arms locally; a fetch would wipe it).
            if (!dev && DeathNoteGate.canSync(player)) {
                Integer last = LAST_REFRESH_CARRIAGE.get(player.getUUID());
                if (last == null || Math.abs(cur - last) >= REFRESH_EVERY_CARRIAGES) {
                    LAST_REFRESH_CARRIAGE.put(player.getUUID(), cur);
                    refresh(player);
                }
            }
        }
    }

    /** Spawn (once) every echo whose death carriage {@code player} has reached, just ahead of them. */
    private static void spawnArrivedEchoes(ServerPlayer player, int cur, boolean dev) {
        ServerLevel level = player.serverLevel();
        if (dev) {
            // Dev reads the PERSISTED armed store (survives quit-to-title / world reload); no relay.
            for (PendingDeathNotes.ArmedNote a : PendingDeathNotes.get(level)
                    .armedReachedFor(player.getUUID(), player.getGameProfile().getName(), cur, ARRIVAL_LEAD)) {
                boolean ok = DeathNoteEchoSpawner.spawnForTarget(level, player,
                        a.authorUuid().toString(), a.authorName(), a.deathCarriage());
                if (!ok) continue;                                   // not on a carriage yet — retry next scan
                PendingDeathNotes.get(level).removeArmed(a.id());
                announce(level, player, a.authorName(), cur);
            }
            return;
        }
        // Release reads the relay-downloaded pool.
        UUID targetUuid = player.getUUID();
        if (!DeathNotePool.hasAny(targetUuid)) return;
        for (DeathNotePool.Note note : DeathNotePool.notesReached(targetUuid, cur, ARRIVAL_LEAD)) {
            boolean ok = DeathNoteEchoSpawner.spawnForTarget(level, player,
                    note.authorUuid(), note.authorName(), note.deathCarriage());
            if (!ok) continue;
            DeathNotePool.remove(targetUuid, note.id());
            DeathNoteReporter.markUsed(note.id());
            announce(level, player, note.authorName(), cur);
        }
    }

    /** Broadcast the vengeance line naming the author + log the spawn. */
    private static void announce(ServerLevel level, ServerPlayer player, String authorName, int cur) {
        level.getServer().getPlayerList().broadcastSystemMessage(
                DeathNoteSpawnMessage.random(level.getRandom(), authorName), false);
        LOGGER.info("[DungeonTrain] DeathNote: echo of {} spawned near {} at carriage {}.",
                authorName, player.getGameProfile().getName(), cur);
    }

    private static void refresh(ServerPlayer player) {
        String worldKey = String.valueOf(DungeonTrainWorldData.get(player.serverLevel()).getGenerationSeed());
        DeathNotePool.refreshForPlayer(player.getUUID(), player.getGameProfile().getName(), worldKey);
    }
}
