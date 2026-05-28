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
 *       "I Live Here Now", and "Help, I Can't Get Off" milestones.</li>
 *   <li>{@code randomBooksRead} — total right-clicks on random-book items.
 *       Re-reads count. Drives the "Taking Notes" milestone.</li>
 * </ul>
 * Both counters accrue across worlds and sessions; switching worlds does
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

    public record Data(long trainTicks, long randomBooksRead) {
        public static final Codec<Data> CODEC = RecordCodecBuilder.create(in -> in.group(
            Codec.LONG.optionalFieldOf("trainTicks", 0L).forGetter(Data::trainTicks),
            Codec.LONG.optionalFieldOf("randomBooksRead", 0L).forGetter(Data::randomBooksRead)
        ).apply(in, Data::new));

        public static final Data EMPTY = new Data(0L, 0L);
    }

    /** In-memory cache. Holds the full {@link Data} record per UUID. */
    private static final Map<UUID, Data> CACHE = new ConcurrentHashMap<>();

    private GlobalPlayerStats() {}

    public static Path file(UUID playerUuid) {
        return FMLPaths.CONFIGDIR.get().resolve(DIR_NAME).resolve(playerUuid + ".json");
    }

    /** Current accumulated train ticks for {@code uuid}. Reads disk on first access. */
    public static long trainTicks(UUID uuid) {
        return CACHE.computeIfAbsent(uuid, GlobalPlayerStats::loadFromDisk).trainTicks();
    }

    /** Current accumulated random-book read count for {@code uuid}. Reads disk on first access. */
    public static long randomBooksRead(UUID uuid) {
        return CACHE.computeIfAbsent(uuid, GlobalPlayerStats::loadFromDisk).randomBooksRead();
    }

    /**
     * Add {@code delta} ticks to the player's cumulative train-time counter
     * and return the new total. Delta must be non-negative; negatives are
     * ignored (counter is monotone-increasing — backward movement / death /
     * etc. does not subtract time).
     */
    public static long addTrainTicks(UUID uuid, long delta) {
        if (delta <= 0) return trainTicks(uuid);
        Data updated = CACHE.compute(uuid, (k, existing) -> {
            Data base = existing != null ? existing : loadFromDisk(k);
            return new Data(base.trainTicks() + delta, base.randomBooksRead());
        });
        return updated.trainTicks();
    }

    /**
     * Add {@code delta} reads to the player's cumulative random-book read
     * counter and return the new total. Monotone-increasing.
     */
    public static long addRandomBooksRead(UUID uuid, long delta) {
        if (delta <= 0) return randomBooksRead(uuid);
        Data updated = CACHE.compute(uuid, (k, existing) -> {
            Data base = existing != null ? existing : loadFromDisk(k);
            return new Data(base.trainTicks(), base.randomBooksRead() + delta);
        });
        return updated.randomBooksRead();
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
