package games.brennan.dungeontrain.progress;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.advancement.GlobalBookBurnStats;
import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.enderchestpersistence.EnderChestStore;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side orchestration of relay-backed player progress: pull the player's canonical state on
 * login, push it back on logout and periodically.
 *
 * <h2>What "canonical" means in practice</h2>
 *
 * The relay is the source of truth, and the local sidecars ({@code dungeontrain-achievements/}, ECP's
 * {@code enderchestpersistence/}) are a write-through cache. Nothing here ever blocks the game: every
 * relay call is async, and a player who is offline, has no consent, or has no credential simply plays
 * from the cache exactly as they did before this feature existed.
 *
 * <h2>Why a failed push is not queued</h2>
 *
 * {@link games.brennan.dungeontrain.net.relay.RelayOutbox} exists because telemetry records are
 * <em>events</em> — miss one and it is gone forever. Progress is <em>state</em>: the next push carries
 * the full current picture, re-derived from the local cache. So a failed push needs no spool; it just
 * happens next time. Queueing would be strictly worse, since a stale queued chest could later overwrite
 * a newer one.
 *
 * <h2>The Ender Chest is the careful case</h2>
 *
 * Advancements and stats merge conflict-free, so they are pushed whenever we have a credential. The
 * chest cannot merge, so it is guarded:
 *
 * <ul>
 *   <li>A chest is only ever pushed once this session has completed a successful <b>pull</b>. Without
 *       one we don't know the relay's {@code seq}, and a guessed seq could clobber a newer chest saved
 *       on another machine.</li>
 *   <li>On pull, a stored chest is applied to the live container — the relay wins, as chosen. But when
 *       the relay has <em>no</em> chest for this slot and the local one is non-empty, the local chest is
 *       pushed instead of being wiped: that is a first sync, not a remote deletion.</li>
 *   <li>Free Play / cheated runs are never synced at all. {@link RunIntegrity#isCheated} runs sit on
 *       ECP's {@code creative} slot (see
 *       {@link games.brennan.dungeontrain.compat.EnderChestLockBridge}), and cheated items must never
 *       reach the account that a legitimate run reads from.</li>
 * </ul>
 */
