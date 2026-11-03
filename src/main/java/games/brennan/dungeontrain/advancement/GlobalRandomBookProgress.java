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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every random-book variant a player has held, for "Faulthurst's Favourite" (and "I Must Know"): kept for good,
 * across deaths and across worlds. Nothing here ever removes a variant — the record only grows. The world's own
 * list ({@link games.brennan.dungeontrain.narrative.NarrativeProgressData}) still starts empty in each new world
 * and still drives loot; this one is what the player has read, wherever they read it.
 *
 * <p>Lives at {@code <gameDir>/dungeontrain/stats/<uuid>-random-books.json}, beside
 * {@link GlobalBiomeProgress}, and works the same way: a new variant is rare, so each one is written straight
 * away — off the server thread, atomically, merged with what is on disk.</p>
 */
public final class GlobalRandomBookProgress {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME_SUFFIX = "-random-books.json";

    /** On-disk shape: {@code {"seen": {"musings_of_faulthurst": [0, 3, 7]}}}. */
    record Data(Map<String, List<Integer>> seen) {
        static final Codec<Data> CODEC = RecordCodecBuilder.create(in -> in.group(
            Codec.unboundedMap(Codec.STRING, Codec.INT.listOf()).optionalFieldOf("seen", Map.of()).forGetter(Data::seen)
        ).apply(in, Data::new));
    }

    /** Each cached player's variants, as an immutable map replaced whole on every addition. */
    private static final Map<UUID, Map<String, Set<Integer>>> CACHE = new ConcurrentHashMap<>();

    private GlobalRandomBookProgress() {}

    /** {@code <gameDir>/dungeontrain/stats/<uuid>-random-books.json}. */
    static Path file(UUID uuid) {
        return PlayerDataPaths.dir(PlayerDataPaths.STATS).resolve(uuid + FILE_NAME_SUFFIX);
    }

    /** Every random-book variant {@code uuid} has held, in any life or world: basename to variant indices. */
    public static Map<String, Set<Integer>> seen(UUID uuid) {
        return CACHE.computeIfAbsent(uuid, k -> load(file(k)));
    }

    /** Whether {@code uuid} has ever held variant {@code variantIndex} of {@code basename}. */
    public static boolean hasSeen(UUID uuid, String basename, int variantIndex) {
        return seen(uuid).getOrDefault(basename, Set.of()).contains(variantIndex);
    }

    /** Add one variant. Returns whether it was new. */
    public static boolean add(UUID uuid, String basename, int variantIndex) {
        return addAll(uuid, Map.of(basename, Set.of(variantIndex)));
    }

    /**
     * Add {@code variants} (basename to indices) to {@code uuid}'s record, saving if any are new.
     *
     * @return whether any were new
     */
    public static boolean addAll(UUID uuid, Map<String, ? extends Set<Integer>> variants) {
        if (containsAll(seen(uuid), variants)) return false;
        Map<String, Set<Integer>> grown = CACHE.compute(uuid, (k, old) ->
            merged(old != null ? old : load(file(k)), variants));
        Util.ioPool().execute(() -> save(file(uuid), grown));
        return true;
    }

    /** Drop the cached copy (logout). Nothing is lost: every addition was already written. */
    public static void evict(UUID uuid) {
        CACHE.remove(uuid);
    }

    /** Whether {@code have} already holds every variant in {@code add}. */
    static boolean containsAll(Map<String, Set<Integer>> have, Map<String, ? extends Set<Integer>> add) {
        for (Map.Entry<String, ? extends Set<Integer>> e : add.entrySet()) {
            if (!have.getOrDefault(e.getKey(), Set.of()).containsAll(e.getValue())) return false;
        }
        return true;
    }

    /** A new immutable map holding everything in {@code a} and {@code b}. Neither input is changed. */
    static Map<String, Set<Integer>> merged(Map<String, ? extends Set<Integer>> a, Map<String, ? extends Set<Integer>> b) {
        Map<String, Set<Integer>> out = new TreeMap<>();
        for (Map<String, ? extends Set<Integer>> m : List.of(a, b)) {
            m.forEach((basename, variants) -> {
                if (!variants.isEmpty()) out.computeIfAbsent(basename, k -> new TreeSet<>()).addAll(variants);
            });
        }
        Map<String, Set<Integer>> frozen = new TreeMap<>();
        out.forEach((basename, variants) -> frozen.put(basename, Set.copyOf(variants)));
        return Map.copyOf(frozen);
    }

    private static Map<String, Set<Integer>> load(Path path) {
        Map<String, Set<Integer>> read = read(path);
        return read != null ? read : Map.of();
    }

    /** The variants in {@code path}: empty when there is no file, {@code null} when it can't be read. */
    static Map<String, Set<Integer>> read(Path path) {
        if (!Files.isRegularFile(path)) return Map.of();
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement element = JsonParser.parseReader(reader);
            var result = Data.CODEC.parse(JsonOps.INSTANCE, element);
            if (result.error().isPresent()) {
                LOGGER.error("[DungeonTrain] GlobalRandomBookProgress: parse failed for {}: {}",
                    path, result.error().get().message());
                return null;
            }
            return result.result()
                .map(d -> merged(Map.of(), toSets(d.seen())))
                .orElse(Map.of());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[DungeonTrain] GlobalRandomBookProgress: couldn't read {}: {}", path, e.toString());
            return null;
        }
    }

    private static Map<String, Set<Integer>> toSets(Map<String, List<Integer>> lists) {
        Map<String, Set<Integer>> out = new TreeMap<>();
        lists.forEach((basename, variants) -> out.put(basename, Set.copyOf(variants)));
        return out;
    }

    /** Move an unreadable file aside, so writing a fresh one never destroys what it held. */
    private static void keepUnreadable(Path path) {
        Path aside = path.resolveSibling(path.getFileName() + ".unreadable-" + System.currentTimeMillis());
        try {
            Files.move(path, aside);
            LOGGER.error("[DungeonTrain] GlobalRandomBookProgress: kept unreadable {} as {}", path, aside);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalRandomBookProgress: couldn't move unreadable {} aside: {}", path, e.toString());
        }
    }

    /**
     * Write {@code variants} atomically. Writes are serialised, and a write never shrinks the file: what is on
     * disk is merged in first, so an older snapshot landing late can never drop a variant already saved, and a
     * file that can't be read is moved aside ({@link #keepUnreadable}) rather than written over.
     */
    static synchronized void save(Path path, Map<String, Set<Integer>> variants) {
        Map<String, Set<Integer>> onDisk = read(path);
        if (onDisk == null) {
            keepUnreadable(path);
            onDisk = Map.of();
        }
        Map<String, List<Integer>> lists = new TreeMap<>();
        merged(onDisk, variants).forEach((basename, v) -> lists.put(basename, List.copyOf(new TreeSet<>(v))));
        var result = Data.CODEC.encodeStart(JsonOps.INSTANCE, new Data(lists));
        if (result.error().isPresent()) {
            LOGGER.error("[DungeonTrain] GlobalRandomBookProgress: encode failed: {}", result.error().get().message());
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
            LOGGER.error("[DungeonTrain] GlobalRandomBookProgress: couldn't write {}: {}", path, e.toString());
        }
    }
}
