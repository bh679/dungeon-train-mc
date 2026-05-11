package games.brennan.dungeontrain.editor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageContentsGroup;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Three-tier store for {@code <parent-id>.group.json} sidecars — the per-id
 * group definition that turns a contents id into a parent that resolves to one
 * of its weighted children at spawn time.
 *
 * <p>Tier order mirrors {@link CarriageVariantContentsAllowStore}:
 * <ol>
 *   <li><b>Config dir</b> — {@code config/dungeontrain/contents/<id>.group.json}.
 *       Per-install override; editor commands write here.</li>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/contents/<id>.group.json}
 *       on the classpath. Shipped defaults.</li>
 *   <li><b>Absent</b> — neither tier has a file. Parent id is a normal leaf
 *       contents (its {@code .nbt}, if any, is stamped directly).</li>
 * </ol>
 *
 * <p>{@link #allChildIds()} walks every known group and unions their member
 * ids. The registry uses this to exclude children from the top-level pool
 * before the weighted pick — so a child only ever spawns through its parent,
 * never as a sibling of it.</p>
 *
 * <p>Dev-mode promotion: writes are mirrored to {@code src/main/resources/...}
 * when the source tree is writable, so an in-game group authoring session
 * ships in the next build. Mirrors the pattern used by
 * {@link CarriageContentsStore#saveToSource} (not the builtin-only logic of
 * {@link CarriageVariantContentsAllowStore}) — group parents are Custom by
 * design, but their group files are still legitimate shippable assets.</p>
 */
public final class CarriageContentsGroupStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SUBDIR = "dungeontrain/contents";
    private static final String EXT = ".group.json";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/contents/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/contents";

    // Per-id cache; Optional.empty() means "no group sidecar for this id" — short-circuits future loads.
    private static final Map<String, Optional<CarriageContentsGroup>> CACHE = new HashMap<>();

    // Ids known to have a group sidecar in either tier. Populated by preload()
    // from the registry scan, augmented by save(), pruned by delete().
    private static final TreeSet<String> KNOWN_PARENTS = new TreeSet<>();

    // Memoised union of all member ids across every known group.
    // null = needs recompute. Invalidated on any mutation or cache clear.
    private static volatile Set<String> ALL_CHILD_IDS_CACHE = null;

    // Memoised reverse lookup: childId → parentId for the first group that
    // claims the child. null = needs recompute. Same lifecycle as the
    // child-ids cache — invalidated together.
    private static volatile Map<String, String> CHILD_TO_PARENT_CACHE = null;

    private CarriageContentsGroupStore() {}

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR);
    }

    public static Path fileForId(String id) {
        return directory().resolve(id.toLowerCase(Locale.ROOT) + EXT);
    }

    public static Path sourceFileForId(String id) {
        return sourceDirectory().resolve(id.toLowerCase(Locale.ROOT) + EXT);
    }

    public static boolean sourceTreeAvailable() {
        Path resources = resourcesRootOrNull();
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
    }

    public static synchronized void clearCache() {
        CACHE.clear();
        KNOWN_PARENTS.clear();
        ALL_CHILD_IDS_CACHE = null;
        CHILD_TO_PARENT_CACHE = null;
    }

    /**
     * Test-only seam: inject {@code group} directly into the cache so unit
     * tests can drive {@link games.brennan.dungeontrain.train.CarriageContentsRegistry}
     * resolution without a Forge bootstrap or filesystem writes. Marks
     * {@code parentId} as a known parent. Do NOT call from production code.
     */
    public static synchronized void injectForTesting(String parentId, CarriageContentsGroup group) {
        if (parentId == null || group == null) return;
        String key = parentId.toLowerCase(Locale.ROOT);
        CACHE.put(key, Optional.of(group));
        KNOWN_PARENTS.add(key);
        ALL_CHILD_IDS_CACHE = null;
        CHILD_TO_PARENT_CACHE = null;
    }

    public static synchronized void invalidate(String parentId) {
        if (parentId == null) return;
        String key = parentId.toLowerCase(Locale.ROOT);
        CACHE.remove(key);
        ALL_CHILD_IDS_CACHE = null;
        CHILD_TO_PARENT_CACHE = null;
    }

    /**
     * Register {@code parentId} as a known group parent without forcing a load.
     * Called by the registry's scan when it discovers a {@code .group.json}
     * file. {@link #allChildIds()} walks the resulting parent set on demand.
     */
    public static synchronized void preload(String parentId) {
        if (parentId == null) return;
        String key = parentId.toLowerCase(Locale.ROOT);
        if (KNOWN_PARENTS.add(key)) {
            ALL_CHILD_IDS_CACHE = null;
        CHILD_TO_PARENT_CACHE = null;
        }
    }

    /**
     * Returns the group for {@code parentId}, loading on first access.
     * {@link Optional#empty()} means "no group file in either tier" — caller
     * treats the id as a normal leaf contents. Filesystem failures (e.g.
     * Forge paths unavailable in unit tests) are caught and degrade to
     * {@code Optional.empty()} so the registry's pick path stays robust.
     */
    public static synchronized Optional<CarriageContentsGroup> get(String parentId) {
        if (parentId == null) return Optional.empty();
        String key = parentId.toLowerCase(Locale.ROOT);
        Optional<CarriageContentsGroup> cached = CACHE.get(key);
        if (cached != null) return cached;
        Optional<CarriageContentsGroup> loaded;
        try {
            loaded = loadFromConfig(key);
            if (loaded.isEmpty()) loaded = loadFromResource(key);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Contents group load for '{}' failed defensively: {}", key, t.toString());
            loaded = Optional.empty();
        }
        CACHE.put(key, loaded);
        if (loaded.isPresent()) {
            KNOWN_PARENTS.add(key);
            ALL_CHILD_IDS_CACHE = null;
        CHILD_TO_PARENT_CACHE = null;
        }
        return loaded;
    }

    /** True iff {@code parentId} has a group sidecar in either tier. */
    public static boolean exists(String parentId) {
        return get(parentId).isPresent();
    }

    /** Snapshot of every known parent id (lowercased, sorted). */
    public static synchronized Set<String> knownParentIds() {
        return Collections.unmodifiableSet(new TreeSet<>(KNOWN_PARENTS));
    }

    /**
     * Flat union of every id mentioned as a member across every known group.
     * Memoised; recomputed lazily after any mutation. Snapshot — safe to
     * iterate without locking.
     */
    public static synchronized Set<String> allChildIds() {
        Set<String> cached = ALL_CHILD_IDS_CACHE;
        if (cached != null) return cached;
        rebuildIndexes();
        return ALL_CHILD_IDS_CACHE;
    }

    /**
     * Reverse lookup — returns the id of the parent group {@code childId}
     * belongs to, or empty if {@code childId} is not a group member. Used by
     * the sub-variant editor menu to identify the sibling list when the
     * player is editing a sub-variant.
     */
    public static synchronized Optional<String> findParentOf(String childId) {
        if (childId == null) return Optional.empty();
        String key = childId.toLowerCase(Locale.ROOT);
        Map<String, String> cached = CHILD_TO_PARENT_CACHE;
        if (cached == null) {
            rebuildIndexes();
            cached = CHILD_TO_PARENT_CACHE;
        }
        return Optional.ofNullable(cached.get(key));
    }

    /**
     * Recompute both the {@link #ALL_CHILD_IDS_CACHE} and
     * {@link #CHILD_TO_PARENT_CACHE} in a single pass over the known groups.
     * Caller must hold the class monitor.
     */
    private static void rebuildIndexes() {
        Set<String> children = new LinkedHashSet<>();
        Map<String, String> reverse = new LinkedHashMap<>();
        for (String parent : KNOWN_PARENTS) {
            Optional<CarriageContentsGroup> g = get(parent);
            if (g.isEmpty()) continue;
            for (CarriageContentsGroup.Member m : g.get().members()) {
                children.add(m.id());
                // First parent wins — guards against a child mistakenly listed
                // in two groups (which the editor command rejects anyway).
                reverse.putIfAbsent(m.id(), parent);
            }
        }
        ALL_CHILD_IDS_CACHE = Collections.unmodifiableSet(children);
        CHILD_TO_PARENT_CACHE = Collections.unmodifiableMap(reverse);
    }

    /**
     * Persist {@code group} for {@code parentId}. Writes the config-dir file
     * and best-effort mirrors to the source tree when running from a checkout.
     */
    public static synchronized void save(String parentId, CarriageContentsGroup group) throws IOException {
        if (parentId == null) throw new IllegalArgumentException("parentId must not be null");
        if (group == null) throw new IllegalArgumentException("group must not be null");
        String key = parentId.toLowerCase(Locale.ROOT);
        Files.createDirectories(directory());
        Path file = fileForId(key);
        String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(group.toJson());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(pretty);
        }
        CACHE.put(key, Optional.of(group));
        KNOWN_PARENTS.add(key);
        ALL_CHILD_IDS_CACHE = null;
        CHILD_TO_PARENT_CACHE = null;
        LOGGER.info("[DungeonTrain] Saved contents group {} to {}", key, file);
        trySaveToSource(key, group);
    }

    public static synchronized void saveToSource(String parentId, CarriageContentsGroup group) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        String key = parentId.toLowerCase(Locale.ROOT);
        Path file = sourceFileForId(key);
        Files.createDirectories(file.getParent());
        String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(group.toJson());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(pretty);
        }
        LOGGER.info("[DungeonTrain] Wrote bundled contents group {} to {} (devmode promote).", key, file);
    }

    /**
     * Delete the config-dir sidecar (and the source-tree mirror when in dev).
     * Returns true iff the config-dir file existed. Cache is updated so a
     * subsequent {@link #get} returns the bundled-resource copy if any.
     */
    public static synchronized boolean delete(String parentId) throws IOException {
        if (parentId == null) return false;
        String key = parentId.toLowerCase(Locale.ROOT);
        Path file = fileForId(key);
        boolean existed = Files.deleteIfExists(file);
        // Invalidate cache so the next get() consults the bundled tier — if
        // that tier also has no copy, KNOWN_PARENTS will be pruned on next
        // get() returning empty (we proactively remove here in that case below).
        CACHE.remove(key);
        ALL_CHILD_IDS_CACHE = null;
        CHILD_TO_PARENT_CACHE = null;
        tryDeleteFromSource(key);
        // If the bundled tier also has nothing, the id is no longer a group parent.
        if (loadFromResource(key).isEmpty()) {
            KNOWN_PARENTS.remove(key);
            CACHE.put(key, Optional.empty());
        }
        if (existed) LOGGER.info("[DungeonTrain] Deleted contents group {} ({})", key, file);
        return existed;
    }

    private static Optional<CarriageContentsGroup> loadFromConfig(String key) {
        Path file = fileForId(key);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Contents group {} is not a JSON object — ignoring", file);
                return Optional.empty();
            }
            CarriageContentsGroup g = CarriageContentsGroup.fromJson(root.getAsJsonObject());
            LOGGER.info("[DungeonTrain] Loaded contents group {} from config {} ({} members)",
                key, file, g.members().size());
            return Optional.of(g);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read contents group {} at {}: {}",
                key, file, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<CarriageContentsGroup> loadFromResource(String key) {
        String resource = RESOURCE_PREFIX + key + EXT;
        try (InputStream in = CarriageContentsGroupStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Bundled contents group {} is not a JSON object — ignoring", resource);
                return Optional.empty();
            }
            CarriageContentsGroup g = CarriageContentsGroup.fromJson(root.getAsJsonObject());
            LOGGER.info("[DungeonTrain] Loaded contents group {} from bundled {} ({} members)",
                key, resource, g.members().size());
            return Optional.of(g);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled contents group {}: {}",
                resource, e.toString());
            return Optional.empty();
        }
    }

    private static void trySaveToSource(String key, CarriageContentsGroup group) {
        if (!sourceTreeAvailable()) return;
        try {
            saveToSource(key, group);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to write bundled contents group {} to source tree: {} (config write succeeded).",
                key, e.toString());
        }
    }

    private static void tryDeleteFromSource(String key) {
        if (!sourceTreeAvailable()) return;
        try {
            Path file = sourceFileForId(key);
            if (Files.deleteIfExists(file)) {
                LOGGER.info("[DungeonTrain] Deleted bundled contents group {} (devmode promote).", file);
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to delete bundled contents group {} from source tree: {} (config delete succeeded).",
                key, e.toString());
        }
    }

    private static Path sourceDirectory() {
        Path dir = sourceDirectoryOrNull();
        if (dir == null) {
            throw new IllegalStateException(
                "Cannot resolve source directory — FMLPaths.GAMEDIR has no parent."
            );
        }
        return dir;
    }

    private static Path sourceDirectoryOrNull() {
        Path projectRoot = projectRootOrNull();
        if (projectRoot == null) return null;
        return projectRoot.resolve(SOURCE_REL_PATH);
    }

    private static Path resourcesRootOrNull() {
        Path projectRoot = projectRootOrNull();
        if (projectRoot == null) return null;
        return projectRoot.resolve("src/main/resources");
    }

    private static Path projectRootOrNull() {
        Path gameDir = FMLPaths.GAMEDIR.get();
        return gameDir.getParent();
    }
}
