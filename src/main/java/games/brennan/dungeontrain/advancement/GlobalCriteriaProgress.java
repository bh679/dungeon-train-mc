package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.player.DifficultyPartition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * The half-finished side of the cross-world profile: for each multi-criteria advancement a player
 * has started but not finished, the criteria they already hold. Lives beside
 * {@link GlobalAchievementStore}'s files, at
 * {@code <gameDir>/dungeontrain/achievements/<uuid>[.<partition>].criteria.json}.
 *
 * <p><b>Why.</b> {@link GlobalAchievementStore} banks only <em>finished</em> advancements, so a
 * collection like "Most Wanted" (photograph every hostile mob) kept its progress only in the world it
 * was earned in. Every new world — a new run, a pack update followed by a fresh world, a difficulty
 * swap — started it from zero. This store carries those criteria across, and
 * {@link AdvancementProfiles} replays them on login.</p>
 *
 * <p>Entries leave once the advancement is done (the finished store has it from then on). Same
 * partitions, cache and off-thread writing as {@link GlobalAchievementStore}: after a profile's
 * first read nothing here touches the disk on the server thread.</p>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "progress": {
 *     "dungeontrain:enchiridion/most_wanted": ["minecraft:blaze", "minecraft:creeper"]
 * } }
 * }</pre>
 */
public final class GlobalCriteriaProgress {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long FLUSH_TIMEOUT_SECONDS = 10;
    private static final String SUFFIX = ".criteria.json";

    private record Key(UUID uuid, String partition) {}

