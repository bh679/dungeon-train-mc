package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
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
 *       ({@link TrackKind#subdir()}), same as
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
 *
 * <p>Policies: {@link SourcePolicy#DEV_MODE} (was "whenever writable", which mirrored per-install
 * sidecars into {@code src/}) and {@link DeleteCachePolicy#REMOVE}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TrackVariantGroupStore {

    /** Filename suffix for the group sidecar. Matches the contents sidecar's. */
    public static final String EXT = ".group.json";

    /** A group's address: its kind and lower-cased parent name. */
    private record Key(TrackKind kind, String name) {
        static Key of(TrackKind kind, String parent) {
            return new Key(kind, parent.toLowerCase(Locale.ROOT));
        }

        String id() {
            return kind.id() + ":" + name;
        }
    }

    private static final ThreeTierStore<Key, TrackVariantGroup> STORE =
        StoreDescriptor.<Key, TrackVariantGroup>named("TrackVariantGroupStore")
            .id(Key::id)
            .subdir(k -> k.kind().subdir())
            .fileName(k -> k.name() + EXT)
            .bundledResource(k -> k.kind().bundledResourcePrefix() + k.name() + EXT)
            .sourceRelDir(k -> k.kind().sourceRelativePath())
            .codec(StoreCodecs.json(TrackVariantGroup::fromJson, TrackVariantGroup::toJson))
            .logNoun("track variant group")
            .sourcePolicy(SourcePolicy.DEV_MODE)
            .deleteCachePolicy(DeleteCachePolicy.REMOVE)
            .build();

    static {
        // Lock order is this class -> STORE (every read takes the class monitor first), so the
        // registry must clear through the synchronized facade, not the store's own entry.
        StoreRegistry.register("TrackVariantGroupStore", TrackVariantGroupStore::clearCache);
    }

    /** Memoised per-kind child id set. Absent = needs recompute. */
    private static final Map<TrackKind, Set<String>> CHILD_IDS = new EnumMap<>(TrackKind.class);

    /** Memoised per-kind reverse index childId → parentId. Absent = needs recompute. */
    private static final Map<TrackKind, Map<String, String>> CHILD_TO_PARENT = new EnumMap<>(TrackKind.class);

    private TrackVariantGroupStore() {}

    // ---------- paths ----------

    /**
     * Write target for {@code kind} — the same directory its {@code .nbt} files
     * live in, i.e. the active package's. See
     * {@link games.brennan.dungeontrain.track.variant.TrackVariantStore#directory}.
     */
    public static Path directory(TrackKind kind) {
        return UserContentPaths.activeSubDir(kind.subdir());
    }

    public static Path fileFor(TrackKind kind, String parent) {
        return STORE.fileFor(Key.of(kind, parent));
    }

    public static String bundledResourceFor(TrackKind kind, String parent) {
        return STORE.bundledResourceFor(Key.of(kind, parent));
    }

    public static Path sourceFileFor(TrackKind kind, String parent) {
        return STORE.sourceFileFor(Key.of(kind, parent));
    }

    public static boolean sourceTreeAvailable() {
        return STORE.sourceTreeAvailable();
    }

    // ---------- reads ----------

    /**
     * The group for {@code (kind, parent)}, loading on first access. {@link Optional#empty()} means
     * the name is an ordinary leaf variant. Filesystem failures (including a unit test with no Forge
     * paths) degrade to empty so the spawn-time pick path stays robust.
     */
    public static synchronized Optional<TrackVariantGroup> get(TrackKind kind, String parent) {
        if (kind == null || parent == null) return Optional.empty();
        Key key = Key.of(kind, parent);
        boolean wasCached = STORE.isCached(key);
        Optional<TrackVariantGroup> loaded = STORE.get(key);
        if (loaded.isPresent() && !wasCached) invalidateIndexes(kind);
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

    /** Persist {@code group}, mirroring to the source tree in dev mode. */
    public static synchronized void save(TrackKind kind, String parent, TrackVariantGroup group) throws IOException {
        if (kind == null) throw new IllegalArgumentException("kind must not be null");
        if (parent == null) throw new IllegalArgumentException("parent must not be null");
        if (group == null) throw new IllegalArgumentException("group must not be null");
        STORE.save(Key.of(kind, parent), group);
        invalidateIndexes(kind);
    }

    public static void saveToSource(TrackKind kind, String parent, TrackVariantGroup group) throws IOException {
        STORE.saveToSource(Key.of(kind, parent), group);
    }

    /**
     * Delete the config-dir sidecar (and its source-tree mirror in dev mode). Returns true iff the
     * config-dir file existed. A bundled copy, if any, becomes visible again on the next read.
     */
    public static synchronized boolean delete(TrackKind kind, String parent) throws IOException {
        if (kind == null || parent == null) return false;
        boolean existed = STORE.delete(Key.of(kind, parent));
        invalidateIndexes(kind);
        return existed;
    }

    // ---------- lifecycle / testing ----------

    public static synchronized void clearCache() {
        STORE.clearCache();
        CHILD_IDS.clear();
        CHILD_TO_PARENT.clear();
    }

    public static synchronized void invalidate(TrackKind kind, String parent) {
        if (kind == null || parent == null) return;
        STORE.invalidate(Key.of(kind, parent));
        invalidateIndexes(kind);
    }

    /**
     * Test-only seam — inject a group without touching the filesystem, so resolution and layout can
     * be unit-tested without a Forge bootstrap. Do NOT call from production code.
     */
    public static synchronized void injectForTesting(TrackKind kind, String parent, TrackVariantGroup group) {
        if (kind == null || parent == null || group == null) return;
        STORE.injectForTesting(Key.of(kind, parent), group);
        invalidateIndexes(kind);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clearCache();
    }
}
