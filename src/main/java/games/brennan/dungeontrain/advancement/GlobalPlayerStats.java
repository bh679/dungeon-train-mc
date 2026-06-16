package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cumulative per-player gameplay stats that persist <em>outside</em> any
 * individual world save, parallel to {@link GlobalAchievementStore}.
 * Lives at {@code <minecraft>/config/dungeontrain-stats/<uuid>.json}.
 *
 * <p>Tracked stats:
 * <ul>
 *   <li>{@code trainTicks} — total accumulated server ticks a player has
 *       been on a carriage. Drives the "Enjoying the Ride", "Settling In",
 *       "I Live Here Now", and "Help, I Can't Get Off" milestones, and the
 *       death screen's "total aboard" all-lives stat.</li>
 *   <li>{@code randomBooksRead} — total right-clicks on random-book items.
 *       Re-reads count. Drives the "Taking Notes" milestone.</li>
 *   <li>{@code startingBooksRead} — total right-clicks on starting-book
 *       items. Re-reads count. Drives the "The Same But Different"
 *       milestone.</li>
 *   <li>{@code playersEncountered} — total distinct PlayerMobs the player
 *       has come near across all runs. Drives the "Strangers on a Train"
 *       milestone.</li>
 *   <li>{@code totalDeaths}, {@code totalCarriages}, {@code totalDistance},
 *       {@code totalFriends}, {@code totalBooks} — lifetime run totals
 *       accumulated at death from the just-ended run's {@code PlayerRunState}
 *       (see {@code RunStatsEvents}). Feed the death screen's "all your lives"
 *       page. Each is the running sum of every run's per-life counter.</li>
 *   <li>{@code distanceBlocks} — cumulative boarded distance (metres) accrued
 *       continuously as the player rides. Drives the lifetime distance
 *       milestones ("Going the Distance", "Million Metre Club", …).</li>
 * </ul>
 * All counters accrue across worlds and sessions; switching worlds does
 * not reset them.</p>
 *
 * <p>Disk-I/O strategy: in-memory cache backs all reads/writes during a
 * server session. Cache is loaded lazily on first access per UUID, written
 * back to disk on player logout, server stop, and on every milestone
 * threshold cross — so a crash mid-session loses at most ~30 minutes of
 * accumulated state, not the lifetime total.</p>
 */
public final class GlobalPlayerStats {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIR_NAME = "dungeontrain-stats";

    public record Data(
            long trainTicks, long randomBooksRead, long startingBooksRead, long playersEncountered,
            long totalDeaths, long totalCarriages, double totalDistance, long totalFriends, long totalBooks,
            double distanceBlocks) {

        public static final Codec<Data> CODEC = RecordCodecBuilder.create(in -> in.group(
            Codec.LONG.optionalFieldOf("trainTicks", 0L).forGetter(Data::trainTicks),
            Codec.LONG.optionalFieldOf("randomBooksRead", 0L).forGetter(Data::randomBooksRead),
            Codec.LONG.optionalFieldOf("startingBooksRead", 0L).forGetter(Data::startingBooksRead),
            Codec.LONG.optionalFieldOf("playersEncountered", 0L).forGetter(Data::playersEncountered),
            Codec.LONG.optionalFieldOf("totalDeaths", 0L).forGetter(Data::totalDeaths),
            Codec.LONG.optionalFieldOf("totalCarriages", 0L).forGetter(Data::totalCarriages),
            Codec.DOUBLE.optionalFieldOf("totalDistance", 0.0).forGetter(Data::totalDistance),
            Codec.LONG.optionalFieldOf("totalFriends", 0L).forGetter(Data::totalFriends),
            Codec.LONG.optionalFieldOf("totalBooks", 0L).forGetter(Data::totalBooks),
            Codec.DOUBLE.optionalFieldOf("distanceBlocks", 0.0).forGetter(Data::distanceBlocks)
        ).apply(in, Data::new));

        public static final Data EMPTY = new Data(0L, 0L, 0L, 0L, 0L, 0L, 0.0, 0L, 0L, 0.0);

        // Field-wise copy-with-increment helpers so the static adders below
        // stay one-liners and the 10-arg constructor is written in exactly one
        // place per field.
        Data plusTrainTicks(long d)         { return new Data(trainTicks + d, randomBooksRead, startingBooksRead, playersEncountered, totalDeaths, totalCarriages, totalDistance, totalFriends, totalBooks, distanceBlocks); }
        Data plusRandomBooks(long d)        { return new Data(trainTicks, randomBooksRead + d, startingBooksRead, playersEncountered, totalDeaths, totalCarriages, totalDistance, totalFriends, totalBooks, distanceBlocks); }
        Data plusStartingBooks(long d)      { return new Data(trainTicks, randomBooksRead, startingBooksRead + d, playersEncountered, totalDeaths, totalCarriages, totalDistance, totalFriends, totalBooks, distanceBlocks); }
        Data plusPlayersEncountered(long d) { return new Data(trainTicks, randomBooksRead, startingBooksRead, playersEncountered + d, totalDeaths, totalCarriages, totalDistance, totalFriends, totalBooks, distanceBlocks); }
        Data plusDeaths(long d)             { return new Data(trainTicks, randomBooksRead, startingBooksRead, playersEncountered, totalDeaths + d, totalCarriages, totalDistance, totalFriends, totalBooks, distanceBlocks); }
        Data plusCarriages(long d)          { return new Data(trainTicks, randomBooksRead, startingBooksRead, playersEncountered, totalDeaths, totalCarriages + d, totalDistance, totalFriends, totalBooks, distanceBlocks); }
        Data plusDistance(double d)         { return new Data(trainTicks, randomBooksRead, startingBooksRead, playersEncountered, totalDeaths, totalCarriages, totalDistance + d, totalFriends, totalBooks, distanceBlocks); }
        Data plusFriends(long d)            { return new Data(trainTicks, randomBooksRead, startingBooksRead, playersEncountered, totalDeaths, totalCarriages, totalDistance, totalFriends + d, totalBooks, distanceBlocks); }
        Data plusBooks(long d)              { return new Data(trainTicks, randomBooksRead, startingBooksRead, playersEncountered, totalDeaths, totalCarriages, totalDistance, totalFriends, totalBooks + d, distanceBlocks); }
        Data plusDistanceBlocks(double d)   { return new Data(trainTicks, randomBooksRead, startingBooksRead, playersEncountered, totalDeaths, totalCarriages, totalDistance, totalFriends, totalBooks, distanceBlocks + d); }
    }

