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
 * <p>v3 schema (current):
 * <pre>
 * {
 *   "schemaVersion": 3,
 *   "variants": {
 *     "3,1,2": [
 *       "minecraft:stone",
 *       { "state": "minecraft:bookshelf" },
 *       { "state": "minecraft:chest[facing=west]", "nbt": "{Items:[...]}" },
 *       { "state": "minecraft:cobblestone", "weight": 3 },
 *       { "state": "minecraft:gold_block", "locked": true }
 *     ]
 *   }
 * }
 * </pre>
 * Keys use local coords (same basis as {@code StructureTemplate} block infos).
 * Each candidate is either a bare BlockState string (no NBT, weight 1, not
 * locked — identical to v1) or a {@code {state, nbt?, weight?, locked?}}
 * object. v1 / v2 files load unchanged; the v3 writer emits objects only
 * when one of {NBT, non-default weight, locked} is set, so older files
 * round-trip diff-clean as bare strings.</p>
 */
public final class CarriageVariantBlocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Current schema version written by {@link #save}.
     * <ul>
     *   <li>v1 — bare BlockState strings.</li>
     *   <li>v2 — adds optional per-entry BlockEntity NBT.</li>
     *   <li>v3 — adds optional per-entry {@code weight} (≥1, default 1)
     *       and {@code locked} (default false). Older files load with the
     *       defaults and round-trip diff-clean since the writer only emits
     *       object form when one of {NBT, non-default weight, locked} is
     *       set.</li>
     * </ul>
     */
    public static final int CURRENT_SCHEMA_VERSION = 3;

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

    private CarriageVariantBlocks(Map<BlockPos, List<VariantState>> entries) {
        this.entries = entries;
    }

    public static CarriageVariantBlocks empty() {
        return new CarriageVariantBlocks(new LinkedHashMap<>());
    }

    /** On-disk path for the config-dir sidecar matching {@code variant}. */
    public static Path configPathFor(CarriageVariant variant) {
        return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve(SUBDIR).resolve(variant.id() + EXT);
    }

    /** Classpath resource for the bundled sidecar matching {@code variant} (only exists for shipped variants). */
    public static String bundledResourceFor(CarriageVariant variant) {
        return RESOURCE_PREFIX + variant.id() + EXT;
    }

    /** Source-tree path for the bundled sidecar — only writable in a {@code ./gradlew runClient} dev checkout. */
    public static Path sourcePathFor(CarriageType type) {
        Path gameDir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get();
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
            if (!field.getValue().isJsonArray()) {
                LOGGER.warn("[DungeonTrain] Variant sidecar {}: value for {} is not an array, skipping.", id, pos);
                continue;
            }
            JsonArray arr = field.getValue().getAsJsonArray();
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
            out.put(pos.immutable(), List.copyOf(states));
        }
        LOGGER.info("[DungeonTrain] Loaded {} variant entries for {} from {}", out.size(), id, origin);
        return new CarriageVariantBlocks(out);
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
            boolean locked = false;
            if (obj.has("locked") && obj.get("locked").isJsonPrimitive()
                && obj.get("locked").getAsJsonPrimitive().isBoolean()) {
                locked = obj.get("locked").getAsBoolean();
            }
            return new VariantState(base.state(), nbt, weight, locked);
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
        return entries.remove(localPos) != null;
    }

    /**
     * Deterministic pick at {@code localPos}. Seeds a fresh {@link Random}
     * with the world seed XOR'd through the carriage index and the local
     * position — same world + same carriage + same pos → same result across
     * reloads. Returns {@code null} if no entry.
     *
     * <p>Lock short-circuit: if any entry in the cell has
     * {@link VariantState#locked} = true, the picker bypasses the random
     * roll and returns the first locked entry. Multiple locked entries are
     * permitted but only the first one wins — list order is the
     * tiebreaker, matching the JSON's insertion order.</p>
     *
     * <p>Weighted pick: when no entry is locked, the pick respects each
     * entry's {@link VariantState#weight}. An entry with weight=2 is twice
     * as likely as one with weight=1 to be chosen. Determinism contract is
     * unchanged — {@code (seed, carriageIndex, localPos)} → same draw, then
     * the same draw maps to the same bucket since weights are stable.</p>
     */
    public VariantState resolve(BlockPos localPos, long worldSeed, int carriageIndex) {
        List<VariantState> states = entries.get(localPos);
        if (states == null || states.isEmpty()) return null;
        for (VariantState s : states) {
            if (s.locked()) return s;
        }
        int idx = pickIndexWeighted(localPos, worldSeed, carriageIndex, states);
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
        Path src = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
            .resolve(SUBDIR).resolve(sourceId + EXT);
        Path dst = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
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
            sb.append("\n    \"").append(formatPos(e.getKey())).append("\": [");
            boolean firstState = true;
            for (VariantState s : e.getValue()) {
                if (!firstState) sb.append(", ");
                appendVariantJson(sb, s);
                firstState = false;
            }
            sb.append("]");
            first = false;
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
    }

    public static void appendVariantJson(StringBuilder sb, VariantState s) {
        String stateStr = BlockStateParser.serialize(s.state());
        if (s.isPlainBareString()) {
            sb.append('"').append(escapeJson(stateStr)).append('"');
            return;
        }
        // Object form — emit only the fields that differ from the default
        // (state always; nbt / weight / locked only when set) so v1/v2
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
        if (s.locked()) {
            sb.append(", \"locked\": true");
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
