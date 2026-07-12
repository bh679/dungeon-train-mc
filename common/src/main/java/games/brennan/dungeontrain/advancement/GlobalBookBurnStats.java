package games.brennan.dungeontrain.advancement;
import games.brennan.dungeontrain.platform.DtPlatform;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
 * Cumulative per-player "books burned without ever being opened" counter, persisted
 * outside any individual world save. Sibling to {@link GlobalPlayerStats}, but kept as
 * its own tiny store rather than a new field there — {@link GlobalPlayerStats.Data}'s
 * {@code RecordCodecBuilder.group(...)} is already at the documented 16-field cap (see
 * that class's {@code Damage} nesting comment).
 *
 * <p>Lives at {@code <minecraft>/config/dungeontrain-stats/<uuid>-bookburn.json} — same
 * directory as {@link GlobalPlayerStats}, distinct filename so the two codecs never
 * collide. Same disk-I/O strategy: in-memory cache, atomic write, flush on logout /
 * server-stop / threshold cross.</p>
 */
public final class GlobalBookBurnStats {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIR_NAME = "dungeontrain-stats";
    private static final String FILE_SUFFIX = "-bookburn.json";

    private record Data(long booksBurnedUnread) {
        static final Codec<Data> CODEC = RecordCodecBuilder.create(in -> in.group(
            Codec.LONG.optionalFieldOf("booksBurnedUnread", 0L).forGetter(Data::booksBurnedUnread)
        ).apply(in, Data::new));

        static final Data EMPTY = new Data(0L);
    }

    private static final Map<UUID, Data> CACHE = new ConcurrentHashMap<>();

    private GlobalBookBurnStats() {}

    public static Path file(UUID playerUuid) {
        return DtPlatform.get().configDir().resolve(DIR_NAME).resolve(playerUuid + FILE_SUFFIX);
    }

    private static Data current(UUID uuid) {
        return CACHE.computeIfAbsent(uuid, GlobalBookBurnStats::loadFromDisk);
    }

    public static long booksBurnedUnread(UUID uuid) {
        return current(uuid).booksBurnedUnread();
    }

    /** Add {@code delta} to the player's unread-burn counter and return the new total. */
    public static long addBooksBurnedUnread(UUID uuid, long delta) {
        if (delta <= 0) return booksBurnedUnread(uuid);
        return CACHE.compute(uuid, (k, e) -> new Data((e != null ? e : loadFromDisk(k)).booksBurnedUnread() + delta))
            .booksBurnedUnread();
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
                LOGGER.warn("[DungeonTrain] GlobalBookBurnStats: parse failed for {}: {}",
                    path, result.error().get().message());
                return Data.EMPTY;
            }
            return result.result().orElse(Data.EMPTY);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] GlobalBookBurnStats: I/O error reading {}: {}",
                path, e.getMessage());
            return Data.EMPTY;
        }
    }

    private static synchronized void saveToDisk(UUID uuid, Data data) {
        Path path = file(uuid);
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalBookBurnStats: failed to create dir {}: {}",
                path.getParent(), e.getMessage());
            return;
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        var result = Data.CODEC.encodeStart(JsonOps.INSTANCE, data);
        if (result.error().isPresent()) {
            LOGGER.error("[DungeonTrain] GlobalBookBurnStats: encode failed: {}",
                result.error().get().message());
            return;
        }
        JsonElement element = result.result().orElseThrow();
        try (Writer writer = Files.newBufferedWriter(tmp)) {
            writer.write(element.toString());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalBookBurnStats: write tmp {} failed: {}",
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
                LOGGER.error("[DungeonTrain] GlobalBookBurnStats: rename {} -> {} failed: {}",
                    tmp, path, e2.getMessage());
            }
        }
    }
}
