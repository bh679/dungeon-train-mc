package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.NarrativeProgress;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Installation-wide (cross-world, shared) record of which narrative-story
 * letters and variants have ever been read on this instance. Lives at
 * {@code <minecraft>/config/dungeontrain-narrative/global.json}, OUTSIDE any
 * individual world save.
 *
 * <p>Sibling of {@link GlobalAchievementStore} / {@link GlobalPlayerStats}, but
 * deliberately <b>not</b> keyed by player UUID — it is one shared record for
 * the whole instance. Story lectern <em>selection</em> stays per-world (in
 * {@code NarrativeProgressData}); this store exists only so the "read every
 * story" advancements can accumulate across worlds instead of resetting each
 * new world.</p>
 *
 * <p>Shape mirrors the two world-scoped maps it shadows so the advancement
 * checks can read it via {@link #progressFor(String)} /
 * {@link #storyVariantsSnapshot()} exactly as they read
 * {@code NarrativeProgressData}:</p>
 * <ul>
 *   <li>{@code read_letters}: {@code basename -> [letterIndex...]} (1-based)</li>
 *   <li>{@code variants_seen}: {@code "basename#letter" -> [variantIndex...]} (0-based)</li>
 * </ul>
 *
 * <p>Concurrency: all methods are {@code synchronized} on the class. The
 * in-memory cache is loaded lazily on first access and written atomically on
 * every mutation (crash-safe temp-then-rename).</p>
 */
public final class GlobalNarrativeProgress {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIR_NAME = "dungeontrain-narrative";
    private static final String FILE_NAME = "global.json";

    /** On-disk schema: two string -> int-list maps. */
    public record Data(Map<String, List<Integer>> readLetters,
                       Map<String, List<Integer>> variantsSeen) {
        public static final Codec<Data> CODEC = RecordCodecBuilder.create(in -> in.group(
            Codec.unboundedMap(Codec.STRING, Codec.INT.listOf())
                .optionalFieldOf("read_letters", Map.of()).forGetter(Data::readLetters),
            Codec.unboundedMap(Codec.STRING, Codec.INT.listOf())
                .optionalFieldOf("variants_seen", Map.of()).forGetter(Data::variantsSeen)
        ).apply(in, Data::new));

        public static final Data EMPTY = new Data(Map.of(), Map.of());
    }

    // In-memory cache. Null until first load. TreeSet keeps indices sorted and
    // de-duplicated, matching NarrativeProgress's own backing set.
    private static Map<String, TreeSet<Integer>> readLetters;
    private static Map<String, TreeSet<Integer>> variantsSeen;

    private GlobalNarrativeProgress() {}

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(DIR_NAME).resolve(FILE_NAME);
    }

    /**
     * Mark {@code letterIndex} (1-based) of {@code basename} read for the
     * instance. Returns {@code true} when state changed. Mirrors
     * {@link NarrativeProgress#markRead(int)} semantics (floors {@code < 1}).
     */
    public static synchronized boolean markRead(String basename, int letterIndex) {
        if (letterIndex < 1) return false;
        ensureLoaded();
        boolean changed = readLetters.computeIfAbsent(basename, k -> new TreeSet<>()).add(letterIndex);
        if (changed) save();
        return changed;
    }

    /**
     * Mark {@code (basename, letterIndex, variantIndex)} (variant 0-based) seen
     * for the instance. Returns {@code true} when state changed.
     */
    public static synchronized boolean markVariantSeen(String basename, int letterIndex, int variantIndex) {
        if (variantIndex < 0) return false;
        ensureLoaded();
        String key = basename + "#" + letterIndex;
        boolean changed = variantsSeen.computeIfAbsent(key, k -> new TreeSet<>()).add(variantIndex);
        if (changed) save();
        return changed;
    }

    /**
     * Bulk-merge a per-world snapshot into the global record in a single write.
     * {@code readLettersIn} is {@code basename -> letterIndices} (1-based);
     * {@code variantsSeenIn} is {@code "basename#letter" -> variantIndices}
     * (0-based) — the exact shapes returned by
     * {@code NarrativeProgressData.snapshotStories()} /
     * {@code storyVariantsSnapshot()}. Idempotent; saves once, only if anything
     * actually changed. Used by the login absorption path.
     */
    public static synchronized void absorbAll(Map<String, ? extends Set<Integer>> readLettersIn,
                                              Map<String, ? extends Set<Integer>> variantsSeenIn) {
        ensureLoaded();
        boolean changed = false;
        for (var e : readLettersIn.entrySet()) {
            TreeSet<Integer> set = readLetters.computeIfAbsent(e.getKey(), k -> new TreeSet<>());
            for (int l : e.getValue()) {
                if (l >= 1 && set.add(l)) changed = true;
            }
        }
        for (var e : variantsSeenIn.entrySet()) {
            TreeSet<Integer> set = variantsSeen.computeIfAbsent(e.getKey(), k -> new TreeSet<>());
            for (int v : e.getValue()) {
                if (v >= 0 && set.add(v)) changed = true;
            }
        }
        if (changed) save();
    }

    /**
     * Read-only global progress for {@code basename}; never null. Shaped like
     * {@link games.brennan.dungeontrain.narrative.NarrativeProgressData#progressFor(String)}
     * so the advancement checks can consume it unchanged.
     */
    public static synchronized NarrativeProgress progressFor(String basename) {
        ensureLoaded();
        TreeSet<Integer> s = readLetters.get(basename);
        return s != null ? new NarrativeProgress(s) : new NarrativeProgress();
    }

    /**
     * Snapshot of every {@code (story, letter)} pair (keyed {@code basename#letter})
     * the instance has seen at least one variant of. Shaped like
     * {@link games.brennan.dungeontrain.narrative.NarrativeProgressData#storyVariantsSnapshot()}.
     */
    public static synchronized Map<String, NarrativeProgress> storyVariantsSnapshot() {
        ensureLoaded();
        Map<String, NarrativeProgress> out = new HashMap<>(variantsSeen.size());
        for (var e : variantsSeen.entrySet()) {
            out.put(e.getKey(), new NarrativeProgress(e.getValue()));
        }
        return out;
    }

    // ---------------- internals ----------------

    private static void ensureLoaded() {
        if (readLetters != null) return;
        readLetters = new HashMap<>();
        variantsSeen = new HashMap<>();
        Path path = file();
        if (!Files.isRegularFile(path)) return;
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement element = JsonParser.parseReader(reader);
            var result = Data.CODEC.parse(JsonOps.INSTANCE, element);
            if (result.error().isPresent()) {
                LOGGER.warn("[DungeonTrain] GlobalNarrativeProgress: failed to parse {}: {}",
                    path, result.error().get().message());
                return;
            }
            Data data = result.result().orElse(Data.EMPTY);
            data.readLetters().forEach((k, v) -> readLetters.put(k, new TreeSet<>(v)));
            data.variantsSeen().forEach((k, v) -> variantsSeen.put(k, new TreeSet<>(v)));
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] GlobalNarrativeProgress: I/O error reading {}: {}",
                path, e.getMessage());
        }
    }

    private static void save() {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalNarrativeProgress: failed to create dir {}: {}",
                path.getParent(), e.getMessage());
            return;
        }
        Data data = new Data(toListMap(readLetters), toListMap(variantsSeen));
        var result = Data.CODEC.encodeStart(JsonOps.INSTANCE, data);
        if (result.error().isPresent()) {
            LOGGER.error("[DungeonTrain] GlobalNarrativeProgress: encode failed: {}",
                result.error().get().message());
            return;
        }
        JsonElement element = result.result().orElseThrow();
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp)) {
            writer.write(element.toString());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalNarrativeProgress: write tmp {} failed: {}",
                tmp, e.getMessage());
            return;
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                LOGGER.error("[DungeonTrain] GlobalNarrativeProgress: rename {} -> {} failed: {}",
                    tmp, path, e2.getMessage());
            }
        }
    }

    private static Map<String, List<Integer>> toListMap(Map<String, TreeSet<Integer>> in) {
        Map<String, List<Integer>> out = new HashMap<>(in.size());
        for (var e : in.entrySet()) out.put(e.getKey(), new ArrayList<>(e.getValue()));
        return out;
    }

    /**
     * Drop the in-memory cache on server stop so the next session re-reads from
     * disk. Writes are write-through (every mutation is flushed immediately), so
     * nothing is lost; this just lets an externally-edited or deleted
     * {@code global.json} (e.g. an operator resetting progress between a
     * stop/start within the same JVM) take effect on the next world load.
     */
        public static synchronized void onServerStopped(net.minecraft.server.MinecraftServer server) {
        readLetters = null;
        variantsSeen = null;
    }
}
