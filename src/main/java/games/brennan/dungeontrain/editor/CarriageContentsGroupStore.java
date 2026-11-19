package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageContentsGroup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
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
 * <ol>
 *   <li><b>Active package's working dir</b> —
 *       {@code <active-package>/contents/<id>.group.json}. Editor commands
 *       write here; reads fall back across every enabled package's
 *       {@code contents/} dir via {@link UserContentPaths#findFile} so a
 *       group shipped in an imported package still resolves when that
 *       package is enabled. Mirrors the routing
 *       {@link CarriageContentsStore} uses for {@code .nbt} files so the
 *       sidecar always travels alongside its parent's content NBT.</li>
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
 * <p>Policies: {@link SourcePolicy#DEV_MODE} — a group parent is Custom by design and has no
 * {@code .nbt} of its own to mark it as shipping, so editor dev mode is the author's "this ships"
 * signal (as it is for stages and loot prefabs); it used to mirror whenever the tree was writable,
 * which put per-install sidecars into {@code src/}. {@link DeleteCachePolicy#REMOVE}: a bundled
 * copy, if any, is visible again on the next read.</p>
 */
public final class CarriageContentsGroupStore {

    // Matches CarriageContentsStore.SUBDIR so group sidecars share the
    // package-routed contents/ directory with their parent's .nbt file —
    // moving a package moves them together.
    private static final String SUBDIR = CarriageContentsStore.SUBDIR;
    private static final String EXT = ".group.json";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/contents/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/contents";

    private static final ThreeTierStore<String, CarriageContentsGroup> STORE =
        StoreDescriptor.<String, CarriageContentsGroup>named("CarriageContentsGroupStore")
            .id(key -> key)
            .subdir(SUBDIR)
            .fileName(key -> key + EXT)
            .bundledUnder(RESOURCE_PREFIX)
            .sourceRelDir(SOURCE_REL_PATH)
            .codec(StoreCodecs.json(CarriageContentsGroup::fromJson, CarriageContentsGroup::toJson))
            .logNoun("contents group")
            .sourcePolicy(SourcePolicy.DEV_MODE)
            .deleteCachePolicy(DeleteCachePolicy.REMOVE)
            .build();

    static {
        // Lock order is this class -> STORE (every read takes the class monitor first), so the
        // registry must clear through the synchronized facade, not the store's own entry.
        StoreRegistry.register("CarriageContentsGroupStore", CarriageContentsGroupStore::clearCache);
    }

    // Ids known to have a group sidecar in either tier. Populated by preload()
    // from the registry scan, augmented by save()/get(), pruned by delete().
    private static final TreeSet<String> KNOWN_PARENTS = new TreeSet<>();

    // Memoised union of all member ids across every known group.
    // null = needs recompute. Invalidated on any mutation or cache clear.
    private static volatile Set<String> ALL_CHILD_IDS_CACHE = null;

    // Memoised reverse lookup: childId → parentId for the first group that
    // claims the child. Same lifecycle as the child-ids cache.
    private static volatile Map<String, String> CHILD_TO_PARENT_CACHE = null;

    private CarriageContentsGroupStore() {}

    private static String key(String parentId) {
        return parentId.toLowerCase(Locale.ROOT);
    }

    /**
     * Active-package write target — {@code <active-package>/contents/}.
     * Routes through {@link UserContentPaths#dir(String)} so a package
     * switch immediately redirects subsequent writes.
     */
    public static Path directory() {
        return UserContentPaths.dir(SUBDIR);
    }

    /**
     * Write path for {@code id} — the file lands in the active package's
     * {@code contents/}. Reads use {@link UserContentPaths#findFile} so an
     * imported package's group sidecar is still resolvable.
     */
    public static Path fileForId(String id) {
        return STORE.fileFor(key(id));
    }

    public static Path sourceFileForId(String id) {
        return STORE.sourceFileFor(key(id));
    }

    public static boolean sourceTreeAvailable() {
        return STORE.sourceTreeAvailable();
    }

    public static synchronized void clearCache() {
        STORE.clearCache();
        KNOWN_PARENTS.clear();
        invalidateIndexes();
    }

    private static void invalidateIndexes() {
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
        String key = key(parentId);
        STORE.injectForTesting(key, group);
        KNOWN_PARENTS.add(key);
        invalidateIndexes();
    }

    public static synchronized void invalidate(String parentId) {
        if (parentId == null) return;
        STORE.invalidate(key(parentId));
        invalidateIndexes();
    }

    /**
     * Register {@code parentId} as a known group parent without forcing a load.
     * Called by the registry's scan when it discovers a {@code .group.json}
     * file. {@link #allChildIds()} walks the resulting parent set on demand.
     */
    public static synchronized void preload(String parentId) {
        if (parentId == null) return;
        if (KNOWN_PARENTS.add(key(parentId))) invalidateIndexes();
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
        String key = key(parentId);
        boolean wasCached = STORE.isCached(key);
        Optional<CarriageContentsGroup> loaded = STORE.get(key);
        if (loaded.isPresent() && !wasCached && KNOWN_PARENTS.add(key)) invalidateIndexes();
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
        Map<String, String> cached = CHILD_TO_PARENT_CACHE;
        if (cached == null) {
            rebuildIndexes();
            cached = CHILD_TO_PARENT_CACHE;
        }
        return Optional.ofNullable(cached.get(key(childId)));
    }

    /**
     * Recompute both the {@link #ALL_CHILD_IDS_CACHE} and
     * {@link #CHILD_TO_PARENT_CACHE} in a single pass over the known groups.
     * Caller must hold the class monitor.
     */
    private static void rebuildIndexes() {
        Set<String> children = new LinkedHashSet<>();
        Map<String, String> reverse = new LinkedHashMap<>();
        for (String parent : new TreeSet<>(KNOWN_PARENTS)) {
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
     * and, in dev mode, mirrors it to the source tree.
     */
    public static synchronized void save(String parentId, CarriageContentsGroup group) throws IOException {
        if (parentId == null) throw new IllegalArgumentException("parentId must not be null");
        if (group == null) throw new IllegalArgumentException("group must not be null");
        String key = key(parentId);
        STORE.save(key, group);
        KNOWN_PARENTS.add(key);
        invalidateIndexes();
    }

    public static void saveToSource(String parentId, CarriageContentsGroup group) throws IOException {
        STORE.saveToSource(key(parentId), group);
    }

    /**
     * Delete the config-dir sidecar (and the source-tree mirror in dev mode).
     * Returns true iff the config-dir file existed. The cache entry is dropped
     * so a subsequent {@link #get} returns the bundled-resource copy if any;
     * when there is none, the id is no longer a group parent.
     */
    public static synchronized boolean delete(String parentId) throws IOException {
        if (parentId == null) return false;
        String key = key(parentId);
        boolean existed = STORE.delete(key);
        invalidateIndexes();
        if (!STORE.bundled(key)) KNOWN_PARENTS.remove(key);
        return existed;
    }
}
