package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageVariantContentsAllowStore;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateRegistry;
import games.brennan.dungeontrain.train.CarriageContents.ContentsType;
import games.brennan.dungeontrain.util.BundledNbtScanner;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ordered list of all carriage-contents variants available for spawning and
 * editing. The {@link ContentsType} built-ins come first in declared enum order;
 * custom contents follow in alphabetical order by id.
 *
 * <p>Custom contents are discovered from two tiers at server start — identical
 * structure to {@link CarriageVariantRegistry}:
 * <ol>
 *   <li><b>Bundled manifest</b> — {@code /data/dungeontrain/contents/customs.json}
 *       on the classpath lists the ids that ship with the mod jar.</li>
 *   <li><b>Config directory</b> — {@link CarriageContentsStore#directory()}
 *       scanned for {@code .nbt} files whose basenames are not built-in ids.</li>
 * </ol>
 *
 * <p>Only interior-sized {@code .nbt} files belong in that directory — shell
 * templates live under {@code config/dungeontrain/user/templates/} and pillar
 * templates under {@code config/dungeontrain/user/pillars/}. Because all three
 * stores have distinct subdirs, file-name collisions cannot leak contents into
 * the shell registry or vice versa.
 *
 * <p>{@link #pick(long, int)} mirrors the deterministic position-free variant
 * of {@link games.brennan.dungeontrain.editor.CarriageVariantBlocks#pickIndex}
 * so the same world seed + carriage index always resolves to the same
 * contents across reloads.
 */
public final class CarriageContentsRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<CarriageContents> BUILTINS;
    static {
        List<CarriageContents> list = new ArrayList<>();
        for (ContentsType t : ContentsType.values()) list.add(CarriageContents.of(t));
        BUILTINS = List.copyOf(list);
    }

    /** Sorted custom contents names. Mutations go through register/unregister/reload. */
    private static final TreeSet<String> CUSTOMS = new TreeSet<>();

    /** Classpath prefix for shipped contents NBTs (and the legacy customs manifest). */
    static final String BUNDLED_RESOURCE_PREFIX = "/data/dungeontrain/contents/";

    private CarriageContentsRegistry() {}

    /**
     * Every contents variant, built-ins first (enum order), then customs
     * alphabetical. Snapshot — safe to iterate without locking even if another
     * thread mutates the registry.
     */
    public static synchronized List<CarriageContents> allContents() {
        List<CarriageContents> all = new ArrayList<>(BUILTINS.size() + CUSTOMS.size());
        all.addAll(BUILTINS);
        for (String name : CUSTOMS) all.add(new CarriageContents.Custom(name));
        return all;
    }

    public static synchronized List<CarriageContents> builtins() {
        return BUILTINS;
    }

    /**
     * Look up a contents variant by id. Returns empty if {@code id} is neither
     * a built-in nor a registered custom.
     */
    public static synchronized Optional<CarriageContents> find(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        for (ContentsType t : ContentsType.values()) {
            if (t.name().toLowerCase(Locale.ROOT).equals(key)) {
                return Optional.of(CarriageContents.of(t));
            }
        }
        if (CUSTOMS.contains(key)) return Optional.of(new CarriageContents.Custom(key));
        return Optional.empty();
    }

    /**
     * Register a new custom contents. Returns false if a contents with the
     * same id already exists (built-in or custom). Caller is responsible for
     * writing the backing .nbt file before calling this.
     */
    public static synchronized boolean register(CarriageContents.Custom contents) {
        if (CarriageContents.isReservedBuiltinName(contents.name())) return false;
        return CUSTOMS.add(contents.name());
    }

    /**
     * Remove a custom contents from the registry. Built-ins can't be
     * unregistered. Caller is responsible for deleting the backing .nbt file.
     */
    public static synchronized boolean unregister(String id) {
        if (CarriageContents.isReservedBuiltinName(id)) return false;
        boolean removed = CUSTOMS.remove(id.toLowerCase(Locale.ROOT));
        if (removed) {
            games.brennan.dungeontrain.editor.CarriageContentsVariantBlocks.invalidate(
                id.toLowerCase(Locale.ROOT));
        }
        return removed;
    }

    /**
     * Deterministic <em>weighted</em> contents pick for a carriage. Seeds a
     * fresh {@link Random} with {@code worldSeed} XOR'd through the carriage
     * index so the same world + same index always resolves to the same
     * contents across reloads. Weights come from
     * {@link CarriageContentsWeights#current()} — weight 0 excludes a contents
     * from the pool entirely; if every contents has weight 0 the function
     * falls back to a uniform pick over the full registry (and warns once
     * per server lifetime so the misconfiguration surfaces).
     *
     * <p>Mirrors {@link games.brennan.dungeontrain.train.CarriagePlacer}'s
     * {@code weightedSeededPick} shape (cumulative array + threshold draw)
     * with a position-free seed mixing constant so a contents pick and a
     * variant-block pick for the same carriage are not correlated.</p>
     */
    public static synchronized CarriageContents pick(long worldSeed, int carriageIndex) {
        return pick(worldSeed, carriageIndex, CarriageContentsAllowList.EMPTY);
    }

    /**
     * Variant-aware {@link #pick(long, int)}. Loads the per-variant allow-list
     * from {@link CarriageVariantContentsAllowStore} and delegates to
     * {@link #pick(long, int, CarriageContentsAllowList)}. {@code variant == null}
     * skips the lookup and falls through to no filtering (matches the legacy
     * 2-arg behaviour for callers / tests that don't have a variant in scope).
     */
    public static synchronized CarriageContents pick(long worldSeed, int carriageIndex, CarriageVariant variant) {
        return pick(worldSeed, carriageIndex, variant, null);
    }

    /**
     * Gate-aware {@link #pick(long, int, CarriageVariant)}: when {@code gateCtx} is non-null,
     * contents whose per-template gate excludes its Diff-Level / phase are dropped from the pool
     * before the weighted draw (an emptied pool falls back to the ungated pool). {@code null} skips
     * gating — used by the editor-preview path (sentinel pIdx) and tests.
     */
    public static synchronized CarriageContents pick(long worldSeed, int carriageIndex, CarriageVariant variant, GateContext gateCtx) {
        CarriageContentsAllowList allow = (variant == null)
            ? CarriageContentsAllowList.EMPTY
            : CarriageVariantContentsAllowStore.get(variant).orElse(CarriageContentsAllowList.EMPTY);
        return pick(worldSeed, carriageIndex, allow, gateCtx);
    }

    /**
     * Pure overload: weighted deterministic pick filtered by the supplied
     * allow-list. Excluded ids are dropped from the candidate pool before the
     * weighted draw. Group-member ids are also excluded from the top-level
     * pool — a child only ever spawns by being picked through its parent's
     * group, never as a sibling of it. Safety net: if the allow-list excludes
     * every registered content, the function falls back to the
     * {@link ContentsType#DEFAULT} built-in so the carriage still spawns with
     * a coherent interior. Visible for testing — no filesystem state, no
     * Forge bootstrap needed.
     *
     * <p>Two-phase: the synchronised top-level pick produces a parent id
     * deterministically; the unsynchronised resolve step looks up
     * {@link CarriageContentsGroupStore} and, if the picked id is a group
     * parent, draws a child from its weighted member list. The child rng is
     * derived via {@code new Random(parentRng.nextLong())} so adding/removing
     * group definitions does not shift the parent-pick distribution for
     * unrelated ids.</p>
     */
    public static CarriageContents pick(long worldSeed, int carriageIndex, CarriageContentsAllowList allow) {
        return pick(worldSeed, carriageIndex, allow, null);
    }

    /** Gate-aware {@link #pick(long, int, CarriageContentsAllowList)}. */
    public static CarriageContents pick(long worldSeed, int carriageIndex, CarriageContentsAllowList allow, GateContext gateCtx) {
        CarriageContentsAllowList safeAllow = (allow == null) ? CarriageContentsAllowList.EMPTY : allow;
        PickContext ctx = buildPickContext(worldSeed, carriageIndex, safeAllow, gateCtx);
        if (ctx.fallbackToDefault) {
            return CarriageContents.of(ContentsType.DEFAULT);
        }
        return resolveGroup(ctx.picked, ctx.parentRng, safeAllow, gateCtx);
    }

    /** Synchronised pool snapshot + top-level weighted pick. */
    private static synchronized PickContext buildPickContext(
        long worldSeed, int carriageIndex, CarriageContentsAllowList allow, GateContext gateCtx
    ) {
        List<CarriageContents> all = allContents();
        if (all.isEmpty()) return PickContext.fallback();

        Set<String> childIds = CarriageContentsGroupStore.allChildIds();
        List<CarriageContents> pool = new ArrayList<>(all.size());
        for (CarriageContents c : all) {
            if (childIds.contains(c.id())) continue;
            if (!allow.isAllowed(c.id())) continue;
            pool.add(c);
        }
        if (pool.isEmpty()) {
            return PickContext.fallback();
        }

        CarriageContentsWeights weights = CarriageContentsWeights.current();

        // Per-template spawn gate (min/max Diff-Level + phase). Drop out-of-band / out-of-phase
        // contents before the weighted draw; if that empties the pool, fall back to the ungated pool
        // so the carriage still gets an interior (a content slot must never be left unfillable).
        List<CarriageContents> effective = pool;
        if (gateCtx != null) {
            List<CarriageContents> gated = new ArrayList<>(pool.size());
            for (CarriageContents c : pool) {
                if (gateCtx.allows(weights.gateFor(c.id()))) gated.add(c);
            }
            if (!gated.isEmpty()) {
                effective = gated;
            } else {
                warnGateEmptyOnce();
            }
        }

        int m = effective.size();
        int[] cumulative = new int[m];
        int total = 0;
        for (int i = 0; i < m; i++) {
            total += weights.weightFor(effective.get(i).id());
            cumulative[i] = total;
        }
        long seed = worldSeed ^ ((long) carriageIndex * 0x9E3779B97F4A7C15L);
        Random rng = new Random(seed);
        CarriageContents picked;
        if (total <= 0) {
            warnAllZeroOnce();
            picked = effective.get(rng.nextInt(m));
        } else {
            int r = rng.nextInt(total);
            CarriageContents tail = effective.get(m - 1);
            CarriageContents found = tail;
            for (int i = 0; i < m; i++) {
                if (r < cumulative[i]) { found = effective.get(i); break; }
            }
            picked = found;
        }
        return new PickContext(picked, rng, false);
    }

    /**
     * Default weight assigned to the synthetic self-entry in a group's
     * resolution pool when the group sidecar omits the {@code selfWeight}
     * field. The parent's own {@code .nbt} is always a candidate alongside its
     * explicit members — this mirrors the "the original variant is the default
     * sub-variant" model and matches the editor UI's first row.
     *
     * <p>Per-group {@code selfWeight} is editable via the
     * {@code editor contents group set-weight <parent> <parent> ...} command
     * and persisted in the group sidecar. This constant remains the fallback
     * for pre-v1.1 sidecars that lack the field.</p>
     */
    public static final int SELF_WEIGHT = CarriageContentsGroup.DEFAULT_SELF_WEIGHT;

    /** Sentinel id used internally to mark the synthetic-self entry in the resolution pool. */
    private static final String SELF_TOKEN = "<self>";

    /**
     * If {@code picked} has a group sidecar, draw one of its weighted members.
     * The pool is the synthetic self ({@link #SELF_WEIGHT}) + every explicit
     * member. The per-carriage-variant allow-list is NOT consulted at this
     * level — it operates only on parents at top-level pick time, by
     * design. Once a parent passes the allow-list and gets picked, its full
     * sub-variant pool runs unfiltered.
     *
     * <p>Members carry their own spawn {@link TemplateGate gate} (or a linked Stage's gate). When
     * {@code gateCtx != null} out-of-band / out-of-phase members are dropped before the draw,
     * mirroring the top-level pool gating in {@link #buildPickContext}. Unlike that pool, the
     * synthetic <b>self</b> entry is always kept — it is the parent's own contents, already
     * gate-cleared at the top-level pick — so the slot stays fillable even when the gate excludes
     * every member. Filtering is therefore <b>strict</b> (no ungated fallback): falling back would
     * spawn a member outside its allowed band, defeating the gate.</p>
     */
    @SuppressWarnings("unused") // allow-list intentionally not used inside group resolution
    private static CarriageContents resolveGroup(
        CarriageContents picked, Random parentRng, CarriageContentsAllowList allow, GateContext gateCtx
    ) {
        Optional<CarriageContentsGroup> groupOpt = CarriageContentsGroupStore.get(picked.id());
        if (groupOpt.isEmpty() || groupOpt.get().isEmpty()) return picked;
        CarriageContentsGroup group = groupOpt.get();

        // Members eligible for the current spawn context — the per-member gate (or its linked Stage's
        // gate) filters here. Strict: an emptied member pool falls through to self-only, never the
        // ungated list (see method doc).
        List<CarriageContentsGroup.Member> members = group.members();
        if (gateCtx != null) {
            List<CarriageContentsGroup.Member> gated = new ArrayList<>(members.size());
            for (CarriageContentsGroup.Member m : members) {
                if (memberAllows(gateCtx, m)) {
                    gated.add(m);
                }
            }
            if (members.size() != gated.size() && gated.isEmpty()) warnGroupGateEmptyOnce(picked.id());
            members = gated;
        }

        // Pool: synthetic self + every eligible member, no allow-list filter.
        // Self-weight is read from the group sidecar (defaults to
        // CarriageContentsGroup.DEFAULT_SELF_WEIGHT for pre-v1.1 files).
        List<PoolEntry> pool = new ArrayList<>(members.size() + 1);
        pool.add(new PoolEntry(SELF_TOKEN, group.selfWeight()));
        for (CarriageContentsGroup.Member m : members) {
            pool.add(new PoolEntry(m.id(), m.weight()));
        }

        Random childRng = new Random(parentRng.nextLong());
        int total = 0;
        for (PoolEntry e : pool) total += e.weight;
        PoolEntry chosen;
        if (total <= 0) {
            chosen = pool.get(childRng.nextInt(pool.size()));
        } else {
            int r = childRng.nextInt(total);
            PoolEntry found = pool.get(pool.size() - 1);
            int cumulative = 0;
            for (PoolEntry e : pool) {
                cumulative += e.weight;
                if (r < cumulative) { found = e; break; }
            }
            chosen = found;
        }

        // Synthetic self → return parent unchanged. Placer handles the rest
        // (parent's .nbt if present, else its own warning path for empty leaves).
        if (SELF_TOKEN.equals(chosen.id)) {
            return picked;
        }

        Optional<CarriageContents> childOpt = find(chosen.id);
        if (childOpt.isEmpty()) {
            warnMissingChild(picked.id(), chosen.id);
            return CarriageContents.of(ContentsType.DEFAULT);
        }
        if (CarriageContentsGroupStore.exists(chosen.id)) {
            // Defensive: nested groups should be rejected at write time. If
            // one slipped through (e.g. authored by hand), treat as a leaf —
            // do NOT recurse.
            warnNestedGroup(picked.id(), chosen.id);
        }
        return childOpt.get();
    }

    /**
     * Whether {@code gateCtx} admits {@code member}. When the member links to no Stage its inline
     * {@code gate} is tested directly. When it links to one or more Stages the member is admitted if
     * <b>any</b> linked Stage's effective gate allows the context (a union) — so a member linked to
     * "desert" and "nether" spawns in either band.
     */
    private static boolean memberAllows(GateContext gateCtx, CarriageContentsGroup.Member member) {
        List<String> stageIds = member.stageIds();
        if (stageIds.isEmpty()) {
            return gateCtx.allows(member.gate());
        }
        for (String stageId : stageIds) {
            if (gateCtx.allows(games.brennan.dungeontrain.editor.StageStore.effectiveGate(member.gate(), stageId))) {
                return true;
            }
        }
        return false;
    }

    /** Internal pool entry — one of: synthetic self (id={@link #SELF_TOKEN}) or a real group member. */
    private record PoolEntry(String id, int weight) {}

    /** Internal envelope for the synchronised pick phase. */
    private record PickContext(CarriageContents picked, Random parentRng, boolean fallbackToDefault) {
        static PickContext fallback() {
            return new PickContext(null, null, true);
        }
    }

    /** One-shot warning when every registered contents has weight 0 — once per server lifetime. */
    private static volatile boolean ZERO_WARNED = false;
    private static void warnAllZeroOnce() {
        if (ZERO_WARNED) return;
        synchronized (CarriageContentsRegistry.class) {
            if (ZERO_WARNED) return;
            ZERO_WARNED = true;
            LOGGER.warn("[DungeonTrain] All carriage contents have weight 0 — falling back to uniform pick. "
                + "Set at least one contents weight > 0 in config/dungeontrain/user/contents/weights.json.");
        }
    }

    /** One-shot warning when min/max-level + phase gates empty the contents pool — once per session. */
    private static volatile boolean GATE_EMPTY_WARNED = false;
    private static void warnGateEmptyOnce() {
        if (GATE_EMPTY_WARNED) return;
        synchronized (CarriageContentsRegistry.class) {
            if (GATE_EMPTY_WARNED) return;
            GATE_EMPTY_WARNED = true;
            LOGGER.warn("[DungeonTrain] Min/max-level + phase gates emptied the carriage contents pool — "
                + "falling back to the ungated pool. Check the contents' level bands and phases.");
        }
    }

    /** De-duped per-parent warning when a group's per-member gates exclude every member for a pick. */
    private static final Set<String> GROUP_GATE_EMPTY_WARNED = ConcurrentHashMap.newKeySet();
    private static void warnGroupGateEmptyOnce(String parentId) {
        if (GROUP_GATE_EMPTY_WARNED.add(parentId)) {
            LOGGER.warn("[DungeonTrain] Contents group '{}' — per-member gates excluded every sub-variant for this "
                + "spawn context; only the parent's own contents are eligible. Check the members' level bands / phases.",
                parentId);
        }
    }

    /** De-duped per-(parent, child) warning when a group member id no longer resolves. */
    private static final Set<String> MISSING_CHILD_WARNED = ConcurrentHashMap.newKeySet();
    private static void warnMissingChild(String parentId, String childId) {
        if (MISSING_CHILD_WARNED.add(parentId + "->" + childId)) {
            LOGGER.warn("[DungeonTrain] Contents group '{}' references unknown child '{}' — falling back to DEFAULT for this pick.",
                parentId, childId);
        }
    }

    /** De-duped per-(parent, child) error when a group's member is itself a group parent. */
    private static final Set<String> NESTED_GROUP_WARNED = ConcurrentHashMap.newKeySet();
    private static void warnNestedGroup(String parentId, String childId) {
        if (NESTED_GROUP_WARNED.add(parentId + "->" + childId)) {
            LOGGER.error("[DungeonTrain] Contents group '{}' has nested-group member '{}' — nested groups are NOT resolved. Treating as leaf.",
                parentId, childId);
        }
    }

    /** Reload custom contents from the bundled classpath scan + the per-install config dir. */
    public static synchronized void reload() {
        CUSTOMS.clear();
        int bundled = loadBundledScan();
        int config = loadConfigDir();
        validateGroups();

        LOGGER.info("[DungeonTrain] Carriage contents registry loaded — {} built-in + {} custom ({} bundled, {} config)",
            BUILTINS.size(), CUSTOMS.size(), bundled, config);
    }

    /**
     * Walk every loaded group sidecar and log:
     * <ul>
     *   <li>WARN: members whose id no longer resolves to a registered content
     *       (they're skipped at resolution time, not auto-removed from the file
     *       — author intent is preserved).</li>
     *   <li>ERROR: members that are themselves group parents (nested groups
     *       are not supported; resolution treats them as leaves).</li>
     * </ul>
     */
    private static void validateGroups() {
        for (String parentId : CarriageContentsGroupStore.knownParentIds()) {
            Optional<CarriageContentsGroup> opt = CarriageContentsGroupStore.get(parentId);
            if (opt.isEmpty()) continue;
            for (CarriageContentsGroup.Member m : opt.get().members()) {
                if (find(m.id()).isEmpty()) {
                    LOGGER.warn("[DungeonTrain] Contents group '{}' references unknown member '{}' — member will be skipped at resolution.",
                        parentId, m.id());
                } else if (CarriageContentsGroupStore.exists(m.id())) {
                    LOGGER.error("[DungeonTrain] Contents group '{}' has nested-group member '{}' — nested groups are NOT resolved. Fix the configuration.",
                        parentId, m.id());
                }
            }
        }
    }

    /**
     * Discover bundled custom contents by scanning the classpath at
     * {@link #BUNDLED_RESOURCE_PREFIX} for {@code .nbt} files. Built-in
     * contents (e.g. {@code default.nbt}) are filtered out via
     * {@link CarriageContents#isReservedBuiltinName} so they don't enter
     * {@link #CUSTOMS} or generate spurious drift warnings against the
     * legacy {@code customs.json}.
     */
    private static int loadBundledScan() {
        Set<String> nbtScanned = BundledNbtScanner.scanBasenames(
            CarriageContentsRegistry.class, BUNDLED_RESOURCE_PREFIX, LOGGER);
        Set<String> groupScanned = BundledNbtScanner.scanBasenames(
            CarriageContentsRegistry.class, BUNDLED_RESOURCE_PREFIX, LOGGER, ".group.json");
        TreeSet<String> customsScanned = new TreeSet<>();
        for (String id : nbtScanned) {
            if (CarriageContents.isReservedBuiltinName(id)) continue;
            customsScanned.add(id);
        }
        for (String id : groupScanned) {
            if (CarriageContents.isReservedBuiltinName(id)) {
                LOGGER.warn("[DungeonTrain] Ignoring bundled group sidecar for built-in '{}' — group parents must be custom.", id);
                continue;
            }
            customsScanned.add(id);
            CarriageContentsGroupStore.preload(id);
        }
        Set<String> manifest = BundledNbtScanner.readManifestBasenames(
            CarriageContentsRegistry.class, BUNDLED_RESOURCE_PREFIX, "customs.json", LOGGER);
        BundledNbtScanner.warnDrift("contents", customsScanned, manifest, LOGGER);
        int added = 0;
        for (String id : customsScanned) {
            if (!acceptCustomId(id, "bundled scan")) continue;
            if (CUSTOMS.add(id)) added++;
        }
        return added;
    }

    private static int loadConfigDir() {
        // Walks user/contents/ + every imported/<pkg>/contents/. Duplicate
        // ids across packages are fine — load-time resolution in
        // CarriageContentsStore uses UserContentPaths.findFile and prefers
        // user/ first, then alphabetical packages.
        java.util.Set<String> ids = games.brennan.dungeontrain.editor.UserContentPaths
            .listBasenamesAcrossSearchDirs(
                games.brennan.dungeontrain.editor.CarriageContentsStore.SUBDIR, ".nbt");
        int added = 0;
        for (String basename : ids) {
            if (CarriageContents.isReservedBuiltinName(basename)) continue;
            if (!acceptCustomId(basename, "user/ + imports")) continue;
            if (CUSTOMS.add(basename)) added++;
        }
        // Group-only parents (no .nbt) are discovered through a parallel scan
        // for *.group.json files across the same search dirs (user/ + imports).
        // Children of groups remain regular customs and are picked up by the
        // .nbt scan above.
        java.util.Set<String> groupIds = games.brennan.dungeontrain.editor.UserContentPaths
            .listBasenamesAcrossSearchDirs(
                games.brennan.dungeontrain.editor.CarriageContentsStore.SUBDIR, ".group.json");
        for (String basename : groupIds) {
            if (CarriageContents.isReservedBuiltinName(basename)) {
                LOGGER.warn("[DungeonTrain] Ignoring group sidecar for built-in '{}' — group parents must be custom.",
                    basename);
                continue;
            }
            if (!acceptCustomId(basename, "user/ + imports (group)")) continue;
            CarriageContentsGroupStore.preload(basename);
            if (CUSTOMS.add(basename)) added++;
        }
        return added;
    }

    private static boolean acceptCustomId(String id, String origin) {
        if (CarriageContents.isReservedBuiltinName(id)) {
            LOGGER.warn("[DungeonTrain] Ignoring custom contents '{}' from {} — collides with built-in name", id, origin);
            return false;
        }
        if (!CarriageContents.NAME_PATTERN.matcher(id).matches()) {
            LOGGER.warn("[DungeonTrain] Ignoring custom contents '{}' from {} — invalid name", id, origin);
            return false;
        }
        return true;
    }

    public static synchronized void clear() {
        CUSTOMS.clear();
        CarriageVariantContentsAllowStore.clearCache();
        CarriageContentsGroupStore.clearCache();
        ZERO_WARNED = false;
        GATE_EMPTY_WARNED = false;
        MISSING_CHILD_WARNED.clear();
        NESTED_GROUP_WARNED.clear();
    }

    public static synchronized List<String> customIds() {
        return Collections.unmodifiableList(new ArrayList<>(CUSTOMS));
    }

    /**
     * Helper: the interior block-position where the {@code default} built-in
     * places its stone pressure plate — the floor centre of the interior
     * volume. Floor is at {@code dy=0} in the interior coord space (the shell
     * floor is one layer below, outside the interior template).
     */
    public static BlockPos defaultPressurePlatePos(CarriageDims dims) {
        return new BlockPos(
            (dims.length() - 2) / 2,
            0,
            (dims.width() - 2) / 2
        );
    }

        public static void onServerStarting(net.minecraft.server.MinecraftServer server) {
        reload();
    }

        public static void onServerStopped(net.minecraft.server.MinecraftServer server) {
        clear();
    }

    /**
     * Phase-2 adapter — exposes the contents registry through the unified
     * {@link TemplateRegistry} surface. Wraps each registered
     * {@link CarriageContents} into a {@link Template.Contents}.
     */
    private static final TemplateRegistry<Template.Contents> ADAPTER = new TemplateRegistry<>() {
        @Override public TemplateKind kind() { return TemplateKind.CONTENTS; }

        @Override
        public List<Template.Contents> all() {
            List<CarriageContents> all = allContents();
            List<Template.Contents> out = new ArrayList<>(all.size());
            for (CarriageContents c : all) out.add(new Template.Contents(c));
            return out;
        }

        @Override
        public List<Template.Contents> builtins() {
            List<CarriageContents> bs = CarriageContentsRegistry.builtins();
            List<Template.Contents> out = new ArrayList<>(bs.size());
            for (CarriageContents c : bs) out.add(new Template.Contents(c));
            return out;
        }

        @Override
        public List<Template.Contents> customs() {
            List<String> ids = customIds();
            List<Template.Contents> out = new ArrayList<>(ids.size());
            for (String id : ids) out.add(new Template.Contents(new CarriageContents.Custom(id)));
            return out;
        }

        @Override
        public Optional<Template.Contents> find(String id) {
            return CarriageContentsRegistry.find(id).map(Template.Contents::new);
        }

        @Override public void reload() { CarriageContentsRegistry.reload(); }
        @Override public void clear() { CarriageContentsRegistry.clear(); }
    };

    public static TemplateRegistry<Template.Contents> adapter() { return ADAPTER; }
}
