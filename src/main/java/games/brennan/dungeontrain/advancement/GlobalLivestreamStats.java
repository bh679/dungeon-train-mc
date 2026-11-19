package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.minecraft.Util;
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
 * Total server ticks a player has spent livestreaming, for "For The Fans": kept across deaths and across
 * worlds. A run gets a new world, so a total saved in the world never got past one run's streams. Sibling to
 * {@link GlobalPlayerStats}, as its own tiny store because {@code GlobalPlayerStats.Data} is at the codec's
 * 16-field cap.
 *
 * <p>Lives at {@code <gameDir>/dungeontrain/stats/<uuid>-livestream.json}. It only ever grows, short of the
 * profile reset ({@link #deleteFor}): it counts up each second on air, and the relay's own all-time figure
 * raises it at login ({@link #creditRelay}). The in-memory total is written every few minutes on air and when a
 * stream ends, off the server thread, and on logout and server stop.</p>
 */
public final class GlobalLivestreamStats {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_SUFFIX = "-livestream.json";
    private static final long TICKS_PER_SECOND = 20L;

    private record Data(long streamTicks) {
        static final Codec<Data> CODEC = RecordCodecBuilder.create(in -> in.group(
            Codec.LONG.optionalFieldOf("streamTicks", 0L).forGetter(Data::streamTicks)
        ).apply(in, Data::new));
    }

    private static final Map<UUID, Long> CACHE = new ConcurrentHashMap<>();

    private GlobalLivestreamStats() {}

    /** {@code <gameDir>/dungeontrain/stats/<uuid>-livestream.json}. */
    public static Path file(UUID uuid) {
        return PlayerDataPaths.dir(PlayerDataPaths.STATS).resolve(uuid + FILE_SUFFIX);
    }

    /** Every tick {@code uuid} has streamed, in any life or world. */
    public static long streamTicks(UUID uuid) {
        return CACHE.computeIfAbsent(uuid, GlobalLivestreamStats::load);
    }

    /** Add {@code delta} ticks and return the new total. */
    public static long addStreamTicks(UUID uuid, long delta) {
        if (delta <= 0) return streamTicks(uuid);
        return CACHE.compute(uuid, (k, old) -> (old != null ? old : load(k)) + delta);
    }

    /**
     * Raise the total to what the relay recorded on air, saving if that changed it. Returns the new total.
     * The relay's figure covers the same streams this total counts, so it replaces rather than adds.
     */
    public static long creditRelay(UUID uuid, long relaySeconds) {
        long before = streamTicks(uuid);
        long after = CACHE.compute(uuid, (k, old) -> credited(old != null ? old : load(k), relaySeconds));
        if (after != before) saveAsync(uuid);
        return after;
    }

    /** The higher of {@code localTicks} and {@code relaySeconds} in ticks. A nonsense relay figure counts for nothing. */
    static long credited(long localTicks, long relaySeconds) {
        if (relaySeconds <= 0 || relaySeconds > Long.MAX_VALUE / TICKS_PER_SECOND) return localTicks;
        return Math.max(localTicks, relaySeconds * TICKS_PER_SECOND);
    }

    /** Write {@code uuid}'s total off the server thread. No-op if not in cache. */
    public static void saveAsync(UUID uuid) {
        Long ticks = CACHE.get(uuid);
        if (ticks == null) return;
        Path path = file(uuid);
        Util.ioPool().execute(() -> save(path, ticks));
    }

    /** Write a single player's total to disk now. No-op if not in cache. */
    public static void flush(UUID uuid) {
        Long ticks = CACHE.get(uuid);
        if (ticks != null) save(file(uuid), ticks);
    }

    /** Write every cached player's total. Called from the server-stop hook. */
    public static void flushAll() {
        new HashMap<>(CACHE).forEach((uuid, ticks) -> save(file(uuid), ticks));
    }

    /** Drop the cached entry for {@code uuid} after persistence. Used on logout. */
    public static void evict(UUID uuid) {
        CACHE.remove(uuid);
    }

    /**
     * Wipe the player's total, cache first so {@link #flushAll} cannot resurrect it.
     *
     * @return true when a file was actually removed
     */
    public static boolean deleteFor(UUID uuid) throws IOException {
        CACHE.remove(uuid);
        return Files.deleteIfExists(file(uuid));
    }

    private static long load(UUID uuid) {
        Long ticks = read(file(uuid));
        return ticks != null ? ticks : 0L;
    }

    /** The total in {@code path}: zero when there is no file, {@code null} when it can't be read. */
    static Long read(Path path) {
        if (!Files.isRegularFile(path)) return 0L;
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement element = JsonParser.parseReader(reader);
            var result = Data.CODEC.parse(JsonOps.INSTANCE, element);
            if (result.error().isPresent()) {
                LOGGER.error("[DungeonTrain] GlobalLivestreamStats: parse failed for {}: {}",
                    path, result.error().get().message());
                return null;
            }
            return result.result().map(Data::streamTicks).orElse(0L);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[DungeonTrain] GlobalLivestreamStats: couldn't read {}: {}", path, e.toString());
            return null;
        }
    }

    /** Move an unreadable file aside, so writing a fresh one never destroys what it held. */
    private static void keepUnreadable(Path path) {
        Path aside = path.resolveSibling(path.getFileName() + ".unreadable-" + System.currentTimeMillis());
        try {
            Files.move(path, aside);
            LOGGER.error("[DungeonTrain] GlobalLivestreamStats: kept unreadable {} as {}", path, aside);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalLivestreamStats: couldn't move unreadable {} aside: {}", path, e.toString());
        }
    }

    /**
     * Write {@code ticks} atomically. Writes are serialised and never lower the file, so an older total landing
     * late can't undo a newer one, and a file that can't be read is moved aside rather than written over.
     */
    static synchronized void save(Path path, long ticks) {
        Long onDisk = read(path);
        if (onDisk == null) {
            keepUnreadable(path);
            onDisk = 0L;
        }
        var result = Data.CODEC.encodeStart(JsonOps.INSTANCE, new Data(Math.max(onDisk, ticks)));
        if (result.error().isPresent()) {
            LOGGER.error("[DungeonTrain] GlobalLivestreamStats: encode failed: {}", result.error().get().message());
            return;
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(tmp)) {
                writer.write(result.result().orElseThrow().toString());
            }
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalLivestreamStats: couldn't write {}: {}", path, e.toString());
        }
    }
}
