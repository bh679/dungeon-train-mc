package games.brennan.dungeontrain.progress;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.advancement.GlobalAchievementStore;
import games.brennan.dungeontrain.advancement.GlobalBookBurnStats;
import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Translates between the mod's local progress stores and the relay's {@link ProgressBlob} wire shape.
 *
 * <p>Each store maps to the merge strategy that is actually correct for it, rather than to one
 * blanket policy:</p>
 *
 * <ul>
 *   <li>{@link GlobalAchievementStore} → {@code set:advancements}. Earned advancements only ever
 *       accumulate, so a union across machines is exactly right.</li>
 *   <li>{@link GlobalPlayerStats} → {@code max:stats} and {@link GlobalBookBurnStats} →
 *       {@code max:bookburns}. These are lifetime totals; per-key maximum is the merge that can't
 *       double-count. (Summing two machines' totals would inflate every stat on every sync.)</li>
 *   <li>The Ender Chest → {@code lww:enderchest.<slot>}. Item contents genuinely cannot be merged —
 *       two divergent chests are two truths, and unioning them would duplicate items — so this is the
 *       one seq-guarded last-writer-wins blob.</li>
 * </ul>
 *
 * <p><b>Deliberately not synced — two stores that look like they belong here and don't:</b></p>
 *
 * <ul>
 *   <li>{@link games.brennan.dungeontrain.player.PlayerBiomeProgress} is <em>current-run</em> state
 *       that {@code AchievementEvents} resets on death. Restoring it from the relay would resurrect a
 *       dead run's biomes and hand out exploration advancements the player didn't earn this life.</li>
 *   <li>{@link games.brennan.dungeontrain.advancement.GlobalNarrativeProgress} is keyed per
 *       <em>install</em>, not per player (one {@code global.json}, no uuid). There is no principled
 *       answer to whose account it belongs to on a shared world, so syncing it would either invent an
 *       owner or leak one player's story progress onto another's account.</li>
 * </ul>
 */
public final class ProgressBlobs {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Stat field names on the wire. These are the {@link GlobalPlayerStats.Data} codec's own field
     * names, so the blob reads the same as the sidecar it came from.
     */
    private static final String[] STAT_KEYS = {
        "trainTicks", "randomBooksRead", "startingBooksRead", "playersEncountered", "totalDeaths",
        "totalCarriages", "totalDistance", "totalFriends", "totalBooks", "distanceBlocks",
        "totalBooksWritten", "totalContainers", "totalMobKills", "totalPlayerKills", "totalEchos",
        "totalDamageDealt", "totalDamageTaken",
    };

    private ProgressBlobs() {}

    // ---- advancements -----------------------------------------------------------

    /** The player's earned advancements as a union blob. */
    public static ProgressBlob readAdvancements(UUID uuid) {
        List<String> ids = new ArrayList<>();
        for (ResourceLocation id : GlobalAchievementStore.read(uuid)) ids.add(id.toString());
        return ProgressBlob.set(ProgressBlob.ADVANCEMENTS, ids);
    }

    /**
     * Fold a relay advancement blob into the local sidecar. Returns the ids that were NOT already
     * known locally — the caller replays exactly those onto the live world.
     */
    public static Set<ResourceLocation> applyAdvancements(UUID uuid, ProgressBlob blob) {
        if (blob == null || blob.ids() == null) return Set.of();
        Set<ResourceLocation> known = GlobalAchievementStore.read(uuid);
        Set<ResourceLocation> fresh = new LinkedHashSet<>();
        for (String raw : blob.ids()) {
            ResourceLocation id = ResourceLocation.tryParse(raw);
            // A blob written by a build with mods this install doesn't have will carry ids that don't
            // resolve here. Keeping them in the sidecar is right (they belong to the player, and this
            // install shouldn't erase them), but only the resolvable ones can be replayed.
            if (id != null && !known.contains(id)) fresh.add(id);
        }
        if (!fresh.isEmpty()) GlobalAchievementStore.appendAll(uuid, fresh);
        return fresh;
    }

    // ---- lifetime stats ---------------------------------------------------------

    /** The player's lifetime stats as a per-key-maximum blob. */
    public static ProgressBlob readStats(UUID uuid) {
        Map<String, Double> f = new LinkedHashMap<>();
        f.put("trainTicks", (double) GlobalPlayerStats.trainTicks(uuid));
        f.put("randomBooksRead", (double) GlobalPlayerStats.randomBooksRead(uuid));
        f.put("startingBooksRead", (double) GlobalPlayerStats.startingBooksRead(uuid));
        f.put("playersEncountered", (double) GlobalPlayerStats.playersEncountered(uuid));
        f.put("totalDeaths", (double) GlobalPlayerStats.totalDeaths(uuid));
        f.put("totalCarriages", (double) GlobalPlayerStats.totalCarriages(uuid));
        f.put("totalDistance", GlobalPlayerStats.totalDistance(uuid));
        f.put("totalFriends", (double) GlobalPlayerStats.totalFriends(uuid));
        f.put("totalBooks", (double) GlobalPlayerStats.totalBooks(uuid));
        f.put("distanceBlocks", GlobalPlayerStats.distanceBlocks(uuid));
        f.put("totalBooksWritten", (double) GlobalPlayerStats.totalBooksWritten(uuid));
        f.put("totalContainers", (double) GlobalPlayerStats.totalContainers(uuid));
        f.put("totalMobKills", (double) GlobalPlayerStats.totalMobKills(uuid));
        f.put("totalPlayerKills", (double) GlobalPlayerStats.totalPlayerKills(uuid));
        f.put("totalEchos", (double) GlobalPlayerStats.totalEchos(uuid));
        f.put("totalDamageDealt", GlobalPlayerStats.totalDamageDealt(uuid));
        f.put("totalDamageTaken", GlobalPlayerStats.totalDamageTaken(uuid));
        return ProgressBlob.max(ProgressBlob.STATS, f);
    }

    /**
     * Raise each local stat to the relay's value where the relay is ahead.
     *
     * <p>{@link GlobalPlayerStats} exposes only {@code add<Stat>(delta)} — there is no setter, by
     * design, because every gameplay call site is an increment. So "catch up to the remote maximum" is
     * expressed as adding the positive difference, which is the same operation the game itself
     * performs and needs no change to the store.</p>
     */
    public static void applyStats(UUID uuid, ProgressBlob blob) {
        if (blob == null || blob.fields() == null) return;
        for (String key : STAT_KEYS) {
            Double remote = blob.fields().get(key);
            if (remote == null) continue;
            double delta = remote - localStat(uuid, key);
            if (delta <= 0) continue;
            addStat(uuid, key, delta);
        }
    }

    private static double localStat(UUID uuid, String key) {
        return switch (key) {
            case "trainTicks" -> GlobalPlayerStats.trainTicks(uuid);
            case "randomBooksRead" -> GlobalPlayerStats.randomBooksRead(uuid);
            case "startingBooksRead" -> GlobalPlayerStats.startingBooksRead(uuid);
            case "playersEncountered" -> GlobalPlayerStats.playersEncountered(uuid);
            case "totalDeaths" -> GlobalPlayerStats.totalDeaths(uuid);
            case "totalCarriages" -> GlobalPlayerStats.totalCarriages(uuid);
            case "totalDistance" -> GlobalPlayerStats.totalDistance(uuid);
            case "totalFriends" -> GlobalPlayerStats.totalFriends(uuid);
            case "totalBooks" -> GlobalPlayerStats.totalBooks(uuid);
            case "distanceBlocks" -> GlobalPlayerStats.distanceBlocks(uuid);
            case "totalBooksWritten" -> GlobalPlayerStats.totalBooksWritten(uuid);
            case "totalContainers" -> GlobalPlayerStats.totalContainers(uuid);
            case "totalMobKills" -> GlobalPlayerStats.totalMobKills(uuid);
            case "totalPlayerKills" -> GlobalPlayerStats.totalPlayerKills(uuid);
            case "totalEchos" -> GlobalPlayerStats.totalEchos(uuid);
            case "totalDamageDealt" -> GlobalPlayerStats.totalDamageDealt(uuid);
            case "totalDamageTaken" -> GlobalPlayerStats.totalDamageTaken(uuid);
            default -> 0.0;
        };
    }

    private static void addStat(UUID uuid, String key, double delta) {
        switch (key) {
            case "trainTicks" -> GlobalPlayerStats.addTrainTicks(uuid, (long) delta);
            case "randomBooksRead" -> GlobalPlayerStats.addRandomBooksRead(uuid, (long) delta);
            case "startingBooksRead" -> GlobalPlayerStats.addStartingBooksRead(uuid, (long) delta);
            case "playersEncountered" -> GlobalPlayerStats.addPlayersEncountered(uuid, (long) delta);
            case "totalDeaths" -> GlobalPlayerStats.addDeaths(uuid, (long) delta);
            case "totalCarriages" -> GlobalPlayerStats.addCarriages(uuid, (long) delta);
            case "totalDistance" -> GlobalPlayerStats.addDistance(uuid, delta);
            case "totalFriends" -> GlobalPlayerStats.addFriends(uuid, (long) delta);
            case "totalBooks" -> GlobalPlayerStats.addBooks(uuid, (long) delta);
            case "distanceBlocks" -> GlobalPlayerStats.addDistanceBlocks(uuid, delta);
            case "totalBooksWritten" -> GlobalPlayerStats.addBooksWritten(uuid, (long) delta);
            case "totalContainers" -> GlobalPlayerStats.addContainers(uuid, (long) delta);
            case "totalMobKills" -> GlobalPlayerStats.addMobKills(uuid, (long) delta);
            case "totalPlayerKills" -> GlobalPlayerStats.addPlayerKills(uuid, (long) delta);
            case "totalEchos" -> GlobalPlayerStats.addEchos(uuid, (long) delta);
            case "totalDamageDealt" -> GlobalPlayerStats.addDamageDealt(uuid, delta);
            case "totalDamageTaken" -> GlobalPlayerStats.addDamageTaken(uuid, delta);
            default -> { /* unknown key from a newer build — ignore rather than guess */ }
        }
    }

    // ---- book burns -------------------------------------------------------------

    public static ProgressBlob readBookBurns(UUID uuid) {
        return ProgressBlob.max(ProgressBlob.BOOK_BURNS,
            Map.of("booksBurnedUnread", (double) GlobalBookBurnStats.booksBurnedUnread(uuid)));
    }

    public static void applyBookBurns(UUID uuid, ProgressBlob blob) {
        if (blob == null || blob.fields() == null) return;
        Double remote = blob.fields().get("booksBurnedUnread");
        if (remote == null) return;
        long delta = (long) (remote - GlobalBookBurnStats.booksBurnedUnread(uuid));
        if (delta > 0) GlobalBookBurnStats.addBooksBurnedUnread(uuid, delta);
    }

    // ---- Ender Chest ------------------------------------------------------------

    /**
     * The player's live Ender Chest as a base64 gzipped-NBT blob, or {@code null} when it can't be
     * serialised. Reads the LIVE container rather than ECP's file, so it always reflects what the
     * player is actually carrying at this instant.
     */
    public static String encodeEnderChest(ServerPlayer player) {
        try {
            HolderLookup.Provider registries = player.registryAccess();
            ListTag items = player.getEnderChestInventory().createTag(registries);
            CompoundTag root = new CompoundTag();
            root.put("items", items);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, out);
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] progress: could not encode ender chest for {}: {}",
                player.getName().getString(), t.toString());
            return null;
        }
    }

    /**
     * Load a base64 gzipped-NBT chest blob into the player's live container. Returns false (leaving the
     * live chest untouched) when the blob can't be decoded — a corrupt or truncated payload must never
     * wipe the chest the player is holding.
     */
    public static boolean applyEnderChest(ServerPlayer player, String base64) {
        if (base64 == null || base64.isBlank()) return false;
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(base64);
            CompoundTag root;
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                root = NbtIo.readCompressed(in, NbtAccounter.create(8L * 1024 * 1024));
            }
            if (!root.contains("items", Tag.TAG_LIST)) return false;
            ListTag items = root.getList("items", Tag.TAG_COMPOUND);
            player.getEnderChestInventory().fromTag(items, player.registryAccess());
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] progress: could not apply ender chest for {}: {}",
                player.getName().getString(), t.toString());
            return false;
        }
    }

    /** True when the player's live Ender Chest holds nothing. */
    public static boolean enderChestIsEmpty(ServerPlayer player) {
        return player.getEnderChestInventory().isEmpty();
    }
}
