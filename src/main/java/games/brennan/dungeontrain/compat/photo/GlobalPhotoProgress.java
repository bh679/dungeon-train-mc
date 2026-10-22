package games.brennan.dungeontrain.compat.photo;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * The photo tallies behind The Enchiridion's tiers, kept across worlds: how many animal / hostile /
 * passenger shots a player has taken ({@link PhotoCounts}) and which biomes they have photographed
 * ({@link PhotoAdvancementEvents}). Lives at {@code <gameDir>/dungeontrain/stats/<uuid>.photos.json}.
 *
 * <p>The world's persistent data stays the working copy; this file mirrors it. On login the two are
 * merged (the larger count, the union of biomes) so a new world carries on from the last one, and
 * every change is pushed back here. Merging only ever grows a tally, so the mirror is safe to
 * re-apply in any order.</p>
 *
 * <p>Writes happen on one background thread, like {@code GlobalAchievementStore}: a photo must
 * never wait on the disk.</p>
 */
public final class GlobalPhotoProgress {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long FLUSH_TIMEOUT_SECONDS = 10;

    /** One player's tallies. Immutable. */
    public record Tally(Map<String, Integer> counts, Set<String> biomes) {
        public static final Codec<Tally> CODEC = RecordCodecBuilder.create(in -> in.group(
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("counts", Map.of()).forGetter(Tally::counts),
            Codec.STRING.listOf().xmap(list -> (Set<String>) new LinkedHashSet<>(list), set -> List.copyOf(set))
                .optionalFieldOf("biomes", Set.of()).forGetter(Tally::biomes)
        ).apply(in, Tally::new));

        public static final Tally EMPTY = new Tally(Map.of(), Set.of());

        public Tally {
            counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
            biomes = Collections.unmodifiableSet(new LinkedHashSet<>(biomes));
        }

        /** The larger of each count and every biome from both. */
        public Tally merge(Tally other) {
            Map<String, Integer> mergedCounts = new LinkedHashMap<>(counts);
            other.counts.forEach((category, n) -> mergedCounts.merge(category, n, Math::max));
            Set<String> mergedBiomes = new LinkedHashSet<>(biomes);
            mergedBiomes.addAll(other.biomes);
            return new Tally(mergedCounts, mergedBiomes);
        }
    }

    private static final Map<UUID, Tally> CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, Tally> PENDING = new ConcurrentHashMap<>();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "DungeonTrain-PhotoProgress");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Function<UUID, Path> resolver = GlobalPhotoProgress::file;

    private GlobalPhotoProgress() {}

    public static Path file(UUID playerUuid) {
        return PlayerDataPaths.dir(PlayerDataPaths.STATS).resolve(playerUuid + ".photos.json");
    }

    public static boolean hasAny(UUID playerUuid) {
        return Files.isRegularFile(resolver.apply(playerUuid));
    }

    /** The player's banked tallies (empty when none). */
    public static synchronized Tally read(UUID playerUuid) {
        return cached(playerUuid);
    }

    /**
     * Merge {@code tally} into the banked tallies and queue the write when anything grew.
     *
     * @return the merged tallies
     */
    public static synchronized Tally merge(UUID playerUuid, Tally tally) {
        Tally current = cached(playerUuid);
        Tally merged = current.merge(tally);
        if (!merged.equals(current)) {
            CACHE.put(playerUuid, merged);
            PENDING.put(playerUuid, merged);
            WRITER.execute(() -> writePending(playerUuid));
        }
        return merged;
    }

    /** The Video Tools stats reset. */
    public static synchronized boolean deleteFor(UUID playerUuid) throws IOException {
        flush();
        evict(playerUuid);
        return Files.deleteIfExists(resolver.apply(playerUuid));
    }

    public static void evict(UUID playerUuid) {
        CACHE.remove(playerUuid);
    }

    public static void flushAndClearAll() {
        flush();
        CACHE.clear();
    }

    public static void flush() {
        try {
            WRITER.submit(() -> {}).get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("[DungeonTrain] GlobalPhotoProgress: flush interrupted");
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[DungeonTrain] GlobalPhotoProgress: flush did not finish: {}", e.toString());
        }
    }

    private static Tally cached(UUID playerUuid) {
        return CACHE.computeIfAbsent(playerUuid, uuid -> {
            Tally pending = PENDING.get(uuid);
            return pending != null ? pending : readFile(resolver.apply(uuid));
        });
    }

    private static void writePending(UUID playerUuid) {
        Tally snapshot = PENDING.get(playerUuid);
        if (snapshot == null) return;
        try {
            writeAtomic(resolver.apply(playerUuid), snapshot);
        } catch (RuntimeException e) {
            LOGGER.error("[DungeonTrain] GlobalPhotoProgress: background write for {} failed", playerUuid, e);
        }
        PENDING.remove(playerUuid, snapshot);
    }

    static Tally readFile(Path path) {
        if (!Files.isRegularFile(path)) return Tally.EMPTY;
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement element = JsonParser.parseReader(reader);
            var result = Tally.CODEC.parse(JsonOps.INSTANCE, element);
            if (result.error().isPresent()) {
                LOGGER.warn("[DungeonTrain] GlobalPhotoProgress: failed to parse {}: {}",
                    path, result.error().get().message());
                return Tally.EMPTY;
            }
            return result.result().orElse(Tally.EMPTY);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] GlobalPhotoProgress: couldn't read {}: {}", path, e.toString());
            return Tally.EMPTY;
        }
    }

    static void writeAtomic(Path path, Tally tally) {
        var result = Tally.CODEC.encodeStart(JsonOps.INSTANCE, tally);
        if (result.error().isPresent()) {
            LOGGER.error("[DungeonTrain] GlobalPhotoProgress: encode failed: {}", result.error().get().message());
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
            } catch (IOException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalPhotoProgress: write {} failed: {}", path, e.getMessage());
        }
    }

    // ---------------- Tests ----------------

    static void useDirectoryForTests(Path dir) {
        flushAndClearAll();
        resolver = uuid -> dir.resolve(uuid + ".photos.json");
    }

    static void resetForTests() {
        flushAndClearAll();
        resolver = GlobalPhotoProgress::file;
    }
}
