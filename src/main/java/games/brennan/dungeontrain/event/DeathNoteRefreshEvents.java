package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.discord.DeathNoteReporter;
import games.brennan.dungeontrain.narrative.CursedStoryPool;
import games.brennan.dungeontrain.narrative.DeathNotePool;
import games.brennan.dungeontrain.narrative.DeathNoteSpawnMessage;
import games.brennan.dungeontrain.narrative.LoveNoteSpawnMessage;
import games.brennan.dungeontrain.narrative.NoteKind;
import games.brennan.dungeontrain.train.DeathNoteEchoSpawner;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import net.minecraft.network.chat.Component;
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

    /**
     * playerUuid → the carriage index seen at the previous scan, used to detect the moment a player
     * crosses from one cart into the next — when a cursed story book is delivered mid-life (see
     * {@link #deliverCursedStoryBetweenCarts}). Cleared on login/logout so a fresh life starts quiet.
     */
    private static final Map<UUID, Integer> LAST_SCAN_CARRIAGE = new ConcurrentHashMap<>();

    /**
     * Players already handed a mid-life cursed strike this life. The welcome strike on the next
     * login / respawn re-offers an unread story anyway, so one mid-life delivery is enough — without
     * this the strike would re-fire at every cart boundary until the book is opened.
     */
    private static final Map<UUID, Boolean> CURSED_STRUCK_THIS_LIFE = new ConcurrentHashMap<>();

    private DeathNoteRefreshEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        LAST_REFRESH_CARRIAGE.remove(player.getUUID());
        LAST_SCAN_CARRIAGE.remove(player.getUUID());
        CURSED_STRUCK_THIS_LIFE.remove(player.getUUID());
        // A new world load counts as a login → re-pull this target's unspawned curses from the relay,
        // and this author's landed curses awaiting their story.
        if (DeathNoteGate.canSync(player)) refresh(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        DeathNotePool.forget(player.getUUID());
        CursedStoryPool.forget(player.getUUID());
        LAST_REFRESH_CARRIAGE.remove(player.getUUID());
        LAST_SCAN_CARRIAGE.remove(player.getUUID());
        CURSED_STRUCK_THIS_LIFE.remove(player.getUUID());
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

            // (2) A cursed story book, handed over as the player crosses into the next cart.
            deliverCursedStoryBetweenCarts(player, cur);

            // (3) Relay re-download every REFRESH_EVERY_CARRIAGES of travel (login covers world entry).
            if (DeathNoteGate.canSync(player)) {
                Integer last = LAST_REFRESH_CARRIAGE.get(player.getUUID());
                if (last == null || Math.abs(cur - last) >= REFRESH_EVERY_CARRIAGES) {
                    LAST_REFRESH_CARRIAGE.put(player.getUUID(), cur);
                    refresh(player);
                }
            }
        }
    }

    /**
     * Hand over a pending cursed story mid-life, at the moment the player steps from one cart into
     * the next — a beat between rooms rather than an interruption mid-fight, and the reason a curse
     * that lands while the author is already playing doesn't have to wait for their next life.
     *
     * <p>Fires at most once per life: the welcome strike on the next login / respawn re-offers an
     * unread story anyway. Held back during the spawn cinematic for the same reason the welcome
     * strike is.</p>
     */
    private static void deliverCursedStoryBetweenCarts(ServerPlayer player, int cur) {
        UUID uuid = player.getUUID();
        Integer previous = LAST_SCAN_CARRIAGE.put(uuid, cur);
        if (previous == null || previous == cur) return;          // not a cart boundary (yet)
        if (CURSED_STRUCK_THIS_LIFE.containsKey(uuid)) return;    // already had this life's delivery
        if (!CursedStoryPool.hasPending(uuid)) return;
        if (CinematicIntroService.isCinematicActive(uuid)) return;
        CURSED_STRUCK_THIS_LIFE.put(uuid, Boolean.TRUE);
        StartingBookEvents.fireCursedStrike(player);
    }

    /** Spawn (once) every echo whose death carriage {@code player} has reached, just ahead of them. */
    private static void spawnArrivedEchoes(ServerPlayer player, int cur) {
        UUID targetUuid = player.getUUID();
        if (!DeathNotePool.hasAny(targetUuid)) return;
        ServerLevel level = player.serverLevel();
        // Provenance match: a curse spawns only for a target whose Free Play state matches the
        // author's at signing — Free Play curses haunt only Free Play runs, legit only legit. Dev
        // builds bypass the match (spawn everything) for testing. A mismatched note is left in the
        // pool (not removed / marked used) so it can still fire on a later matching life.
        boolean targetFreePlay = RunIntegrity.isCheated(player);
        boolean enforce = !DungeonTrain.isDevBuild();
        for (DeathNotePool.Note note : DeathNotePool.notesReached(targetUuid, cur, ARRIVAL_LEAD)) {
            if (enforce && note.freePlay() != targetFreePlay) continue; // provenance mismatch — wait for a matching life
            boolean ok = DeathNoteEchoSpawner.spawnForTarget(level, player,
                    note.authorUuid(), note.authorName(), note.deathCarriage(), note.id(), note.kind(),
                    note.lines());
            if (!ok) continue;                                       // not on a carriage yet — retry next scan
            DeathNotePool.remove(targetUuid, note.id());
            DeathNoteReporter.markUsed(note.id());
            announce(level, player, note.authorName(), cur, note.kind());
        }
    }

    /** Broadcast this kind's arrival line naming the author + log the spawn. */
    private static void announce(ServerLevel level, ServerPlayer player, String authorName, int cur,
                                 NoteKind kind) {
        Component line = kind == NoteKind.LOVE
                ? LoveNoteSpawnMessage.random(level.getRandom(), authorName)
                : DeathNoteSpawnMessage.random(level.getRandom(), authorName);
        level.getServer().getPlayerList().broadcastSystemMessage(line, false);
        LOGGER.info("[DungeonTrain] {}: echo of {} spawned near {} at carriage {}.",
                kind.trophyTitle(), authorName, player.getGameProfile().getName(), cur);
    }

    /**
     * Pull this target's unspawned curses from the relay (matched by target, ANY world — no seed
     * scoping). Public so the dev {@code /dtechotest dnpull} command can force a pull on demand.
     */
    public static void refresh(ServerPlayer player) {
        DeathNotePool.refreshForPlayer(player.getUUID(), player.getGameProfile().getName());
        // Same trip, opposite direction: the curses this player WROTE that have since landed and are
        // owed a story (see CursedStoryPool + the cursed welcome book).
        CursedStoryPool.refreshForPlayer(player.getUUID());
    }
}
