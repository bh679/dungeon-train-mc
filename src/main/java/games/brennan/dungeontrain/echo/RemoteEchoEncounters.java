package games.brennan.dungeontrain.echo;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.playermob.compat.ReincarnationRecord;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side journal of every player's encounter with a <em>remote</em> echo — a PlayerMob that
 * spawned embodying a player who died in another world. From the {@link
 * games.brennan.dungeontrain.compat.PlayerMobSpawnBridge spawn seam} through the proximity scan,
 * combat, gifts, on/off-train beats and the kill, it accumulates an {@link EchoEncounter} keyed by
 * the echo's entity UUID; when the encounter ends it posts a crafted story (and, in Stage C, a
 * screenshot of the echo) to Discord via {@link DiscordService}.
 *
 * <p>Remote-only by construction: {@link #onRemoteEchoSpawned} is the sole entry that opens a
 * journal, and the spawn bridge calls it only for {@code remote == true} echoes. Server-thread only;
 * all state is plain (no synchronisation needed).</p>
 */
public final class RemoteEchoEncounters {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Furthest a player can be from a spawning echo and still be its "primary" (story audience). */
    private static final double PRIMARY_PLAYER_RADIUS = 48.0;
    /** Centre-to-centre range that counts as "met". */
    private static final double MEET_RADIUS = 6.0;
    /** Range within which line of sight counts as eye contact. */
    private static final double EYE_CONTACT_RADIUS = 16.0;
    /** Range within which a crouch reads as directed at the other. */
    private static final double CROUCH_RADIUS = 5.0;
    /** A deck departure within this many ticks of a player strike is a shove, not a fall. */
    private static final long PUSH_WINDOW_TICKS = 100L;
    /** Safety cap so a pathological run can't grow the map without bound. */
    private static final int MAX_ACTIVE = 64;

    private static final String PHOTO_FILENAME = "echo.png";
    /** Embed bar colour for the encounter story — greyish-blue (Blue Grey), distinct from death-red. */
    private static final int EMBED_COLOR = 0x607D8B;

    /** How many of the echo's items the story names. */
    private static final int HIGHLIGHT_ITEM_COUNT = 2;

    /** Echo spawned but no player was within range at the time; promoted to ACTIVE on first close approach. */
    private record PendingEcho(ResourceKey<Level> dimension, ReincarnationRecord record, List<String> bestItems) {}
    private static final Map<UUID, PendingEcho> PENDING = new HashMap<>();

    private static final Map<UUID, EchoEncounter> ACTIVE = new HashMap<>();

    private RemoteEchoEncounters() {}

    // ---------------- spawn ----------------

    /**
     * Open a journal for a freshly-spawned remote echo. Called from the PlayerMob spawn seam (remote
     * echoes only). No-op when the feature is off, the spawn isn't on a server level, or no player is
     * near enough to be the story's audience.
     */
    public static void onRemoteEchoSpawned(PlayerMobEntity mob, ReincarnationRecord record) {
        if (!DungeonTrainConfig.isEchoEncounterToDiscord()) return;
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (ACTIVE.size() >= MAX_ACTIVE) return;
        // The mob is fully geared by now (PlayerMob applies the snapshot before this seam fires), and
        // it is often gone by post-time — so snapshot its best items here, once, for the story.
        List<String> bestItems = EchoItemHighlights.topItems(mob, HIGHLIGHT_ITEM_COUNT);
        Player nearest = level.getNearestPlayer(mob, PRIMARY_PLAYER_RADIUS);
        if (!(nearest instanceof ServerPlayer player)) {
            // No audience in range yet — park for retroactive promotion in scan().
            PENDING.put(mob.getUUID(), new PendingEcho(level.dimension(), record, bestItems));
            return;
        }

        UUID echoId = mob.getUUID();
        EchoEncounter enc = new EchoEncounter(echoId, level.dimension(), record.playerId(),
                record.name(), player.getUUID(), record.carriage(), level.getGameTime(), bestItems);
        enc.log(EchoEvent.SPAWNED);
        enc.lastOnDeck = CarriageDeck.isOnCarriageDeck(Trains.allCarriages(level), mob);
        ACTIVE.put(echoId, enc);
        LOGGER.info("[DungeonTrain] Remote echo of '{}' spawned (id={}) — encounter journal opened for {}.",
                record.name(), echoId, player.getGameProfile().getName());
    }

    // ---------------- interaction signals ----------------

    /** The primary player struck the echo. Stamps the shove window and logs the beat. */
    public static void onPlayerStruckEcho(ServerPlayer player, UUID echoId, long tick) {
        EchoEncounter enc = forPlayer(echoId, player);
        if (enc == null) return;
        enc.lastStruckByPlayerTick = tick;
        enc.log(EchoEvent.PLAYER_STRUCK_ECHO);
    }

    /** The echo struck the primary player. */
    public static void onEchoStruckPlayer(ServerPlayer player, UUID echoId) {
        EchoEncounter enc = forPlayer(echoId, player);
        if (enc != null) enc.log(EchoEvent.ECHO_STRUCK_PLAYER);
    }

    /** The primary player gave the echo an item. */
    public static void onGaveGift(ServerPlayer player, UUID echoId) {
        EchoEncounter enc = forPlayer(echoId, player);
        if (enc != null) enc.log(EchoEvent.GAVE_GIFT);
    }

    /** The echo gave the primary player an item. */
    public static void onReceivedGift(ServerPlayer player, UUID echoId) {
        EchoEncounter enc = forPlayer(echoId, player);
        if (enc != null) enc.log(EchoEvent.RECEIVED_GIFT);
    }

    /** Stage C: store the captured screenshot of the echo for the eventual post. */
    public static void onPhoto(UUID echoId, byte[] png) {
        EchoEncounter enc = ACTIVE.get(echoId);
        if (enc != null && png != null && png.length > 0) {
            enc.photo = png;
        }
    }

    // ---------------- periodic scan (meeting / LOS / crouch / on-off train / left-behind) ----------------

    /**
     * Advance every journal in {@code level}: meeting, eye contact, crouches, and deck departures
     * (shove vs fall), and end any whose echo has left the world. Driven on a cadence by
     * {@link EchoEncounterEvents}.
     */
    public static void scan(ServerLevel level, long tick) {
        if (ACTIVE.isEmpty() && PENDING.isEmpty()) return;
        List<Trains.Carriage> carriages = null; // computed lazily, once, only if a journal is in this level

        for (EchoEncounter enc : new ArrayList<>(ACTIVE.values())) {
            if (enc.dimension != level.dimension()) continue;
            Entity echo = level.getEntity(enc.echoId);
            if (echo == null || !echo.isAlive()) {
                // Gone from this level (unloaded as the train moved on, or removed) → left behind.
                endEcho(level, enc.echoId, EndReason.LEFT_BEHIND);
                continue;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(enc.primaryPlayerId);
            if (player == null) continue; // audience offline; keep journaling geometry-only beats next tick

            double dist = player.distanceTo(echo);
            if (dist <= MEET_RADIUS) {
                enc.log(EchoEvent.MET);
            }
            if (dist <= EYE_CONTACT_RADIUS && player.hasLineOfSight(echo)) {
                if (enc.log(EchoEvent.EYE_CONTACT)) {
                    // Stage C hooks the screenshot request here (first eye contact).
                    EchoSnapshotRequests.requestIfEnabled(player, echo);
                }
            }
            if (dist <= CROUCH_RADIUS) {
                if (player.isShiftKeyDown()) enc.log(EchoEvent.PLAYER_CROUCHED);
                if (echo.isCrouching()) enc.log(EchoEvent.ECHO_CROUCHED);
            }

            if (carriages == null) carriages = Trains.allCarriages(level);
            boolean onDeck = CarriageDeck.isOnCarriageDeck(carriages, echo);
            if (Boolean.TRUE.equals(enc.lastOnDeck) && !onDeck) {
                boolean shoved = tick - enc.lastStruckByPlayerTick <= PUSH_WINDOW_TICKS;
                enc.log(shoved ? EchoEvent.PUSHED_OFF_TRAIN : EchoEvent.FELL_OFF_TRAIN);
            }
            enc.lastOnDeck = onDeck;
        }

        // Promote pending records whose echo now has a nearby player.
        if (!PENDING.isEmpty()) {
            for (Iterator<Map.Entry<UUID, PendingEcho>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, PendingEcho> entry = it.next();
                if (entry.getValue().dimension() != level.dimension()) continue;
                Entity echo = level.getEntity(entry.getKey());
                if (echo == null || !echo.isAlive()) { it.remove(); continue; }
                if (ACTIVE.size() >= MAX_ACTIVE) break;
                Player nearest = level.getNearestPlayer(echo, MEET_RADIUS);
                if (!(nearest instanceof ServerPlayer player)) continue;
                if (carriages == null) carriages = Trains.allCarriages(level);
                ReincarnationRecord rec = entry.getValue().record();
                EchoEncounter enc = new EchoEncounter(echo.getUUID(), level.dimension(),
                        rec.playerId(), rec.name(), player.getUUID(), rec.carriage(), tick,
                        entry.getValue().bestItems());
                enc.log(EchoEvent.SPAWNED);
                enc.log(EchoEvent.MET);
                enc.lastOnDeck = CarriageDeck.isOnCarriageDeck(carriages, echo);
                ACTIVE.put(echo.getUUID(), enc);
                it.remove();
                LOGGER.info("[DungeonTrain] Remote echo of '{}' (id={}) — journal opened retroactively for {}.",
                        rec.name(), echo.getUUID(), player.getGameProfile().getName());
            }
        }
    }

    // ---------------- terminal: kills & player death ----------------

    /**
     * An entity died. If it is a journaled echo, end that journal; if it is a primary player, end all
     * of their open journals (crediting an echo kill when the echo dealt the blow).
     */
    public static void onEntityDeath(ServerLevel level, Entity victim, Entity killer) {
        UUID victimId = victim.getUUID();
        EchoEncounter echoEnc = ACTIVE.get(victimId);
        if (echoEnc != null) {
            boolean byPrimary = killer instanceof ServerPlayer p && p.getUUID().equals(echoEnc.primaryPlayerId);
            endEcho(level, victimId, byPrimary ? EndReason.ECHO_SLAIN_BY_YOU : EndReason.ECHO_SLAIN);
        }
        // Echo died while still pending (kill happened before the scan could promote it).
        PendingEcho pending = PENDING.remove(victimId);
        if (pending != null && pending.dimension() == level.dimension()) {
            ServerPlayer player = null;
            if (killer instanceof ServerPlayer p) {
                player = p;
            } else {
                Player n = level.getNearestPlayer(victim, PRIMARY_PLAYER_RADIUS);
                if (n instanceof ServerPlayer sp) player = sp;
            }
            if (player != null) {
                EchoEncounter enc = new EchoEncounter(victimId, level.dimension(),
                        pending.record().playerId(), pending.record().name(),
                        player.getUUID(), pending.record().carriage(), level.getGameTime(),
                        pending.bestItems());
                enc.log(EchoEvent.SPAWNED);
                boolean byPrimary = killer instanceof ServerPlayer p && p.getUUID().equals(player.getUUID());
                post(level, enc, byPrimary ? EndReason.ECHO_SLAIN_BY_YOU : EndReason.ECHO_SLAIN);
            }
        }

        // The dead entity may instead be a primary player — close their encounters.
        for (EchoEncounter enc : new ArrayList<>(ACTIVE.values())) {
            if (!victimId.equals(enc.primaryPlayerId)) continue;
            boolean byThisEcho = killer != null && killer.getUUID().equals(enc.echoId);
            endEcho(level, enc.echoId, byThisEcho ? EndReason.YOU_SLAIN_BY_ECHO : EndReason.YOU_DIED);
        }
    }

    /** Drop every journal and pending record (world unload / server stop). */
    public static void clearAll() {
        ACTIVE.clear();
        PENDING.clear();
    }

    /** Number of currently-open journals plus pending records (for the dev test command's feedback). */
    public static int activeCount() {
        return ACTIVE.size() + PENDING.size();
    }

    /**
     * Dev/test helper: end every open journal now ({@link EndReason#LEFT_BEHIND}), posting each
     * story. Returns how many were ended. Used only by the dev-only encounter test command.
     */
    public static int devEndAll(MinecraftServer server) {
        int ended = 0;
        for (EchoEncounter enc : new ArrayList<>(ACTIVE.values())) {
            ServerLevel level = server.getLevel(enc.dimension);
            if (level != null) {
                endEcho(level, enc.echoId, EndReason.LEFT_BEHIND);
                ended++;
            }
        }
        return ended;
    }

    // ---------------- internals ----------------

    /** The journal for {@code echoId} iff {@code player} is its primary; else {@code null}. */
    private static EchoEncounter forPlayer(UUID echoId, ServerPlayer player) {
        EchoEncounter enc = ACTIVE.get(echoId);
        return enc != null && player.getUUID().equals(enc.primaryPlayerId) ? enc : null;
    }

    /** Finalise a journal: remove it, build the story, and post it (best-effort) to Discord. */
    private static void endEcho(ServerLevel level, UUID echoId, EndReason reason) {
        EchoEncounter enc = ACTIVE.remove(echoId);
        if (enc == null) return;
        try {
            post(level, enc, reason);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] echo-encounter Discord post failed: {}", t.toString());
        }
    }

    private static void post(ServerLevel level, EchoEncounter enc, EndReason reason) {
        if (!DungeonTrainConfig.isEchoEncounterToDiscord()) return;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(enc.primaryPlayerId);
        if (player == null) return; // audience offline → nothing to address the story to
        String title = EchoEncounterFormat.title(player.getGameProfile().getName(), enc.sourceName);
        String story = EchoEncounterFormat.story(player.getGameProfile().getName(), enc, reason);
        LOGGER.info("[DungeonTrain] Remote-echo encounter ended ({}) — posting story for {} vs echo of '{}'.",
                reason, player.getGameProfile().getName(), enc.sourceName);
        // Route to the public death-report channel (same cap as the death manifest) on main builds,
        // keeping the encounter story's greyish-blue bar; null on dev → the build's default cap.
        DiscordService.get().postReportTopLevel(player, title, story, List.of(), enc.photo, PHOTO_FILENAME,
                EMBED_COLOR, DungeonTrain.manifestWebhookOverride());
    }
}
