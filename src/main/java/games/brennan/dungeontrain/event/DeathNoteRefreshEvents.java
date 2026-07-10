package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.discord.DeathNoteReporter;
import games.brennan.dungeontrain.narrative.DeathNotePool;
import games.brennan.dungeontrain.narrative.DeathNoteSpawnMessage;
import games.brennan.dungeontrain.train.DeathNoteEchoSpawner;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
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
 * Target-side driver for the Death Note curse — relay-backed and seed-agnostic; <b>dev and release
 * behave identically</b>:
 * <ol>
 *   <li><b>Relay download</b> — pulls a player's unspawned curses (matched by <em>target</em>, across
 *       ANY world) at login and every {@link #REFRESH_EVERY_CARRIAGES} carriages, fail-closed on
 *       {@link DeathNoteGate#canSync}. Login fires on every world load, so each fresh roguelike world
 *       re-pulls the target's pending curses. The relay is a global store, so a curse armed in the
 *       world the author died in survives into the target's later (differently-seeded) lives.</li>
 *   <li><b>Arrival spawn</b> — spawns the author's echo just ahead of the target the moment they reach
 *       the carriage <em>depth</em> the author died at ({@link DeathNotePool#notesReached}). On success
 *       the note is dropped from the local snapshot and latched {@code used} on the relay so it never
 *       respawns.</li>
 * </ol>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DeathNoteRefreshEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Re-download a target's curses every this many carriages of their own travel. */
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
        // A new world load counts as a login → re-pull this target's unspawned curses from the relay.
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
        if (level.dimension() != Level.OVERWORLD) return;            // run the scan once per game tick
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            // A dead player must not spawn an echo — the echo comes in a LATER life, the next time the
            // target reaches the death carriage ALIVE.
            if (!player.isAlive()) continue;
            Integer cur = TrainCarriageAppender.lastCarriageIndex(player.getUUID());
            if (cur == null) continue;                               // not near a train

            // (1) Arrival spawn from the relay-downloaded pool.
            spawnArrivedEchoes(player, cur);

            // (2) Relay re-download every REFRESH_EVERY_CARRIAGES of travel (login covers world entry).
            if (DeathNoteGate.canSync(player)) {
                Integer last = LAST_REFRESH_CARRIAGE.get(player.getUUID());
                if (last == null || Math.abs(cur - last) >= REFRESH_EVERY_CARRIAGES) {
                    LAST_REFRESH_CARRIAGE.put(player.getUUID(), cur);
                    refresh(player);
                }
            }
        }
    }

    /** Spawn (once) every echo whose death carriage {@code player} has reached, just ahead of them. */
    private static void spawnArrivedEchoes(ServerPlayer player, int cur) {
        UUID targetUuid = player.getUUID();
        if (!DeathNotePool.hasAny(targetUuid)) return;
        ServerLevel level = player.serverLevel();
        for (DeathNotePool.Note note : DeathNotePool.notesReached(targetUuid, cur, ARRIVAL_LEAD)) {
            LOGGER.info("[DN-DEBUG] arrival: {} reached carriage {} — spawning echo of {} (note id={}, deathCarriage={}).",
                    player.getGameProfile().getName(), cur, note.authorName(), note.id(), note.deathCarriage());
            boolean ok = DeathNoteEchoSpawner.spawnForTarget(level, player,
                    note.authorUuid(), note.authorName(), note.deathCarriage());
            if (!ok) continue;                                       // not on a carriage yet — retry next scan
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

    /**
     * Pull this target's unspawned curses from the relay (matched by target, ANY world — no seed
     * scoping). Public so the dev {@code /dtechotest dnpull} command can force a pull on demand.
     */
    public static void refresh(ServerPlayer player) {
        DeathNotePool.refreshForPlayer(player.getUUID(), player.getGameProfile().getName());
    }
}
