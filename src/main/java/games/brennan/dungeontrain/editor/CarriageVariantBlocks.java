package games.brennan.dungeontrain.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Sidecar JSON ({@code <id>.variants.json}) that pairs with each carriage
 * variant's {@code .nbt} template. Each entry maps a carriage-local
 * {@link BlockPos} to a list of candidate {@link BlockState}s — one of which
 * is picked deterministically at spawn time based on the world seed and the
 * carriage index.
 *
 * <p>The NBT template stays the source of truth for the carriage's "default"
 * block at every position; this sidecar only describes positions the author
 * wants to randomise. Missing sidecar = no variants, full backward compat
 * with every existing template on disk or in the mod jar.</p>
 *
 * <p>Follows the three-tier lookup used by {@link CarriageTemplateStore}:
 * per-install config dir ({@link #configPathFor}) overrides bundled resource
 * ({@link #bundledResourceFor}). Unlike the NBT store, there is no hardcoded
 * fallback — a variant with no sidecar simply has no random positions.</p>
 *
 * <p>v4 schema (current):
 * <pre>
 * {
 *   "schemaVersion": 4,
 *   "variants": {
 *     "3,1,2": [
 *       "minecraft:stone",
 *       { "state": "minecraft:bookshelf" },
 *       { "state": "minecraft:chest[facing=west]", "nbt": "{Items:[...]}" },
 *       { "state": "minecraft:cobblestone", "weight": 3 }
 *     ],
 *     "4,1,2": {
 *       "lockId": 1,
 *       "states": [ "minecraft:stone", "minecraft:cobblestone" ]
 *     }
 *   }
 * }
 * </pre>
 * Keys use local coords (same basis as {@code StructureTemplate} block infos).
 * Each cell value is either a bare states array (lockId=0; v3-shape) or an
 * object {@code {lockId, states:[...]}} when {@code lockId>0}. Each candidate
 * inside the states array is either a bare BlockState string or a
 * {@code {state, nbt?, weight?}} object. v1 / v2 / v3 files load
 * unchanged; the v4 writer emits the cell-object form only when {@code
 * lockId>0}, so older files round-trip diff-clean.</p>
 */
public final class CarriageVariantBlocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Current schema version written by {@link #save}.
     * <ul>
     *   <li>v1 — bare BlockState strings.</li>
     *   <li>v2 — adds optional per-entry BlockEntity NBT.</li>
     *   <li>v3 — adds optional per-entry {@code weight} (≥1, default 1)
     *       and a per-entry {@code locked} flag (since dropped). Older
     *       files load with the defaults and round-trip diff-clean since
     *       the writer only emits object form when one of {NBT,
     *       non-default weight} is set.</li>
     *   <li>v4 — adds optional per-cell {@code lockId} (≥0, default 0).
     *       Cells with the same non-zero {@code lockId} share a runtime
     *       random pick at spawn (called once per group, all cells in
     *       the group render the same index). The cell's value is now
     *       either a bare states array (v3 form, lockId=0) or an object
     *       {@code {"states":[...], "lockId":N}} (lockId&gt;0). v3
     *       per-entry {@code locked} fields are silently ignored on
     *       read.</li>
     *   <li>v5 — adds optional per-entry {@code rotation} object
     *       {@code {"mode": "lock|random|options", "dirs": ["east", ...]}}.
     *       Default rotation (random with empty mask) is omitted from
     *       JSON, so v3/v4 entries round-trip diff-clean.</li>
     * </ul>
     */
    public static final int CURRENT_SCHEMA_VERSION = 5;

    private static final String SUBDIR = "dungeontrain/templates";
    private static final String EXT = ".variants.json";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/templates/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/templates";

    /** Minimum candidates per entry — a single-state "variant" is just a fixed block. */
    public static final int MIN_STATES_PER_ENTRY = 2;

    /**
     * Returns true if {@code state} is a command-block sentinel used in variant
     * lists to mean "leave this position empty / air at spawn time." Covers all
     * three command-block kinds (impulse, chain, repeating) so any command block
     * the author places counts. The sentinel is stored verbatim in the JSON; the
     * translation to {@code Blocks.AIR} happens in the apply path.
     */
    public static boolean isEmptyPlaceholder(BlockState state) {
        if (state == null) return false;
        return state.is(Blocks.COMMAND_BLOCK)
            || state.is(Blocks.CHAIN_COMMAND_BLOCK)
            || state.is(Blocks.REPEATING_COMMAND_BLOCK);
    }

    // Session cache keyed by variant id. Invalidated on save and on editor
    // enter (re-read from disk so a manual edit picks up immediately).
    private static final Map<String, CarriageVariantBlocks> CACHE = new HashMap<>();

    /** Local position + candidate variants. Ordering of {@code states} is preserved as written. */
    public record Entry(BlockPos localPos, List<VariantState> states) {
        public Entry {
            if (localPos == null) throw new IllegalArgumentException("localPos");
            if (states == null || states.size() < MIN_STATES_PER_ENTRY) {
                throw new IllegalArgumentException(
                    "entry at " + localPos + " must list at least " + MIN_STATES_PER_ENTRY + " states");
            }
            for (VariantState s : states) {
                if (s == null) throw new IllegalArgumentException("null state in entry " + localPos);
            }
            states = List.copyOf(states);
        }
    }

    /** pos → candidate variants. {@link LinkedHashMap} keeps insertion order for deterministic JSON output. */
    private final Map<BlockPos, List<VariantState>> entries;

    /**
     * pos → lock-id (≥1 means locked, 0/missing means unlocked). Cells
     * with the same non-zero lock-id share a runtime random pick at
     * spawn — the picker hashes the lock-id instead of the local
     * position, so all cells in the group land on the same index. The
     * lock-id space is local to this sidecar (per template).
     */
    private final Map<BlockPos, Integer> lockIds;

    private CarriageVariantBlocks(Map<BlockPos, List<VariantState>> entries, Map<BlockPos, Integer> lockIds) {
        this.entries = entries;
        this.lockIds = lockIds;
    }

    public static CarriageVariantBlocks empty() {
        return new CarriageVariantBlocks(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    /** On-disk path for the config-dir sidecar matching {@code variant}. */
    public static Path configPathFor(CarriageVariant variant) {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(SUBDIR).resolve(variant.id() + EXT);
    }

    /** Classpath resource for the bundled sidecar matching {@code variant} (only exists for shipped variants). */
    public static String bundledResourceFor(CarriageVariant variant) {
        return RESOURCE_PREFIX + variant.id() + EXT;
    }

    /** Source-tree path for the bundled sidecar — only writable in a {@code ./gradlew runClient} dev checkout. */
    public static Path sourcePathFor(CarriageType type) {
        Path gameDir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
        Path projectRoot = gameDir.getParent();
        if (projectRoot == null) {
            throw new IllegalStateException("Cannot resolve source directory — FMLPaths.GAMEDIR has no parent.");
        }
        return projectRoot.resolve(SOURCE_REL_PATH).resolve(type.name().toLowerCase(Locale.ROOT) + EXT);
    }

    /**
     * Load the sidecar for {@code variant} — per-install config dir first,
     * then bundled resource. Returns an empty instance if neither exists (or
     * both fail to parse). Result is cached by variant id until {@link #save}
     * or {@link #clearCache} is called. Entries outside the {@code dims}
     * footprint are dropped with a warning.
     */
    public static synchronized CarriageVariantBlocks loadFor(CarriageVariant variant, CarriageDims dims) {
        String id = variant.id();
        CarriageVariantBlocks cached = CACHE.get(id);
        if (cached != null) return cached;
        CarriageVariantBlocks loaded = loadFromDisk(variant, dims);
        CACHE.put(id, loaded);
        return loaded;
    }

    private static CarriageVariantBlocks loadFromDisk(CarriageVariant variant, CarriageDims dims) {
        Path cfg = configPathFor(variant);
        if (Files.isRegularFile(cfg)) {
            try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
                return parse(r, variant.id(), "config " + cfg, dims);
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Failed to read variant sidecar {}: {}", cfg, e.toString());
            }
        }
        String resource = bundledResourceFor(variant);
        try (InputStream in = CarriageVariantBlocks.class.getResourceAsStream(resource)) {
            if (in == null) return empty();
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(r, variant.id(), "bundled " + resource, dims);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled variant sidecar {}: {}", resource, e.toString());
            return empty();
        }
    }

    private static CarriageVariantBlocks parse(Reader reader, String id, String origin, CarriageDims dims) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            LOGGER.warn("[DungeonTrain] Variant sidecar {} ({}) is not a JSON object — ignoring.", id, origin);
            return empty();
        }
        JsonObject obj = root.getAsJsonObject();
        if (obj.has("schemaVersion")) {
            int v = obj.get("schemaVersion").getAsInt();
            if (v > CURRENT_SCHEMA_VERSION) {
                LOGGER.warn("[DungeonTrain] Variant sidecar {} ({}) has schemaVersion {} (newer than {}) — attempting best-effort parse.",
                    id, origin, v, CURRENT_SCHEMA_VERSION);
            }
        }
        if (!obj.has("variants") || !obj.get("variants").isJsonObject()) {
            return empty();
        }
        HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> blocks =
            BuiltInRegistries.BLOCK.asLookup();

        JsonObject variants = obj.getAsJsonObject("variants");
        Map<BlockPos, List<VariantState>> out = new LinkedHashMap<>();
        Map<BlockPos, Integer> outLocks = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> field : variants.entrySet()) {
            BlockPos pos = parsePos(field.getKey());
            if (pos == null) {
                LOGGER.warn("[DungeonTrain] Variant sidecar {}: bad position key '{}', skipping.", id, field.getKey());
                continue;
            }
            if (!inBounds(pos, dims)) {
                LOGGER.warn("[DungeonTrain] Variant sidecar {}: position {} outside dims {}x{}x{}, skipping.",
                    id, pos, dims.length(), dims.height(), dims.width());
                continue;
            }
            JsonArray arr;
            int lockId = 0;
            JsonElement value = field.getValue();
            if (value.isJsonArray()) {
                arr = value.getAsJsonArray();
            } else if (value.isJsonObject()) {
                JsonObject cellObj = value.getAsJsonObject();
                if (!cellObj.has("states") || !cellObj.get("states").isJsonArray()) {
                    LOGGER.warn("[DungeonTrain] Variant sidecar {}: cell at {} missing 'states' array, skipping.",
                        id, pos);
                    continue;
                }
                arr = cellObj.getAsJsonArray("states");
                if (cellObj.has("lockId") && cellObj.get("lockId").isJsonPrimitive()
                    && cellObj.get("lockId").getAsJsonPrimitive().isNumber()) {
                    int raw = cellObj.get("lockId").getAsInt();
                    lockId = raw < 0 ? 0 : raw;
                }
            } else {
                LOGGER.warn("[DungeonTrain] Variant sidecar {}: value for {} is neither array nor object, skipping.",
                    id, pos);
                continue;
            }
            List<VariantState> states = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                VariantState parsed = parseVariantElement(el, blocks, id, pos);
                if (parsed != null) states.add(parsed);
            }
            if (states.size() < MIN_STATES_PER_ENTRY) {
                LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: fewer than {} valid states, dropping entry.",
                    id, pos, MIN_STATES_PER_ENTRY);
                continue;
            }
            BlockPos posI = pos.immutable();
            out.put(posI, List.copyOf(states));
            if (lockId > 0) outLocks.put(posI, lockId);
        }
        LOGGER.info("[DungeonTrain] Loaded {} variant entries for {} from {}", out.size(), id, origin);
        return new CarriageVariantBlocks(out, outLocks);
    }

    /**
     * Parse one element of a per-cell candidate array — either a bare string
     * (v1, BlockState only) or an object {@code {state, nbt?, weight?, locked?}}
     * (v2 / v3). Returns {@code null} on parse failure (caller logs).
     */
    public static VariantState parseVariantElement(JsonElement el,
                                            HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> blocks,
                                            String contextId, BlockPos contextPos) {
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            return parseStateString(el.getAsString(), blocks, contextId, contextPos);
        }
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("state") || !obj.get("state").isJsonPrimitive()) {
                LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: object entry missing 'state' field, skipping.",
                    contextId, contextPos);
                return null;
            }
            VariantState base = parseStateString(obj.get("state").getAsString(), blocks, contextId, contextPos);
            if (base == null) return null;
            CompoundTag nbt = null;
            if (obj.has("nbt") && obj.get("nbt").isJsonPrimitive()
                && obj.get("nbt").getAsJsonPrimitive().isString()) {
                String snbt = obj.get("nbt").getAsString();
                try {
                    nbt = TagParser.parseTag(snbt);
                } catch (Exception e) {
                    LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: could not parse 'nbt' SNBT ({}), dropping NBT.",
                        contextId, contextPos, e.getMessage());
                }
            }
            int weight = 1;
            if (obj.has("weight") && obj.get("weight").isJsonPrimitive()
                && obj.get("weight").getAsJsonPrimitive().isNumber()) {
                int raw = obj.get("weight").getAsInt();
                weight = raw < 1 ? 1 : raw;
            }
            VariantRotation rotation = parseRotation(obj.get("rotation"), contextId, contextPos);
            // v3 entries had a per-entry "locked" field; v4 moved locking
            // to the cell level. Old "locked" values are silently dropped
            // on read — the file rewrites cleanly without it.
            return new VariantState(base.state(), nbt, weight, rotation);
        }
        LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: unrecognized entry {}, skipping.",
            contextId, contextPos, el);
        return null;
    }

    private static VariantState parseStateString(String raw,
                                                 HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> blocks,
                                                 String contextId, BlockPos contextPos) {
        try {
            BlockStateParser.BlockResult parsed = BlockStateParser.parseForBlock(blocks, raw, false);
            return VariantState.of(parsed.blockState());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: could not parse '{}' ({}), skipping.",
                contextId, contextPos, raw, e.getMessage());
            return null;
        }
    }

    /** Package-private for test access — parses a {@code "x,y,z"} key back into a {@link BlockPos}, or null. */
    public static BlockPos parsePos(String key) {
        String[] parts = key.split(",");
        if (parts.length != 3) return null;
        try {
            return new BlockPos(
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim())
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean inBounds(BlockPos p, CarriageDims dims) {
        return p.getX() >= 0 && p.getX() < dims.length()
            && p.getY() >= 0 && p.getY() < dims.height()
            && p.getZ() >= 0 && p.getZ() < dims.width();
    }

    /** Package-private for tests — renders a {@link BlockPos} as the {@code "x,y,z"} JSON key. */
    static String formatPos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /** Iterable snapshot of all entries. */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>(entries.size());
        for (Map.Entry<BlockPos, List<VariantState>> e : entries.entrySet()) {
            out.add(new Entry(e.getKey(), e.getValue()));
        }
        return Collections.unmodifiableList(out);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /** Candidate variants at {@code localPos}, or {@code null} if no entry. */
    public List<VariantState> statesAt(BlockPos localPos) {
        return entries.get(localPos);
    }

    /**
     * Replace the candidate list at {@code localPos}. Requires
     * {@link #MIN_STATES_PER_ENTRY} or more entries. v2 supports block-entity
     * states with optional {@link CompoundTag} payload — see {@link VariantState}.
     */
    public synchronized void put(BlockPos localPos, List<VariantState> states) {
        if (states == null || states.size() < MIN_STATES_PER_ENTRY) {
            throw new IllegalArgumentException(
                "need at least " + MIN_STATES_PER_ENTRY + " states, got "
                    + (states == null ? 0 : states.size()));
        }
        for (VariantState s : states) {
            if (s == null) throw new IllegalArgumentException("null state");
        }
        entries.put(localPos.immutable(), List.copyOf(states));
    }

    /** Remove the entry at {@code localPos}. Returns true if one was present. */
    public synchronized boolean remove(BlockPos localPos) {
        lockIds.remove(localPos);
        return entries.remove(localPos) != null;
    }

    /** Lock-id at {@code localPos}; 0 if unlocked or no entry. */
    public synchronized int lockIdAt(BlockPos localPos) {
        return lockIds.getOrDefault(localPos, 0);
    }

    /**
     * Set the lock-id for an existing cell. Pass 0 to unlock. Throws if
     * no cell exists at {@code localPos} — only cells with state lists
     * can be locked.
     */
    public synchronized void setLockId(BlockPos localPos, int lockId) {
        if (!entries.containsKey(localPos)) {
            throw new IllegalArgumentException("no cell at " + localPos + " — call put first");
        }
        if (lockId < 0) lockId = 0;
        if (lockId == 0) {
            lockIds.remove(localPos);
        } else {
            lockIds.put(localPos.immutable(), lockId);
        }
    }

    /**
     * Snapshot of every {@code (localPos, lockId)} pair with {@code lockId > 0}.
     * Returned map is a defensive copy — callers may iterate freely.
     */
    public synchronized Map<BlockPos, Integer> allLockIds() {
        return new LinkedHashMap<>(lockIds);
    }

    /** Positions in this sidecar that share the given lock-id. Empty for {@code lockId == 0}. */
    public synchronized java.util.Set<BlockPos> positionsWithLockId(int lockId) {
        if (lockId <= 0) return java.util.Set.of();
        java.util.Set<BlockPos> out = new java.util.LinkedHashSet<>();
        for (Map.Entry<BlockPos, Integer> e : lockIds.entrySet()) {
            if (e.getValue() == lockId) out.add(e.getKey());
        }
        return out;
    }

    /**
     * Smallest positive integer not currently used by any cell as a
     * lock-id. Used by the menu's Lock-cycle button to allocate a new
     * group id; the only way two cells end up with the same id is via
     * Copy / Paste of a clipboard item.
     */
    public synchronized int nextFreeLockId() {
        java.util.Set<Integer> used = new java.util.HashSet<>(lockIds.values());
        int n = 1;
        while (used.contains(n)) n++;
        return n;
    }

    /**
     * Deterministic pick at {@code localPos}. Seeds a fresh {@link Random}
     * with the world seed XOR'd through the carriage index and either the
     * local position (unlocked cells) or the cell's lock-id (locked cells).
     * Returns {@code null} if no entry.
     *
     * <p>Lock-group pick: when the cell has a non-zero lock-id, every
     * cell in the group hashes the same {@code (seed, carriageIndex,
     * lockId)} so they all draw the same index — combined with the
     * group's shared state list, every cell renders the same block.</p>
     *
     * <p>Weighted pick: respects each entry's {@link VariantState#weight}.
     * Determinism contract is unchanged — same inputs → same index.</p>
     */
    public VariantState resolve(BlockPos localPos, long worldSeed, int carriageIndex) {
        List<VariantState> states = entries.get(localPos);
        if (states == null || states.isEmpty()) return null;
        int lockId = lockIdAt(localPos);
        int idx;
        if (lockId > 0) {
            int[] weights = new int[states.size()];
            for (int i = 0; i < states.size(); i++) weights[i] = states.get(i).weight();
            idx = pickIndexFromLockGroup(lockId, worldSeed, carriageIndex, weights);
        } else {
            idx = pickIndexWeighted(localPos, worldSeed, carriageIndex, states);
        }
        return states.get(idx);
    }

    /**
     * Uniform-distribution index picker (legacy / test surface). Kept for
     * the existing schema-version unit tests that operate on raw {@code size}
     * without constructing {@link VariantState} instances. New callers
     * should use {@link #pickIndexWeighted}.
     *
     * <p>Mirrors {@code CarriageTemplate.seededPick}'s golden-ratio mix,
     * with an additional {@code 0xBF58476D1CE4E5B9L} multiplier folding the
     * block position so two adjacent positions at the same carriage index
     * aren't correlated.</p>
     */
    public static int pickIndex(BlockPos localPos, long worldSeed, int carriageIndex, int size) {
        if (size <= 0) throw new IllegalArgumentException("size must be > 0");
        return seededRandom(localPos, worldSeed, carriageIndex).nextInt(size);
    }

    /**
     * Weighted picker over a list of {@link VariantState}. Materialises
     * weights to an {@code int[]} and delegates to
     * {@link #pickIndexFromWeights} so the picker core stays unit-testable
     * without a Forge/MC bootstrap.
     *
     * <p>Caller responsibility: the {@code states} list must be non-empty
     * and contain no {@link VariantState#locked} entries (otherwise the
     * caller should short-circuit; weights on locked entries don't matter).</p>
     */
    public static int pickIndexWeighted(BlockPos localPos, long worldSeed, int carriageIndex, List<VariantState> states) {
        if (states.isEmpty()) throw new IllegalArgumentException("states must be non-empty");
        int[] weights = new int[states.size()];
        for (int i = 0; i < states.size(); i++) weights[i] = states.get(i).weight();
        return pickIndexFromWeights(localPos, worldSeed, carriageIndex, weights);
    }

    /**
     * Pure-data weighted picker — testable without constructing real
     * {@link VariantState} (and therefore real {@link net.minecraft.world.level.block.state.BlockState})
     * instances. Sums the weights, draws a uniform {@code [0, total)} from
     * the deterministic seed mix, walks the array to find the bucket.
     *
     * <p>List order is stable (insertion order from the LinkedHashMap-backed
     * sidecar), so a given seed always selects the same bucket. Determinism
     * contract: same {@code (worldSeed, carriageIndex, localPos, weights)}
     * → same result across reloads.</p>
     */
    public static int pickIndexFromWeights(BlockPos localPos, long worldSeed, int carriageIndex, int[] weights) {
        if (weights.length == 0) throw new IllegalArgumentException("weights must be non-empty");
        int total = 0;
        for (int w : weights) {
            // Defensive clamp — VariantState's canonical constructor already
            // enforces ≥ 1, but external callers might pass raw values.
            if (w < 1) w = 1;
            total += w;
        }
        // Weights are clamped to [1, ~99] and entry counts are ≤ 32, so
        // total fits comfortably in int.
        int draw = seededRandom(localPos, worldSeed, carriageIndex).nextInt(total);
        int acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += Math.max(1, weights[i]);
            if (draw < acc) return i;
        }
        // Fallback for any rounding edge case — should never trigger because acc reaches total.
        return weights.length - 1;
    }

    /**
     * Same seed mix used by both pick paths so the uniform / weighted call
     * sites land on the same draw for backward-compat behaviour
     * verification: a v2 sidecar (no weights) re-loaded as v3 with all
     * weights=1 picks identically to the v2 path.
     */
    private static Random seededRandom(BlockPos localPos, long worldSeed, int carriageIndex) {
        long posHash = (((long) localPos.getX() * 31L + localPos.getY()) * 31L + localPos.getZ());
        long seed = worldSeed
            ^ ((long) carriageIndex * 0x9E3779B97F4A7C15L)
            ^ (posHash * 0xBF58476D1CE4E5B9L);
        return new Random(seed);
    }

    /**
     * Lock-group variant of {@link #pickIndexFromWeights}: hashes the
     * lock-id where the unlocked path hashes the local position. Every
     * cell that shares {@code lockId} therefore draws the same bucket,
     * which is exactly the "all locked cells render the same block at
     * spawn" contract.
     */
    public static int pickIndexFromLockGroup(int lockId, long worldSeed, int carriageIndex, int[] weights) {
        if (weights.length == 0) throw new IllegalArgumentException("weights must be non-empty");
        if (lockId <= 0) throw new IllegalArgumentException("lockId must be > 0");
        int total = 0;
        for (int w : weights) {
            if (w < 1) w = 1;
            total += w;
        }
        long seed = worldSeed
            ^ ((long) carriageIndex * 0x9E3779B97F4A7C15L)
            ^ ((long) lockId * 0xBF58476D1CE4E5B9L);
        int draw = new Random(seed).nextInt(total);
        int acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += Math.max(1, weights[i]);
            if (draw < acc) return i;
        }
        return weights.length - 1;
    }

    /**
     * Persist this sidecar to {@code <config>/dungeontrain/templates/<id>.variants.json}.
     * If the map is empty, any existing file is deleted so empty variants don't
     * leave stale sidecars on disk.
     */
    public synchronized void save(CarriageVariant variant) throws IOException {
        Path file = configPathFor(variant);
        if (entries.isEmpty()) {
            Files.deleteIfExists(file);
            CACHE.put(variant.id(), this);
            LOGGER.info("[DungeonTrain] Cleared variant sidecar for {} (no entries)", variant.id());
            return;
        }
        Files.createDirectories(file.getParent());
        String json = toJson();
        Files.writeString(file, json, StandardCharsets.UTF_8);
        CACHE.put(variant.id(), this);
        LOGGER.info("[DungeonTrain] Saved {} variant entries for {} to {}", entries.size(), variant.id(), file);
    }

    /**
     * Dev-mode write-through: copy the sidecar into the project source tree
     * so it ships with the next build. Mirrors
     * {@link CarriageTemplateStore#saveToSource}.
     */
    public synchronized void saveToSource(CarriageType type) throws IOException {
        Path file = sourcePathFor(type);
        if (entries.isEmpty()) {
            Files.deleteIfExists(file);
            LOGGER.info("[DungeonTrain] Cleared bundled variant sidecar for {} (no entries)", type);
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, toJson(), StandardCharsets.UTF_8);
        LOGGER.info("[DungeonTrain] Wrote bundled variant sidecar for {} to {}", type, file);
    }

    /** Delete both the config-dir sidecar and invalidate cache. Used by the editor reset path. */
    public static synchronized boolean delete(CarriageVariant variant) throws IOException {
        Path file = configPathFor(variant);
        boolean existed = Files.deleteIfExists(file);
        CACHE.remove(variant.id());
        if (existed) LOGGER.info("[DungeonTrain] Deleted variant sidecar for {} ({})", variant.id(), file);
        return existed;
    }

    /** Rename the config-dir sidecar from {@code sourceId} to {@code targetId}. No-op if source missing. */
    public static synchronized boolean rename(String sourceId, String targetId) throws IOException {
        Path src = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
            .resolve(SUBDIR).resolve(sourceId + EXT);
        Path dst = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
            .resolve(SUBDIR).resolve(targetId + EXT);
        if (!Files.isRegularFile(src)) return false;
        Files.move(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        CarriageVariantBlocks cached = CACHE.remove(sourceId);
        if (cached != null) CACHE.put(targetId, cached);
        LOGGER.info("[DungeonTrain] Renamed variant sidecar {} -> {}", src, dst);
        return true;
    }

    /** Invalidate the per-variant cache entry so the next {@link #loadFor} re-reads disk. */
    public static synchronized void invalidate(String variantId) {
        CACHE.remove(variantId);
    }

    /** Drop all cached sidecars — wired to {@code ServerStoppedEvent} by the template store. */
    public static synchronized void clearCache() {
        CACHE.clear();
    }

    /**
     * Deterministic JSON serialisation — entries ordered by insertion
     * (LinkedHashMap), states in the order the editor recorded them. The
     * hand-rolled writer produces indented output that a human can diff,
     * without pulling in {@code GsonBuilder} for a tiny file.
     *
     * <p>Each candidate writes as a bare BlockState string when it has no
     * BlockEntity NBT (v1-compatible), or as {@code {"state": ..., "nbt": ...}}
     * when NBT is present.</p>
     */
    private String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"schemaVersion\": ").append(CURRENT_SCHEMA_VERSION).append(",\n");
        sb.append("  \"variants\": {");
        boolean first = true;
        for (Map.Entry<BlockPos, List<VariantState>> e : entries.entrySet()) {
            if (!first) sb.append(",");
            int lockId = lockIds.getOrDefault(e.getKey(), 0);
            sb.append("\n    \"").append(formatPos(e.getKey())).append("\": ");
            if (lockId > 0) {
                // Cell-object form for v4 locked cells.
                sb.append("{ \"lockId\": ").append(lockId).append(", \"states\": [");
                appendStateArray(sb, e.getValue());
                sb.append("] }");
            } else {
                // Bare-array form (v3-shape) for unlocked cells — keeps
                // pre-v4 sidecars diff-clean on a no-op resave.
                sb.append("[");
                appendStateArray(sb, e.getValue());
                sb.append("]");
            }
            first = false;
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
    }

    private static void appendStateArray(StringBuilder sb, List<VariantState> states) {
        boolean firstState = true;
        for (VariantState s : states) {
            if (!firstState) sb.append(", ");
            appendVariantJson(sb, s);
            firstState = false;
        }
    }

    /**
     * Parsed cell — what {@link #parseCellValue} returns. {@code states}
     * is the candidate list, {@code lockId} is the v4 cell-level lock-id
     * (0 when unlocked or for v1/v2/v3 array-form cells).
     */
    public record ParsedCell(List<VariantState> states, int lockId) {}

    /**
     * Parse a {@code "x,y,z" → value} JSON entry into a {@link ParsedCell}.
     * Accepts both forms:
     * <ul>
     *   <li>v3 / pre-v4 — bare array of state elements; {@code lockId = 0}.</li>
     *   <li>v4 — object {@code {"lockId":N, "states":[...]}}; lockId is read
     *       from the object (≥0; negatives clamped to 0).</li>
     * </ul>
     * Returns {@code null} when the value is malformed (caller logs).
     * Used by all four block-variant sidecars
     * ({@link CarriageVariantBlocks}, {@link CarriagePartVariantBlocks},
     * {@link CarriageContentsVariantBlocks},
     * {@code TrackVariantBlocks}).
     */
    public static ParsedCell parseCellValue(JsonElement value,
                                            HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> blocks,
                                            String contextId, BlockPos contextPos) {
        JsonArray arr;
        int lockId = 0;
        if (value.isJsonArray()) {
            arr = value.getAsJsonArray();
        } else if (value.isJsonObject()) {
            JsonObject cellObj = value.getAsJsonObject();
            if (!cellObj.has("states") || !cellObj.get("states").isJsonArray()) {
                LOGGER.warn("[DungeonTrain] Variant sidecar {}: cell at {} missing 'states' array, skipping.",
                    contextId, contextPos);
                return null;
            }
            arr = cellObj.getAsJsonArray("states");
            if (cellObj.has("lockId") && cellObj.get("lockId").isJsonPrimitive()
                && cellObj.get("lockId").getAsJsonPrimitive().isNumber()) {
                int raw = cellObj.get("lockId").getAsInt();
                lockId = raw < 0 ? 0 : raw;
            }
        } else {
            LOGGER.warn("[DungeonTrain] Variant sidecar {}: value for {} is neither array nor object, skipping.",
                contextId, contextPos);
            return null;
        }
        List<VariantState> states = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            VariantState parsed = parseVariantElement(el, blocks, contextId, contextPos);
            if (parsed != null) states.add(parsed);
        }
        return new ParsedCell(states, lockId);
    }

    /**
     * Append a cell value to a JSON buffer in the form chosen by
     * {@code lockId}: bare-array (v3 shape) when {@code lockId == 0}, or
     * cell-object {@code {lockId, states}} when {@code lockId > 0}.
     * Used by all four sidecars to keep on-disk output identical.
     */
    public static void appendCellJson(StringBuilder sb, List<VariantState> states, int lockId) {
        if (lockId > 0) {
            sb.append("{ \"lockId\": ").append(lockId).append(", \"states\": [");
            boolean firstState = true;
            for (VariantState s : states) {
                if (!firstState) sb.append(", ");
                appendVariantJson(sb, s);
                firstState = false;
            }
            sb.append("] }");
        } else {
            sb.append("[");
            boolean firstState = true;
            for (VariantState s : states) {
                if (!firstState) sb.append(", ");
                appendVariantJson(sb, s);
                firstState = false;
            }
            sb.append("]");
        }
    }

    public static void appendVariantJson(StringBuilder sb, VariantState s) {
        String stateStr = BlockStateParser.serialize(s.state());
        if (s.isPlainBareString()) {
            sb.append('"').append(escapeJson(stateStr)).append('"');
            return;
        }
        // Object form — emit only the fields that differ from the default
        // (state always; nbt / weight / rotation only when set) so v1/v2
        // entries that gain a non-default value still produce the smallest
        // possible diff.
        sb.append("{\"state\": \"").append(escapeJson(stateStr)).append("\"");
        if (s.hasBlockEntityData()) {
            // CompoundTag.toString() returns the canonical SNBT representation —
            // round-trips with TagParser.parseTag on the read side.
            sb.append(", \"nbt\": \"").append(escapeJson(s.blockEntityNbt().toString())).append("\"");
        }
        if (s.weight() != 1) {
            sb.append(", \"weight\": ").append(s.weight());
        }
        if (!s.rotation().isDefault()) {
            sb.append(", \"rotation\": ");
            appendRotationJson(sb, s.rotation());
        }
        sb.append("}");
    }

    /**
     * Parse a v5 {@code "rotation"} element ({@code {"mode": "...", "dirs": ["..."]}}).
     * Returns {@link VariantRotation#NONE} if the element is missing, null,
     * or malformed. The {@link VariantRotation} canonical constructor
     * silently re-clamps malformed mode/mask combinations.
     */
    private static VariantRotation parseRotation(JsonElement el, String contextId, BlockPos contextPos) {
        if (el == null || !el.isJsonObject()) return VariantRotation.NONE;
        JsonObject obj = el.getAsJsonObject();
        VariantRotation.Mode mode = VariantRotation.Mode.RANDOM;
        if (obj.has("mode") && obj.get("mode").isJsonPrimitive()) {
            String raw = obj.get("mode").getAsString();
            try {
                mode = VariantRotation.Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: unknown rotation mode '{}', defaulting to random.",
                    contextId, contextPos, raw);
            }
        }
        int dirMask = 0;
        if (obj.has("dirs") && obj.get("dirs").isJsonArray()) {
            for (JsonElement d : obj.getAsJsonArray("dirs")) {
                if (!d.isJsonPrimitive()) continue;
                String name = d.getAsString().trim().toUpperCase(Locale.ROOT);
                try {
                    net.minecraft.core.Direction dir = net.minecraft.core.Direction.valueOf(name);
                    dirMask |= VariantRotation.maskOf(dir);
                } catch (IllegalArgumentException ignored) {
                    LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: unknown rotation dir '{}', skipping.",
                        contextId, contextPos, name);
                }
            }
        }
        return new VariantRotation(mode, dirMask);
    }

    /**
     * Serialise a {@link VariantRotation} to the v5 JSON shape. Direction
     * names are written lowercase for human readability; the parser is
     * case-insensitive.
     */
    private static void appendRotationJson(StringBuilder sb, VariantRotation rot) {
        sb.append("{\"mode\": \"").append(rot.mode().name().toLowerCase(Locale.ROOT)).append("\"");
        if (rot.dirMask() != 0) {
            sb.append(", \"dirs\": [");
            boolean first = true;
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                if ((rot.dirMask() & VariantRotation.maskOf(d)) == 0) continue;
                if (!first) sb.append(", ");
                sb.append('"').append(d.name().toLowerCase(Locale.ROOT)).append('"');
                first = false;
            }
            sb.append("]");
        }
        sb.append("}");
    }

    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