    /** Each loaded partition's contents (unmodifiable snapshots). */
    private static final Map<Key, Map<ResourceLocation, Set<String>>> CACHE = new ConcurrentHashMap<>();
    /** The newest snapshot of each partition still waiting to be written. */
    private static final Map<Key, Map<ResourceLocation, Set<String>>> PENDING = new ConcurrentHashMap<>();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "DungeonTrain-CriteriaStore");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile BiFunction<UUID, String, Path> resolver = GlobalCriteriaProgress::file;

    /** Schema record. Criteria are stored as lists to keep the JSON in earn order. */
    public record Data(Map<ResourceLocation, List<String>> progress) {
        public static final Codec<Data> CODEC = RecordCodecBuilder.create(in -> in.group(
            Codec.unboundedMap(ResourceLocation.CODEC, Codec.STRING.listOf())
                .optionalFieldOf("progress", Map.of()).forGetter(Data::progress)
        ).apply(in, Data::new));

        public static final Data EMPTY = new Data(Map.of());
    }

    private static final List<String> ALL_PARTITIONS = Stream.concat(
        Arrays.stream(Difficulty.values()).map(DifficultyPartition::keyFor),
        Stream.of(GlobalAchievementStore.SHARED)).toList();

    private GlobalCriteriaProgress() {}

    /** {@code <uuid>.criteria.json} for Normal, {@code <uuid>.<partition>.criteria.json} otherwise. */
    public static String fileName(UUID playerUuid, String partitionKey) {
        return DifficultyPartition.suffixFor(partitionKey).isEmpty()
            ? playerUuid + SUFFIX
            : playerUuid + "." + partitionKey + SUFFIX;
    }

    public static Path file(UUID playerUuid, String partitionKey) {
        return PlayerDataPaths.dir(PlayerDataPaths.ACHIEVEMENTS).resolve(fileName(playerUuid, partitionKey));
    }

    /** Does {@code playerUuid} have a criteria file in any partition? */
    public static boolean hasAny(UUID playerUuid) {
        for (String partition : ALL_PARTITIONS) {
            if (Files.isRegularFile(resolver.apply(playerUuid, partition))) return true;
        }
        return false;
    }

    /** Every started advancement in the partition and the criteria held for it. */
    public static synchronized Map<ResourceLocation, Set<String>> read(UUID playerUuid, String partitionKey) {
        return cached(new Key(playerUuid, partitionKey));
    }

    /** The criteria held for {@code advancement}, or none. */
    public static synchronized Set<String> criteria(UUID playerUuid, String partitionKey, ResourceLocation advancement) {
        return read(playerUuid, partitionKey).getOrDefault(advancement, Set.of());
    }

    /**
     * Make {@code criteria} the advancement's banked progress, merged with what is already held.
     *
     * @return the number of criteria newly banked
     */
    public static synchronized int addAll(UUID playerUuid, String partitionKey, ResourceLocation advancement,
                                          Collection<String> criteria) {
        if (criteria.isEmpty()) return 0;
        Key key = new Key(playerUuid, partitionKey);
        Map<ResourceLocation, Set<String>> current = mutableCopy(cached(key));
        Set<String> held = current.computeIfAbsent(advancement, id -> new LinkedHashSet<>());
        int before = held.size();
        held.addAll(criteria);
        int added = held.size() - before;
        if (added > 0) store(key, current);
        return added;
    }

    /** Drop one criterion. The advancement's entry goes too once nothing is left in it. */
    public static synchronized boolean removeCriterion(UUID playerUuid, String partitionKey,
                                                       ResourceLocation advancement, String criterion) {
        Key key = new Key(playerUuid, partitionKey);
        Map<ResourceLocation, Set<String>> current = mutableCopy(cached(key));
        Set<String> held = current.get(advancement);
        if (held == null || !held.remove(criterion)) return false;
        if (held.isEmpty()) current.remove(advancement);
        store(key, current);
        return true;
    }

    /** Drop the advancement's whole entry — it was finished, or struck. */
    public static synchronized boolean removeAdvancement(UUID playerUuid, String partitionKey, ResourceLocation advancement) {
        Key key = new Key(playerUuid, partitionKey);
        Map<ResourceLocation, Set<String>> current = mutableCopy(cached(key));
        if (current.remove(advancement) == null) return false;
        store(key, current);
        return true;
    }

    /** Drop the advancement from every partition — a relay revocation covers the player, not one difficulty. */
    public static synchronized boolean removeEverywhere(UUID playerUuid, ResourceLocation advancement) {
        boolean removed = false;
        for (String partition : ALL_PARTITIONS) removed |= removeAdvancement(playerUuid, partition, advancement);
        return removed;
    }

    /** Delete every criteria file for {@code playerUuid} — the Video Tools profile reset. */
    public static synchronized boolean deleteFor(UUID playerUuid) throws IOException {
        flush();
        evict(playerUuid);
        boolean removed = false;
        for (String partition : ALL_PARTITIONS) {
            removed |= Files.deleteIfExists(resolver.apply(playerUuid, partition));
        }
        return removed;
    }

    // ---------------- Cache + background writer ----------------

    private static Map<ResourceLocation, Set<String>> cached(Key key) {
        return CACHE.computeIfAbsent(key, k -> {
            Map<ResourceLocation, Set<String>> pending = PENDING.get(k);
            return pending != null ? pending : freeze(readFile(resolver.apply(k.uuid(), k.partition())));
        });
    }

    private static void store(Key key, Map<ResourceLocation, Set<String>> progress) {
        Map<ResourceLocation, Set<String>> snapshot = freeze(progress);
        CACHE.put(key, snapshot);
        PENDING.put(key, snapshot);
        WRITER.execute(() -> writePending(key));
    }

    private static void writePending(Key key) {
        Map<ResourceLocation, Set<String>> snapshot = PENDING.get(key);
        if (snapshot == null) return;
        try {
            writeAtomic(resolver.apply(key.uuid(), key.partition()), snapshot);
        } catch (RuntimeException e) {
            LOGGER.error("[DungeonTrain] GlobalCriteriaProgress: background write for {} failed", key, e);
        }
        PENDING.remove(key, snapshot);
    }

    private static Map<ResourceLocation, Set<String>> mutableCopy(Map<ResourceLocation, Set<String>> progress) {
        Map<ResourceLocation, Set<String>> copy = new LinkedHashMap<>();
        progress.forEach((id, criteria) -> copy.put(id, new LinkedHashSet<>(criteria)));
        return copy;
    }

    private static Map<ResourceLocation, Set<String>> freeze(Map<ResourceLocation, Set<String>> progress) {
        Map<ResourceLocation, Set<String>> copy = new LinkedHashMap<>();
        progress.forEach((id, criteria) -> {
            if (!criteria.isEmpty()) copy.put(id, Collections.unmodifiableSet(new LinkedHashSet<>(criteria)));
        });
        return Collections.unmodifiableMap(copy);
    }

    /** Wait for every write queued so far to reach the disk. */
    public static void flush() {
        try {
            WRITER.submit(() -> {}).get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("[DungeonTrain] GlobalCriteriaProgress: flush interrupted");
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[DungeonTrain] GlobalCriteriaProgress: flush did not finish: {}", e.toString());
        }
    }

    /** Logout: forget the player's cached progress without waiting for the writer. */
    public static void evict(UUID playerUuid) {
        CACHE.keySet().removeIf(key -> key.uuid().equals(playerUuid));
    }

    /** Server stop: land every queued write and empty the cache. */
    public static void flushAndClearAll() {
        flush();
        CACHE.clear();
    }

    static Map<ResourceLocation, Set<String>> readFile(Path path) {
        if (!Files.isRegularFile(path)) return Map.of();
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement element = JsonParser.parseReader(reader);
            var result = Data.CODEC.parse(JsonOps.INSTANCE, element);
            if (result.error().isPresent()) {
                LOGGER.warn("[DungeonTrain] GlobalCriteriaProgress: failed to parse {}: {}",
                    path, result.error().get().message());
                return Map.of();
            }
            Map<ResourceLocation, Set<String>> progress = new LinkedHashMap<>();
            result.result().orElse(Data.EMPTY).progress()
                .forEach((id, criteria) -> progress.put(id, new LinkedHashSet<>(criteria)));
            return progress;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] GlobalCriteriaProgress: couldn't read {}: {}", path, e.toString());
            return Map.of();
        }
    }

    static void writeAtomic(Path path, Map<ResourceLocation, Set<String>> progress) {
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalCriteriaProgress: failed to create dir {}: {}",
                path.getParent(), e.getMessage());
            return;
        }
        Map<ResourceLocation, List<String>> lists = new LinkedHashMap<>();
        progress.forEach((id, criteria) -> lists.put(id, new ArrayList<>(criteria)));
        var result = Data.CODEC.encodeStart(JsonOps.INSTANCE, new Data(lists));
        if (result.error().isPresent()) {
            LOGGER.error("[DungeonTrain] GlobalCriteriaProgress: encode failed: {}", result.error().get().message());
            return;
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp)) {
            writer.write(result.result().orElseThrow().toString());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalCriteriaProgress: write tmp {} failed: {}", tmp, e.getMessage());
            return;
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                LOGGER.error("[DungeonTrain] GlobalCriteriaProgress: rename {} -> {} failed: {}",
                    tmp, path, e2.getMessage());
            }
        }
    }

    // ---------------- Tests ----------------

    static void useDirectoryForTests(Path dir) {
        flushAndClearAll();
        resolver = (uuid, partition) -> dir.resolve(fileName(uuid, partition));
    }

    static void resetForTests() {
        flushAndClearAll();
        resolver = GlobalCriteriaProgress::file;
    }
}