    /** In-memory cache. Holds the full {@link Data} record per UUID. */
    private static final Map<UUID, Data> CACHE = new ConcurrentHashMap<>();

    private GlobalPlayerStats() {}

    public static Path file(UUID playerUuid) {
        return FMLPaths.CONFIGDIR.get().resolve(DIR_NAME).resolve(playerUuid + ".json");
    }

    /** The player's full cached record, loading from disk on first access. */
    private static Data current(UUID uuid) {
        return CACHE.computeIfAbsent(uuid, GlobalPlayerStats::loadFromDisk);
    }

    // ---- Getters (read disk on first access per UUID) ----

    public static long trainTicks(UUID uuid)         { return current(uuid).trainTicks(); }
    public static long randomBooksRead(UUID uuid)    { return current(uuid).randomBooksRead(); }
    public static long startingBooksRead(UUID uuid)  { return current(uuid).startingBooksRead(); }
    public static long playersEncountered(UUID uuid) { return current(uuid).playersEncountered(); }
    public static long totalDeaths(UUID uuid)        { return current(uuid).totalDeaths(); }
    public static long totalCarriages(UUID uuid)     { return current(uuid).totalCarriages(); }
    public static double totalDistance(UUID uuid)    { return current(uuid).totalDistance(); }
    public static long totalFriends(UUID uuid)       { return current(uuid).totalFriends(); }
    public static long totalBooks(UUID uuid)         { return current(uuid).totalBooks(); }
    public static double distanceBlocks(UUID uuid)   { return current(uuid).distanceBlocks(); }

    // ---- Adders. All monotone-increasing: non-positive deltas are ignored. ----

    public static long addTrainTicks(UUID uuid, long delta) {
        if (delta <= 0) return trainTicks(uuid);
        return CACHE.compute(uuid, (k, e) -> (e != null ? e : loadFromDisk(k)).plusTrainTicks(delta)).trainTicks();
    }

    public static long addRandomBooksRead(UUID uuid, long delta) {
        if (delta <= 0) return randomBooksRead(uuid);
        return CACHE.compute(uuid, (k, e) -> (e != null ? e : loadFromDisk(k)).plusRandomBooks(delta)).randomBooksRead();
    }

    public static long addStartingBooksRead(UUID uuid, long delta) {
        if (delta <= 0) return startingBooksRead(uuid);
        return CACHE.compute(uuid, (k, e) -> (e != null ? e : loadFromDisk(k)).plusStartingBooks(delta)).startingBooksRead();
    }

