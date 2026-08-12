package games.brennan.dungeontrain.editor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Two-tier store for {@code <kind.subdir>/<parent>.group.json} — the track-side twin of
 * {@link CarriageContentsGroupStore}, keyed by {@link TrackKind}.
 *
 * <ol>
 *   <li><b>User content</b> — resolved through {@link UserContentPaths#findFile} across the active
 *       package and every enabled imported package, so a group shipped inside a package still
 *       resolves. Writes land in the config dir beside the kind's {@code .nbt} files
 *       ({@link TrackKind#configSubdir()}), same as
 *       {@link games.brennan.dungeontrain.track.variant.TrackVariantStore}.</li>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/<kind.subdir>/<parent>.group.json}.</li>
 * </ol>
 *
 * <p><b>No separate discovery pass.</b> Candidate parents are just the kind's registered names
 * ({@link TrackVariantRegistry#namesFor}) — a bounded set — each loaded once and cached, including
 * the negative answer. That keeps the store's view of "which names are parents" incapable of
 * drifting from the registry's view of "which names exist", which is the failure that would put a
 * sub-variant in the top-level pool as a sibling of its own parent.</p>
 *
 * <p><b>Lock order is store → registry, never the reverse.</b> These methods call
 * {@code TrackVariantRegistry.namesFor} while holding this class's monitor, so nothing on the
 * registry side may call in here while holding <i>its</i> monitor. That is why the cache is cleared
 * from this class's own {@link ServerStoppedEvent} handler rather than from
 * {@code TrackVariantRegistry.clear()}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TrackVariantGroupStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Filename suffix for the group sidecar. Matches the contents sidecar's. */
    public static final String EXT = ".group.json";

    /** Per-kind, per-name cache. {@code Optional.empty()} = "not a parent" — a cached negative. */
    private static final Map<TrackKind, Map<String, Optional<TrackVariantGroup>>> CACHE =
        new EnumMap<>(TrackKind.class);

    /** Memoised per-kind child id set. null = needs recompute. */
    private static final Map<TrackKind, Set<String>> CHILD_IDS = new EnumMap<>(TrackKind.class);

    /** Memoised per-kind reverse index childId → parentId. null = needs recompute. */
    private static final Map<TrackKind, Map<String, String>> CHILD_TO_PARENT = new EnumMap<>(TrackKind.class);

    static {
        for (TrackKind k : TrackKind.values()) CACHE.put(k, new HashMap<>());
    }

    private TrackVariantGroupStore() {}

    // ---------- paths ----------

    /** Config-dir write target for {@code kind} — the same directory its {@code .nbt} files live in. */
    public static Path directory(TrackKind kind) {
        return FMLPaths.CONFIGDIR.get().resolve(kind.configSubdir());
    }

    public static Path fileFor(TrackKind kind, String parent) {
        return directory(kind).resolve(parent.toLowerCase(Locale.ROOT) + EXT);
    }

    public static String bundledResourceFor(TrackKind kind, String parent) {
        return kind.bundledResourcePrefix() + parent.toLowerCase(Locale.ROOT) + EXT;
    }

    public static Path sourceFileFor(TrackKind kind, String parent) {
        Path resources = resourcesRootOrNull();
        if (resources == null) {
            throw new IllegalStateException("Cannot resolve source directory — FMLPaths.GAMEDIR has no parent.");
        }
        return resources.resolve(kind.sourceRelativePath().substring("src/main/resources/".length()))
            .resolve(parent.toLowerCase(Locale.ROOT) + EXT);
    }

    public static boolean sourceTreeAvailable() {
        Path resources = resourcesRootOrNull();
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
    }

    // ---------- reads ----------

    /**
     * The group for {@code (kind, parent)}, loading on first access. {@link Optional#empty()} means
     * the name is an ordinary leaf variant. Filesystem failures (including a unit test with no Forge
     * paths) degrade to empty so the spawn-time pick path stays robust.
     */
    public static synchronized Optional<TrackVariantGroup> get(TrackKind kind, String parent) {
        if (kind == null || parent == null) return Optional.empty();
        String key = parent.toLowerCase(Locale.ROOT);
        Map<String, Optional<TrackVariantGroup>> byName = CACHE.get(kind);
        Optional<TrackVariantGroup> cached = byName.get(key);
        if (cached != null) return cached;
        Optional<TrackVariantGroup> loaded;
        try {
            loaded = loadFromConfig(kind, key);
            if (loaded.isEmpty()) loaded = loadFromResource(kind, key);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Track variant group load for {}:{} failed defensively: {}",
                kind.id(), key, t.toString());
            loaded = Optional.empty();
        }
        byName.put(key, loaded);
        if (loaded.isPresent()) invalidateIndexes(kind);
        return loaded;
    }

    /** True iff {@code (kind, parent)} has a group sidecar in either tier. */
    public static boolean exists(TrackKind kind, String parent) {
        return get(kind, parent).isPresent();
    }

    /**
     * Every name mentioned as a member of any group of {@code kind}. The registry excludes these
     * from the top-level pool, so a sub-variant is only ever reached through its parent.
     */
    public static synchronized Set<String> allChildIds(TrackKind kind) {
        Set<String> cached = CHILD_IDS.get(kind);
        if (cached != null) return cached;
        rebuildIndexes(kind);
        return CHILD_IDS.get(kind);
    }

    /** The parent {@code child} belongs to, or empty when it is a top-level name. */
    public static synchronized Optional<String> findParentOf(TrackKind kind, String child) {
        if (child == null) return Optional.empty();
        Map<String, String> cached = CHILD_TO_PARENT.get(kind);
        if (cached == null) {
            rebuildIndexes(kind);
            cached = CHILD_TO_PARENT.get(kind);
        }
        return Optional.ofNullable(cached.get(child.toLowerCase(Locale.ROOT)));
    }

    /** Every registered name of {@code kind} that is <b>not</b> a member of some group, in registry order. */
    public static List<String> topLevelNames(TrackKind kind) {
        List<String> all = TrackVariantRegistry.namesFor(kind);
        Set<String> children = allChildIds(kind);
        if (children.isEmpty()) return all;
        List<String> out = new java.util.ArrayList<>(all.size());
        for (String n : all) {
            if (!children.contains(n)) out.add(n);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Recompute both indexes for {@code kind} in one pass over its registered names. Caller holds
     * the monitor; {@link #get} is re-entrant on the same thread.
     */
    private static void rebuildIndexes(TrackKind kind) {
        Set<String> children = new LinkedHashSet<>();
        Map<String, String> reverse = new LinkedHashMap<>();
        for (String parent : TrackVariantRegistry.namesFor(kind)) {
            Optional<TrackVariantGroup> g = get(kind, parent);
            if (g.isEmpty()) continue;
            for (TrackVariantGroup.Member m : g.get().members()) {
                children.add(m.id());
                // First parent wins — a child listed in two groups is rejected by the editor
                // command, so this only guards a hand-edited pair of sidecars.
                reverse.putIfAbsent(m.id(), parent);
            }
        }
        CHILD_IDS.put(kind, Collections.unmodifiableSet(children));
        CHILD_TO_PARENT.put(kind, Collections.unmodifiableMap(reverse));
    }

    private static void invalidateIndexes(TrackKind kind) {
        CHILD_IDS.remove(kind);
        CHILD_TO_PARENT.remove(kind);
    }

    // ---------- writes ----------

    /** Persist {@code group}, mirroring to the source tree when running from a checkout. */
    public static synchronized void save(TrackKind kind, String parent, TrackVariantGroup group) throws IOException {
        if (kind == null) throw new IllegalArgumentException("kind must not be null");
        if (parent == null) throw new IllegalArgumentException("parent must not be null");
        if (group == null) throw new IllegalArgumentException("group must not be null");
        String key = parent.toLowerCase(Locale.ROOT);
        Path file = fileFor(kind, key);
        Files.createDirectories(file.getParent());
        String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(group.toJson());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(pretty);
        }
        CACHE.get(kind).put(key, Optional.of(group));
        invalidateIndexes(kind);
        LOGGER.info("[DungeonTrain] Saved track variant group {}:{} to {}", kind.id(), key, file);
        trySaveToSource(kind, key, group);
    }

    public static synchronized void saveToSource(TrackKind kind, String parent, TrackVariantGroup group) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        String key = parent.toLowerCase(Locale.ROOT);
        Path file = sourceFileFor(kind, key);
        Files.createDirectories(file.getParent());
        String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(group.toJson());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(pretty);
        }
        LOGGER.info("[DungeonTrain] Wrote bundled track variant group {}:{} to {} (devmode promote).",
            kind.id(), key, file);
    }

    /**
     * Delete the config-dir sidecar (and its source-tree mirror in dev). Returns true iff the
     * config-dir file existed. A bundled copy, if any, becomes visible again on the next read.
     */
    public static synchronized boolean delete(TrackKind kind, String parent) throws IOException {
        if (kind == null || parent == null) return false;
        String key = parent.toLowerCase(Locale.ROOT);
        boolean existed = Files.deleteIfExists(fileFor(kind, key));
        CACHE.get(kind).remove(key);
        invalidateIndexes(kind);
        tryDeleteFromSource(kind, key);
        if (existed) LOGGER.info("[DungeonTrain] Deleted track variant group {}:{}", kind.id(), key);
        return existed;
    }

    // ---------- lifecycle / testing ----------

    public static synchronized void clearCache() {
        for (TrackKind k : TrackKind.values()) CACHE.get(k).clear();
        CHILD_IDS.clear();
        CHILD_TO_PARENT.clear();
    }

    public static synchronized void invalidate(TrackKind kind, String parent) {
        if (kind == null || parent == null) return;
        CACHE.get(kind).remove(parent.toLowerCase(Locale.ROOT));
        invalidateIndexes(kind);
    }

    /**
     * Test-only seam — inject a group without touching the filesystem, so resolution and layout can
     * be unit-tested without a Forge bootstrap. Do NOT call from production code.
     */
    public static synchronized void injectForTesting(TrackKind kind, String parent, TrackVariantGroup group) {
        if (kind == null || parent == null || group == null) return;
        CACHE.get(kind).put(parent.toLowerCase(Locale.ROOT), Optional.of(group));
        invalidateIndexes(kind);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clearCache();
    }

    // ---------- loading ----------

    private static Optional<TrackVariantGroup> loadFromConfig(TrackKind kind, String key) {
        Path file = UserContentPaths.findFile(kind.subdir(), key + EXT);
        if (file == null) return Optional.empty();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Track variant group {} is not a JSON object — ignoring", file);
                return Optional.empty();
            }
            TrackVariantGroup g = TrackVariantGroup.fromJson(root.getAsJsonObject());
            LOGGER.info("[DungeonTrain] Loaded track variant group {}:{} from {} ({} members)",
                kind.id(), key, file, g.members().size());
            return Optional.of(g);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read track variant group {}:{} at {}: {}",
                kind.id(), key, file, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<TrackVariantGroup> loadFromResource(TrackKind kind, String key) {
        String resource = bundledResourceFor(kind, key);
        try (InputStream in = TrackVariantGroupStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Bundled track variant group {} is not a JSON object — ignoring", resource);
                return Optional.empty();
            }
            TrackVariantGroup g = TrackVariantGroup.fromJson(root.getAsJsonObject());
            LOGGER.info("[DungeonTrain] Loaded track variant group {}:{} from bundled {} ({} members)",
                kind.id(), key, resource, g.members().size());
            return Optional.of(g);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled track variant group {}: {}",
                resource, e.toString());
            return Optional.empty();
        }
    }

    private static void trySaveToSource(TrackKind kind, String key, TrackVariantGroup group) {
        if (!sourceTreeAvailable()) return;
        try {
            saveToSource(kind, key, group);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to write bundled track variant group {}:{} to source tree: {} (config write succeeded).",
                kind.id(), key, e.toString());
        }
    }

    private static void tryDeleteFromSource(TrackKind kind, String key) {
        if (!sourceTreeAvailable()) return;
        try {
            if (Files.deleteIfExists(sourceFileFor(kind, key))) {
                LOGGER.info("[DungeonTrain] Deleted bundled track variant group {}:{} (devmode promote).",
                    kind.id(), key);
            }
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Failed to delete bundled track variant group {}:{} from source tree: {} (config delete succeeded).",
                kind.id(), key, e.toString());
        }
    }

    private static Path resourcesRootOrNull() {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path projectRoot = gameDir == null ? null : gameDir.getParent();
        if (projectRoot == null) return null;
        return projectRoot.resolve("src/main/resources");
    }
}