public final class ProgressSyncService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Per-player sync state for this session. Cleared on logout. */
    private record Session(Map<String, Integer> seqByKind, boolean pulled) {
        static final Session UNPULLED = new Session(Map.of(), false);
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private ProgressSyncService() {}

    // ---- lifecycle hooks --------------------------------------------------------

    /**
     * The client has just handed us its credential (login). This is the first moment the server knows
     * which account this player is, so it is where the pull starts.
     */
    public static void onCredentialReceived(ServerPlayer player) {
        if (!canSync(player)) return;
        String secret = ProgressAccounts.secretFor(player);
        if (secret == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        final UUID uuid = player.getUUID();

        ProgressClient.fetchState(secret).thenAccept(state -> {
            if (state == null) {
                LOGGER.info("[DungeonTrain] progress: no relay state for {} this session — playing from "
                    + "the local cache (nothing is lost; the next successful sync catches up).",
                    player.getName().getString());
                return;
            }
            // Back onto the server thread: everything below touches player/world state.
            server.execute(() -> applyPulled(player, state));
        }).exceptionally(t -> {
            LOGGER.debug("[DungeonTrain] progress: pull failed for {} — {}", uuid, t.toString());
            return null;
        });
    }

    /** Push everything we can on logout. */
    public static void onLogout(ServerPlayer player) {
        push(player, "logout");
        ProgressAccounts.forget(player.getUUID());
        SESSIONS.remove(player.getUUID());
    }

    /** Push everything we can for every online player (autosave / server stopping). */
    public static void pushAll(MinecraftServer server, String reason) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) push(player, reason);
    }

    // ---- pull -------------------------------------------------------------------

    /** Apply a pulled state to this player. Server thread. */
    private static void applyPulled(ServerPlayer player, ProgressClient.State state) {
        if (!canSync(player)) return;
        UUID uuid = player.getUUID();

        // Advancements: union into the sidecar, then replay the newly-learned ones onto this world so
        // they show up immediately rather than only after the next login.
        Set<ResourceLocation> fresh = ProgressBlobs.applyAdvancements(uuid, state.get(ProgressBlob.ADVANCEMENTS));
        if (!fresh.isEmpty()) {
            grant(player, fresh);
            LOGGER.info("[DungeonTrain] progress: replayed {} advancement(s) from the relay for {}.",
                fresh.size(), player.getName().getString());
        }

        ProgressBlobs.applyStats(uuid, state.get(ProgressBlob.STATS));
        ProgressBlobs.applyBookBurns(uuid, state.get(ProgressBlob.BOOK_BURNS));
        GlobalPlayerStats.flush(uuid);
        GlobalBookBurnStats.flush(uuid);

        Map<String, Integer> seqs = new HashMap<>();
        String chestKind = chestKindFor(player);
        if (chestKind != null) {
            ProgressBlob chest = state.get(chestKind);
            if (chest != null && chest.data() != null) {
                if (ProgressBlobs.applyEnderChest(player, chest.data())) {
                    // Keep ECP's on-disk cache coherent with what the player is now holding.
                    EnderChestStore.save(player);
                    EnderChestStore.flush(player.getUUID());
                    LOGGER.info("[DungeonTrain] progress: restored the ender chest for {} from the relay.",
                        player.getName().getString());
                }
                seqs.put(chestKind, chest.seq());
            } else {
                // Nothing stored yet — this is a first sync, not a remote deletion. Seed from local.
                seqs.put(chestKind, 0);
            }
        }
        SESSIONS.put(uuid, new Session(Map.copyOf(seqs), true));

        // Seed the relay with whatever this install already had.
        push(player, "first sync");
    }

    /** Grant advancements the relay knows about but this world doesn't. */
    private static void grant(ServerPlayer player, Set<ResourceLocation> ids) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        for (ResourceLocation id : ids) {
            AdvancementHolder holder = server.getAdvancements().get(id);
            if (holder == null) continue; // an advancement from a mod this install doesn't have
            var progress = player.getAdvancements().getOrStartProgress(holder);
            for (String criterion : progress.getRemainingCriteria()) {
                player.getAdvancements().award(holder, criterion);
            }
        }
    }

    // ---- push -------------------------------------------------------------------

    /**
     * Push this player's progress. Conflict-free blobs go every time; the chest only once this session
     * has pulled (see the class doc).
     */
    public static void push(ServerPlayer player, String reason) {
        if (!canSync(player)) return;
        String secret = ProgressAccounts.secretFor(player);
        if (secret == null) return;
        UUID uuid = player.getUUID();

        List<ProgressBlob> blobs = new ArrayList<>();
        blobs.add(ProgressBlobs.readAdvancements(uuid));
        blobs.add(ProgressBlobs.readStats(uuid));
        blobs.add(ProgressBlobs.readBookBurns(uuid));

        Session session = SESSIONS.getOrDefault(uuid, Session.UNPULLED);
        String chestKind = chestKindFor(player);
        if (session.pulled() && chestKind != null) {
            String data = ProgressBlobs.encodeEnderChest(player);
            if (data != null) {
                int nextSeq = session.seqByKind().getOrDefault(chestKind, 0) + 1;
                blobs.add(ProgressBlob.lww(chestKind, nextSeq, data));
                Map<String, Integer> seqs = new HashMap<>(session.seqByKind());
                seqs.put(chestKind, nextSeq);
                SESSIONS.put(uuid, new Session(Map.copyOf(seqs), true));
            }
        }

        ProgressClient.pushState(secret, blobs).thenAccept(state -> {
            if (state == null) {
                LOGGER.debug("[DungeonTrain] progress: push ({}) did not reach the relay for {} — the "
                    + "next push carries the same state.", reason, uuid);
                return;
            }
            if (!state.conflicts().isEmpty()) {
                // Another machine wrote a newer chest. The winner came back in this same response, so
                // adopt it rather than leaving the two divergent.
                MinecraftServer server = player.getServer();
                if (server != null) server.execute(() -> adoptConflictWinners(player, state));
            }
        }).exceptionally(t -> {
            LOGGER.debug("[DungeonTrain] progress: push failed for {} — {}", uuid, t.toString());
            return null;
        });
    }

    /** A push lost to a newer chest: take the relay's version, which is in the same response. */
    private static void adoptConflictWinners(ServerPlayer player, ProgressClient.State state) {
        if (!canSync(player)) return;
        String chestKind = chestKindFor(player);
        if (chestKind == null || !state.conflicts().contains(chestKind)) return;
        ProgressBlob winner = state.get(chestKind);
        if (winner == null || winner.data() == null) return;
        if (ProgressBlobs.applyEnderChest(player, winner.data())) {
            EnderChestStore.save(player);
            EnderChestStore.flush(player.getUUID());
            Map<String, Integer> seqs = new HashMap<>();
            seqs.put(chestKind, winner.seq());
            SESSIONS.put(player.getUUID(), new Session(Map.copyOf(seqs), true));
            LOGGER.info("[DungeonTrain] progress: a newer ender chest was saved elsewhere; adopted it "
                + "for {}.", player.getName().getString());
        }
    }

    // ---- gates ------------------------------------------------------------------

    /**
     * Whether this player may sync at all. Fail-closed at every step: the feature must be enabled, the
     * player's client must have granted network consent, and the run must not be Free Play.
     */
    private static boolean canSync(ServerPlayer player) {
        if (player == null) return false;
        if (!DungeonTrainConfig.progressSyncEnabled()) return false;
        if (!NetworkConsentMirror.isGranted(player)) return false;
        // A cheated run's items must never reach the account a legitimate run reads from.
        return !RunIntegrity.isCheated(player);
    }

    /**
     * The blob kind for this player's Ender Chest slot, or {@code null} when the slot must not sync.
     *
     * <p>Mirrors ECP's own slot resolution (game mode name), with DT's override folded in: a cheated
     * run is locked onto the {@code creative} slot by
     * {@link games.brennan.dungeontrain.compat.EnderChestLockBridge}, and that slot is deliberately
     * never synced — it holds Free Play items by construction.</p>
     */
    private static String chestKindFor(ServerPlayer player) {
        GameType mode = player.gameMode.getGameModeForPlayer();
        if (mode == GameType.CREATIVE || RunIntegrity.isCheated(player)) return null;
        return ProgressBlob.enderChest(mode.getSerializedName());
    }

    /** Server stop — drop every session so a restart re-pulls cleanly. */
    public static void clear() {
        SESSIONS.clear();
        ProgressAccounts.clear();
    }
}
