package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageVariantContentsAllowStore;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateRegistry;
import games.brennan.dungeontrain.train.CarriageContents.ContentsType;
import games.brennan.dungeontrain.util.BundledNbtScanner;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
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
 * templates live under {@code config/dungeontrain/templates/} and pillar
 * templates under {@code config/dungeontrain/pillars/}. Because all three
 * stores have distinct subdirs, file-name collisions cannot leak contents into
 * the shell registry or vice versa.
 *
 * <p>{@link #pick(long, int)} mirrors the deterministic position-free variant
 * of {@link games.brennan.dungeontrain.editor.CarriageVariantBlocks#pickIndex}
 * so the same world seed + carriage index always resolves to the same
 * contents across reloads.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
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
        CarriageContentsAllowList allow = (variant == null)
            ? CarriageContentsAllowList.EMPTY
            : CarriageVariantContentsAllowStore.get(variant).orElse(CarriageContentsAllowList.EMPTY);
        return pick(worldSeed, carriageIndex, allow);
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
        CarriageContentsAllowList safeAllow = (allow == null) ? CarriageContentsAllowList.EMPTY : allow;
        PickContext ctx = buildPickContext(worldSeed, carriageIndex, safeAllow);
        if (ctx.fallbackToDefault) {
            return CarriageContents.of(ContentsType.DEFAULT);
        }
        return resolveGroup(ctx.picked, ctx.parentRng, safeAllow);
    }

    /** Synchronised pool snapshot + top-level weighted pick. */
    private static synchronized PickContext buildPickContext(
        long worldSeed, int carriageIndex, CarriageContentsAllowList allow
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

        int m = pool.size();
        CarriageContentsWeights weights = CarriageContentsWeights.current();
        int[] cumulative = new int[m];
        int total = 0;
        for (int i = 0; i < m; i++) {
            total += weights.weightFor(pool.get(i).id());
            cumulative[i] = total;
        }
        long seed = worldSeed ^ ((long) carriageIndex * 0x9E3779B97F4A7C15L);
        Random rng = new Random(seed);
        CarriageContents picked;
        if (total <= 0) {
            warnAllZeroOnce();
            picked = pool.get(rng.nextInt(m));
        } else {
            int r = rng.nextInt(total);
            CarriageContents tail = pool.get(m - 1);
            CarriageContents found = tail;
            for (int i = 0; i < m; i++) {
                if (r < cumulative[i]) { found = pool.get(i); break; }
            }
            picked = found;
        }
        return new PickContext(picked, rng, false);
    }

    /**
     * Weight assigned to the synthetic self-entry in a group's resolution pool.
     * The parent's own {@code .nbt} is always a candidate alongside its
     * explicit members — this mirrors the "the original variant is the default
     * sub-variant" model and matches the editor UI's first row.
     *
     * <p>Fixed at 1 in this phase. Editable weights for self are a future
     * extension (would require a schema field on the group sidecar).</p>
     */
    public static final int SELF_WEIGHT = 1;

    /** Sentinel id used internally to mark the synthetic-self entry in the resolution pool. */
    private static final String SELF_TOKEN = "<self>";

    /**
     * If {@code picked} has a group sidecar, draw one of its weighted members
     * (filtered through {@code allow} so allow-list controls are coherent at
     * both pick levels). The pool always includes a synthetic self-entry with
     * {@link #SELF_WEIGHT} so the parent's own {@code .nbt} stays in rotation
     * even after members are added. Returns {@link ContentsType#DEFAULT} only
     * when both self and all members are excluded by the allow-list.
     */
    private static CarriageContents resolveGroup(
        CarriageContents picked, Random parentRng, CarriageContentsAllowList allow
    ) {
        Optional<CarriageContentsGroup> groupOpt = CarriageContentsGroupStore.get(picked.id());
        if (groupOpt.isEmpty() || groupOpt.get().isEmpty()) return picked;
        CarriageContentsGroup group = groupOpt.get();

        // Build pool: synthetic self (always weight=SELF_WEIGHT) + filtered members.
        // Self is included iff the parent itself is allowed by the allow-list.
        boolean selfAllowed = allow.isAllowed(picked.id());
        List<PoolEntry> pool = new ArrayList<>(group.members().size() + 1);
        if (selfAllowed) {
            pool.add(new PoolEntry(SELF_TOKEN, SELF_WEIGHT));
        }
        for (CarriageContentsGroup.Member m : group.members()) {
            if (!allow.isAllowed(m.id())) continue;
            pool.add(new PoolEntry(m.id(), m.weight()));
        }
        if (pool.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Contents group '{}' has no allowed members (including self) under current allow-list — falling back to DEFAULT.",
                picked.id());
            return CarriageContents.of(ContentsType.DEFAULT);
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
                + "Set at least one contents weight > 0 in config/dungeontrain/contents/weights.json.");
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
        Path dir = CarriageContentsStore.directory();
        if (!Files.isDirectory(dir)) return 0;

        int added = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.nbt")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".nbt")) continue;
                String basename = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT);
                if (CarriageContents.isReservedBuiltinName(basename)) continue;
                if (!acceptCustomId(basename, "config dir " + file.getFileName())) continue;
                if (CUSTOMS.add(basename)) added++;
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to scan contents directory {}: {}", dir, e.toString());
        }
        // Group-only parents (no .nbt) are discovered through a parallel scan
        // for *.group.json files. Children of groups remain regular customs
        // and are picked up by the .nbt scan above.
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.group.json")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".group.json")) continue;
                String basename = name.substring(0, name.length() - ".group.json".length()).toLowerCase(Locale.ROOT);
                if (CarriageContents.isReservedBuiltinName(basename)) {
                    LOGGER.warn("[DungeonTrain] Ignoring group sidecar for built-in '{}' at {} — group parents must be custom.",
                        basename, file);
                    continue;
                }
                if (!acceptCustomId(basename, "config dir " + file.getFileName())) continue;
                CarriageContentsGroupStore.preload(basename);
                if (CUSTOMS.add(basename)) added++;
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to scan contents group sidecars in {}: {}", dir, e.toString());
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

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
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
