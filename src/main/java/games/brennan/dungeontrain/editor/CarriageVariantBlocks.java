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
 * <p>v1 schema:
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "variants": {
 *     "3,1,2": ["minecraft:stone", "minecraft:oak_planks"],
 *     "4,1,2": ["minecraft:stone", "minecraft:oak_planks[variant=...]"]
 *   }
 * }
 * </pre>
 * Keys use local coords (same basis as {@code StructureTemplate} block infos).
 * Values use the standard {@code BlockStateParser} block-state string syntax
 * so properties round-trip.</p>
 */
public final class CarriageVariantBlocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Current schema version written by {@link #save}. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final String SUBDIR = "dungeontrain/templates";
    private static final String EXT = ".variants.json";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/templates/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/templates";

    /** Minimum candidates per entry — a single-state "variant" is just a fixed block. */
    public static final int MIN_STATES_PER_ENTRY = 2;

    // Session cache keyed by variant id. Invalidated on save and on editor
    // enter (re-read from disk so a manual edit picks up immediately).
    private static final Map<String, CarriageVariantBlocks> CACHE = new HashMap<>();

    /** Local position + candidate states. Ordering of {@code states} is preserved as written. */
    public record Entry(BlockPos localPos, List<BlockState> states) {
        public Entry {
            if (localPos == null) throw new IllegalArgumentException("localPos");
            if (states == null || states.size() < MIN_STATES_PER_ENTRY) {
                throw new IllegalArgumentException(
                    "entry at " + localPos + " must list at least " + MIN_STATES_PER_ENTRY + " states");
            }
            for (BlockState s : states) {
                if (s == null) throw new IllegalArgumentException("null state in entry " + localPos);
            }
            states = List.copyOf(states);
        }
    }

    /** pos → candidate states. {@link LinkedHashMap} keeps insertion order for deterministic JSON output. */
    private final Map<BlockPos, List<BlockState>> entries;

    private CarriageVariantBlocks(Map<BlockPos, List<BlockState>> entries) {
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
            if (v != CURRENT_SCHEMA_VERSION) {
                LOGGER.warn("[DungeonTrain] Variant sidecar {} ({}) has schemaVersion {} (expected {}) — attempting best-effort parse.",
                    id, origin, v, CURRENT_SCHEMA_VERSION);
            }
        }
        if (!obj.has("variants") || !obj.get("variants").isJsonObject()) {
            return empty();
        }
        HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> blocks =
            BuiltInRegistries.BLOCK.asLookup();

        JsonObject variants = obj.getAsJsonObject("variants");
        Map<BlockPos, List<BlockState>> out = new LinkedHashMap<>();
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
            List<BlockState> states = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                    LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: non-string entry {}, skipping.", id, pos, el);
                    continue;
                }
                try {
                    BlockStateParser.BlockResult parsed =
                        BlockStateParser.parseForBlock(blocks, el.getAsString(), false);
                    states.add(parsed.blockState());
                } catch (Exception e) {
                    LOGGER.warn("[DungeonTrain] Variant sidecar {} pos {}: could not parse '{}' ({}), skipping.",
                        id, pos, el.getAsString(), e.getMessage());
                }
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

    /** Package-private for test access — parses a {@code "x,y,z"} key back into a {@link BlockPos}, or null. */
    static BlockPos parsePos(String key) {
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
        for (Map.Entry<BlockPos, List<BlockState>> e : entries.entrySet()) {
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

    /** Candidate states at {@code localPos}, or {@code null} if no entry. */
    public List<BlockState> statesAt(BlockPos localPos) {
        return entries.get(localPos);
    }

    /**
     * Replace the candidate list at {@code localPos}. Requires
     * {@link #MIN_STATES_PER_ENTRY} or more states. Throws if any state
     * carries a {@link net.minecraft.world.level.block.entity.BlockEntity}
     * requirement — block-entity states need NBT that the sidecar does not
     * carry, and silent placement would drop that data silently. v1 rejects
     * them; future work can extend the format.
     */
    public synchronized void put(BlockPos localPos, List<BlockState> states) {
        if (states == null || states.size() < MIN_STATES_PER_ENTRY) {
            throw new IllegalArgumentException(
                "need at least " + MIN_STATES_PER_ENTRY + " states, got "
                    + (states == null ? 0 : states.size()));
        }
        for (BlockState s : states) {
            if (s == null) throw new IllegalArgumentException("null state");
            if (s.hasBlockEntity()) {
                throw new IllegalArgumentException(
                    "block-entity state " + s + " rejected — sidecar cannot preserve BE data");
            }
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
     */
    public BlockState resolve(BlockPos localPos, long worldSeed, int carriageIndex) {
        List<BlockState> states = entries.get(localPos);
        if (states == null || states.isEmpty()) return null;
        int idx = pickIndex(localPos, worldSeed, carriageIndex, states.size());
        return states.get(idx);
    }

    /**
     * Pure-function deterministic index picker used by {@link #resolve}.
     * Extracted so unit tests can exercise the determinism contract without
     * needing a Forge/MC bootstrap to construct real {@link BlockState}s.
     * Mirrors {@code CarriageTemplate.seededPick}'s golden-ratio mix, with
     * an additional {@code 0xBF58476D1CE4E5B9L} multiplier folding the block
     * position so two adjacent positions at the same carriage index aren't
     * correlated.
     */
    public static int pickIndex(BlockPos localPos, long worldSeed, int carriageIndex, int size) {
        if (size <= 0) throw new IllegalArgumentException("size must be > 0");
        long posHash = (((long) localPos.getX() * 31L + localPos.getY()) * 31L + localPos.getZ());
        long seed = worldSeed
            ^ ((long) carriageIndex * 0x9E3779B97F4A7C15L)
            ^ (posHash * 0xBF58476D1CE4E5B9L);
        return new Random(seed).nextInt(size);
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
     */
    private String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"schemaVersion\": ").append(CURRENT_SCHEMA_VERSION).append(",\n");
        sb.append("  \"variants\": {");
        boolean first = true;
        for (Map.Entry<BlockPos, List<BlockState>> e : entries.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\n    \"").append(formatPos(e.getKey())).append("\": [");
            boolean firstState = true;
            for (BlockState s : e.getValue()) {
                if (!firstState) sb.append(", ");
                sb.append('"').append(escapeJson(BlockStateParser.serialize(s))).append('"');
                firstState = false;
            }
            sb.append("]");
            first = false;
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
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
