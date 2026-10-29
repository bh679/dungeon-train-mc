package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.net.relay.AdvancementRevokeClient;
import games.brennan.dungeontrain.net.relay.AdvancementRevokeClient.Revoke;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Advancements the developer took back from a player (relay: {@code advrevoke.js}), removed silently
 * from the live world and the cross-world profile.
 *
 * <p><b>Earn time, not once.</b> A revocation carries the time it was made. In any world, an
 * advancement whose criteria were obtained at or before that time is revoked; one earned afterwards is
 * genuine and kept. That is what makes it safe to apply at every login: an old world still holding the
 * bad earn is cleaned the first time it is opened, so its login {@code absorb} can't bank the earn back
 * into the profile, and a later real earn is never touched.</p>
 *
 * <p><b>The profile, once.</b> The cross-world sidecar ({@link GlobalAchievementStore}) keeps ids
 * without times, so each revocation strikes its id from every partition exactly once per install and
 * records that in {@value #APPLIED_FILE}. Anything banked after that is a real earn.</p>
 *
 * <p><b>Order.</b> {@link #applyCached} runs at login before {@code absorb} and {@code replay}, from a
 * cache on disk, so it works offline and before the relay answers. The fresh list then arrives
 * off-thread ({@link #fetchAndApply}); it replaces the cache and is applied to the player if they are
 * still online. The logout reconcile keeps the profile matching the world either way.</p>
 */
public final class AdvancementRevocations {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** uuid (no dashes) → that player's active revocations, as last served by the relay. */
    static final String CACHE_FILE = ".revocations.json";
    /** Revocation ids whose profile half has run on this install, one per line, append-only. */
    static final String APPLIED_FILE = ".revocations-applied";

    private AdvancementRevocations() {}

    // ---- login and fetch --------------------------------------------------------------------------

    /** Apply this player's cached revocations. Login, before absorb/replay. Server thread; no-throw. */
    public static void applyCached(ServerPlayer player) {
        try {
            List<Revoke> revokes = cached(dir(), player.getUUID());
            if (revokes.isEmpty()) return;
            List<String> done = applyTo(player, revokes);
            AdvancementRevokeClient.ack(player.getUUID(), done);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Advancement revocations: cached apply failed: {}", e.toString());
        }
    }

    /**
     * Ask the relay for {@code player}'s current list; when it answers, cache it and apply it if they
     * are still online, then resync the tab copies of anything removed.
     */
    public static void fetchAndApply(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        UUID uuid = player.getUUID();
        AdvancementRevokeClient.fetch(uuid, revokes -> server.execute(() -> onFetched(server, uuid, revokes)));
    }

    private static void onFetched(MinecraftServer server, UUID uuid, List<Revoke> revokes) {
        try {
            replaceFor(dir(), uuid, revokes);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null || revokes.isEmpty()) return;
            List<String> done = applyTo(player, revokes);
            if (!done.isEmpty()) TabGateways.sync(player);
            AdvancementRevokeClient.ack(uuid, done);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Advancement revocations: apply after fetch failed: {}", e.toString());
        }
    }

    /**
     * Apply {@code revokes} to {@code player}'s world and profile. Returns the ids that changed
     * something here (an earn revoked, or the profile struck for the first time) — what gets acked.
     */
    static List<String> applyTo(ServerPlayer player, List<Revoke> revokes) {
        MinecraftServer server = player.getServer();
        if (server == null) return List.of();
        PlayerAdvancements advancements = player.getAdvancements();
        Path dir = dir();
        List<String> done = new ArrayList<>();
        for (Revoke r : revokes) {
            boolean changed = false;
            AdvancementHolder holder = server.getAdvancements().get(r.adv());
            if (holder != null) changed |= revokeEarnedBy(advancements, holder, r.ts());
            if (!isApplied(dir, r.id())) {
                // First time on this install: strike the id from every profile partition. Counts as
                // applied even if no partition held it, so the ack says this install has caught up.
                GlobalAchievementStore.removeEverywhere(player.getUUID(), r.adv());
                GlobalCriteriaProgress.removeEverywhere(player.getUUID(), r.adv());
                markApplied(dir, r.id());
                changed = true;
            }
            if (changed) {
                done.add(r.id());
                LOGGER.info("[DungeonTrain] Advancement revocation {} applied: {} for {}",
                    r.id(), r.adv(), player.getName().getString());
            }
        }
        return done;
    }

    /**
     * Revoke every criterion of {@code holder} obtained at or before {@code ts}. A criterion earned
     * after it is a genuine earn and stays. Returns whether anything was revoked.
     */
    private static boolean revokeEarnedBy(PlayerAdvancements advancements, AdvancementHolder holder, long ts) {
        AdvancementProgress progress = advancements.getOrStartProgress(holder);
        List<String> covered = new ArrayList<>();
        for (String criterion : progress.getCompletedCriteria()) {
            CriterionProgress c = progress.getCriterion(criterion);
            if (c != null && coveredBy(c.getObtained(), ts)) covered.add(criterion);
        }
        boolean any = false;
        for (String criterion : covered) any |= advancements.revoke(holder, criterion);
        return any;
    }

    /** Is an earn at {@code obtained} covered by a revocation made at {@code ts}? Pure. */
    static boolean coveredBy(Instant obtained, long ts) {
        return obtained != null && obtained.toEpochMilli() <= ts;
    }

    // ---- cache ------------------------------------------------------------------------------------

    /** {@code <gameDir>/dungeontrain/achievements/} — beside the profiles the revocations edit. */
    static Path dir() {
        return PlayerDataPaths.dir(PlayerDataPaths.ACHIEVEMENTS);
    }

    /** {@code uuid}'s cached revocations, or none. */
    static List<Revoke> cached(Path dir, UUID uuid) {
        return readCache(dir).getOrDefault(key(uuid), List.of());
    }

    /** Make the relay's answer the whole truth for {@code uuid}: cancelled ones drop out. */
    static void replaceFor(Path dir, UUID uuid, List<Revoke> revokes) {
        Map<String, List<Revoke>> all = new LinkedHashMap<>(readCache(dir));
        List<Revoke> prev = all.getOrDefault(key(uuid), List.of());
        if (prev.equals(revokes)) return;
        if (revokes.isEmpty()) all.remove(key(uuid));
        else all.put(key(uuid), List.copyOf(revokes));
        writeCache(dir, all);
    }

    static Map<String, List<Revoke>> readCache(Path dir) {
        Path file = dir.resolve(CACHE_FILE);
        if (!Files.isRegularFile(file)) return Map.of();
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return Map.of();
            Map<String, List<Revoke>> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonArray()) continue;
                List<Revoke> list = new ArrayList<>();
                for (JsonElement r : e.getValue().getAsJsonArray()) {
                    Revoke parsed = AdvancementRevokeClient.revoke(r);
                    if (parsed != null) list.add(parsed);
                }
                if (!list.isEmpty()) out.put(e.getKey(), List.copyOf(list));
            }
            return out;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Advancement revocations: cache unreadable: {}", e.toString());
            return Map.of();
        }
    }

    private static void writeCache(Path dir, Map<String, List<Revoke>> all) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, List<Revoke>> e : all.entrySet()) {
            JsonArray a = new JsonArray();
            for (Revoke r : e.getValue()) a.add(AdvancementRevokeClient.json(r));
            root.add(e.getKey(), a);
        }
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(CACHE_FILE + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(CACHE_FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Advancement revocations: cache not written: {}", e.toString());
        }
    }

    // ---- applied marker ---------------------------------------------------------------------------

    static boolean isApplied(Path dir, String id) {
        return appliedIds(dir).contains(id);
    }

    static void markApplied(Path dir, String id) {
        if (isApplied(dir, id)) return;
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(APPLIED_FILE), id + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Advancement revocations: couldn't record {} as applied: {}", id, e.toString());
        }
    }

    static Set<String> appliedIds(Path dir) {
        Path file = dir.resolve(APPLIED_FILE);
        if (!Files.isRegularFile(file)) return Set.of();
        try {
            Set<String> out = new LinkedHashSet<>();
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) out.add(line.trim());
            }
            return out;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Advancement revocations: couldn't read {}: {}", file, e.toString());
            return Set.of();
        }
    }

    private static String key(UUID uuid) {
        return uuid.toString().replace("-", "");
    }
}