    public static long addPlayersEncountered(UUID uuid, long delta) {
        if (delta <= 0) return playersEncountered(uuid);
        return CACHE.compute(uuid, (k, e) -> (e != null ? e : loadFromDisk(k)).plusPlayersEncountered(delta)).playersEncountered();
    }

    public static long addDeaths(UUID uuid, long delta) {
        if (delta <= 0) return totalDeaths(uuid);
        return CACHE.compute(uuid, (k, e) -> (e != null ? e : loadFromDisk(k)).plusDeaths(delta)).totalDeaths();
    }

    public static long addCarriages(UUID uuid, long delta) {
        if (delta <= 0) return totalCarriages(uuid);
        return CACHE.compute(uuid, (k, e) -> (e != null ? e : loadFromDisk(k)).plusCarriages(delta)).totalCarriages();
    }

    public static double addDistance(UUID uuid, double delta) {
        if (delta <= 0) return totalDistance(uuid);
        return CACHE.compute(uuid, (k, e) -> (e != null ? e : loadFromDisk(k)).plusDistance(delta)).totalDistance();
    }

    public static long addFriends(UUID uuid, long delta) {
        if (delta <= 0) return totalFriends(uuid);
        return CACHE.compute(uuid, (k, e) -> (e != null ? e : loadFromDisk(k)).plusFriends(delta)).totalFriends();
    }

    public static long addBooks(UUID uuid, long delta) {
        if (delta <= 0) return totalBooks(uuid);
        return CACHE.compute(uuid, (k, e) -> (e != null ? e : loadFromDisk(k)).plusBooks(delta)).totalBooks();
    }

    /**
     * Add {@code delta} metres to the player's cumulative boarded-distance
     * counter and return the new total. Monotone-increasing; non-finite / ≤0
     * deltas are ignored. Drives the lifetime distance advancements.
     */
    public static double addDistanceBlocks(UUID uuid, double delta) {
        if (delta <= 0.0 || !Double.isFinite(delta)) return distanceBlocks(uuid);
        return CACHE.compute(uuid, (k, e) -> (e != null ? e : loadFromDisk(k)).plusDistanceBlocks(delta)).distanceBlocks();
    }

    /** Flush a single player's cached stats to disk. No-op if not in cache. */
    public static void flush(UUID uuid) {
        Data data = CACHE.get(uuid);
        if (data == null) return;
        saveToDisk(uuid, data);
    }

    /** Flush every cached player's stats. Called from server-stop hook. */
    public static void flushAll() {
        Map<UUID, Data> snapshot = new HashMap<>(CACHE);
        for (var entry : snapshot.entrySet()) {
            saveToDisk(entry.getKey(), entry.getValue());
        }
    }

    /** Drop the cached entry for {@code uuid} after persistence. Used on logout. */
    public static void evict(UUID uuid) {
        CACHE.remove(uuid);
    }

    private static Data loadFromDisk(UUID uuid) {
        Path path = file(uuid);
        if (!Files.isRegularFile(path)) return Data.EMPTY;
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement element = JsonParser.parseReader(reader);
            var result = Data.CODEC.parse(JsonOps.INSTANCE, element);
            if (result.error().isPresent()) {
                LOGGER.warn("[DungeonTrain] GlobalPlayerStats: parse failed for {}: {}",
                    path, result.error().get().message());
                return Data.EMPTY;
            }
            return result.result().orElse(Data.EMPTY);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] GlobalPlayerStats: I/O error reading {}: {}",
                path, e.getMessage());
            return Data.EMPTY;
        }
    }

    private static synchronized void saveToDisk(UUID uuid, Data data) {
        Path path = file(uuid);
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalPlayerStats: failed to create dir {}: {}",
                path.getParent(), e.getMessage());
            return;
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        var result = Data.CODEC.encodeStart(JsonOps.INSTANCE, data);
        if (result.error().isPresent()) {
            LOGGER.error("[DungeonTrain] GlobalPlayerStats: encode failed: {}",
                result.error().get().message());
            return;
        }
        JsonElement element = result.result().orElseThrow();
        try (Writer writer = Files.newBufferedWriter(tmp)) {
            writer.write(element.toString());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalPlayerStats: write tmp {} failed: {}",
                tmp, e.getMessage());
            return;
        }
        try {
            Files.move(tmp, path,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                LOGGER.error("[DungeonTrain] GlobalPlayerStats: rename {} -> {} failed: {}",
                    tmp, path, e2.getMessage());
            }
        }
    }
}
