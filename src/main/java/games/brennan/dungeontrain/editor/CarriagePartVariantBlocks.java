package games.brennan.dungeontrain.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-part sidecar of {@code localPos → [blockState, ...]} candidate lists —
 * the part template's local analogue of {@link CarriageVariantBlocks}. Enables
 * {@code shift-right-click} variant authoring inside part editor plots and
 * deterministic per-carriage-index randomisation when
 * {@link games.brennan.dungeontrain.train.CarriagePartTemplate#placeAt}
 * stamps the part at spawn time.
 *
 * <p>Storage: {@code config/dungeontrain/parts/<kind>/<name>.variants.json}
 * alongside the part NBT. Schema mirrors {@link CarriageVariantBlocks} (v2 —
 * candidates can be bare BlockState strings or {@code {state, nbt?}} objects
 * carrying SNBT for block-entity payloads). The
 * {@link CarriageVariantBlocks#isEmptyPlaceholder} sentinel (command-block
 * states) and {@link CarriageVariantBlocks#pickIndex} deterministic mixer are
 * shared — a part's random pick seeds on the same
 * {@code (worldSeed, carriageIndex, localPos)} basis so a parts-backed
 * carriage's rolling-window re-render stays stable.</p>
 *
 * <p>Local coordinates are clamped to the part's footprint
 * {@code kind.dims(worldDims)} rather than the whole carriage shell — walls
 * only have Y and X to play with, doors only X and Z, etc.</p>
 */
public final class CarriagePartVariantBlocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int CURRENT_SCHEMA_VERSION = CarriageVariantBlocks.CURRENT_SCHEMA_VERSION;
    public static final int MIN_STATES_PER_ENTRY = CarriageVariantBlocks.MIN_STATES_PER_ENTRY;

    private static final String SUBDIR_BASE = "dungeontrain/parts";
    private static final String EXT = ".variants.json";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/parts/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/parts";

    /** Session cache keyed on {@code <kind>:<name>}. Invalidated on save and on editor enter. */
    private static final Map<String, CarriagePartVariantBlocks> CACHE = new HashMap<>();

    private final Map<BlockPos, List<VariantState>> entries;

    /** pos → lock-id (≥1 = locked, 0/missing = unlocked). See {@link CarriageVariantBlocks#lockIdAt}. */
    private final Map<BlockPos, Integer> lockIds;

    private CarriagePartVariantBlocks(Map<BlockPos, List<VariantState>> entries, Map<BlockPos, Integer> lockIds) {
        this.entries = entries;
        this.lockIds = lockIds;
    }

    public static CarriagePartVariantBlocks empty() {
        return new CarriagePartVariantBlocks(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    public static Path configPathFor(CarriagePartKind kind, String name) {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR_BASE).resolve(kind.id()).resolve(name + EXT);
    }

    public static String bundledResourceFor(CarriagePartKind kind, String name) {
        return RESOURCE_PREFIX + kind.id() + "/" + name + EXT;
    }

    private static String cacheKey(CarriagePartKind kind, String name) {
        return kind.id() + ":" + name;
    }

    /**
     * Load the sidecar for {@code (kind, name)} — config dir first, then
     * bundled resource. Returns {@link #empty} if neither exists. Entries
     * outside the {@code partSize} footprint (the part's own
     * {@link CarriagePartKind#dims}) are dropped with a warning.
     */
    public static synchronized CarriagePartVariantBlocks loadFor(CarriagePartKind kind, String name, Vec3i partSize) {
        String key = cacheKey(kind, name);
        CarriagePartVariantBlocks cached = CACHE.get(key);
        if (cached != null) return cached;
        CarriagePartVariantBlocks loaded = loadFromDisk(kind, name, partSize);
        CACHE.put(key, loaded);
        return loaded;
    }

    private static CarriagePartVariantBlocks loadFromDisk(CarriagePartKind kind, String name, Vec3i partSize) {
        Path cfg = configPathFor(kind, name);
        if (Files.isRegularFile(cfg)) {
            try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
                return parse(r, kind, name, "config " + cfg, partSize);
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Failed to read part variant sidecar {}: {}", cfg, e.toString());
            }
        }
        String resource = bundledResourceFor(kind, name);
        try (InputStream in = CarriagePartVariantBlocks.class.getResourceAsStream(resource)) {
            if (in == null) return empty();
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(r, kind, name, "bundled " + resource, partSize);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled part variant sidecar {}: {}", resource, e.toString());
            return empty();
        }
    }

    private static CarriagePartVariantBlocks parse(Reader reader, CarriagePartKind kind, String name,
                                                    String origin, Vec3i partSize) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            LOGGER.warn("[DungeonTrain] Part variant sidecar {}:{} ({}) is not a JSON object — ignoring.",
                kind.id(), name, origin);
            return empty();
        }
        JsonObject obj = root.getAsJsonObject();
        if (obj.has("schemaVersion")) {
            int v = obj.get("schemaVersion").getAsInt();
            if (v > CURRENT_SCHEMA_VERSION) {
                LOGGER.warn("[DungeonTrain] Part variant sidecar {}:{} ({}) schemaVersion {} (newer than {}) — best-effort parse.",
                    kind.id(), name, origin, v, CURRENT_SCHEMA_VERSION);
            }
        }
        if (!obj.has("variants") || !obj.get("variants").isJsonObject()) return empty();

        HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        JsonObject variants = obj.getAsJsonObject("variants");
        Map<BlockPos, List<VariantState>> out = new LinkedHashMap<>();
        Map<BlockPos, Integer> outLocks = new LinkedHashMap<>();
        String contextId = kind.id() + ":" + name;
        for (Map.Entry<String, JsonElement> field : variants.entrySet()) {
            BlockPos pos = parsePos(field.getKey());
            if (pos == null) {
                LOGGER.warn("[DungeonTrain] Part variant sidecar {}: bad pos '{}', skipping.",
                    contextId, field.getKey());
                continue;
            }
            if (!inBounds(pos, partSize)) {
                LOGGER.warn("[DungeonTrain] Part variant sidecar {}: pos {} outside part footprint {}x{}x{}, skipping.",
                    contextId, pos, partSize.getX(), partSize.getY(), partSize.getZ());
                continue;
            }
            CarriageVariantBlocks.ParsedCell cell = CarriageVariantBlocks.parseCellValue(
                field.getValue(), blocks, contextId, pos);
            if (cell == null) continue;
            if (cell.states().size() < MIN_STATES_PER_ENTRY) {
                LOGGER.warn("[DungeonTrain] Part variant sidecar {} pos {}: fewer than {} valid states, dropped.",
                    contextId, pos, MIN_STATES_PER_ENTRY);
                continue;
            }
            BlockPos posI = pos.immutable();
            out.put(posI, List.copyOf(cell.states()));
            if (cell.lockId() > 0) outLocks.put(posI, cell.lockId());
        }
        LOGGER.info("[DungeonTrain] Loaded {} part variant entries for {} from {}",
            out.size(), contextId, origin);
        return new CarriagePartVariantBlocks(out, outLocks);
    }

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

    private static boolean inBounds(BlockPos p, Vec3i size) {
        return p.getX() >= 0 && p.getX() < size.getX()
            && p.getY() >= 0 && p.getY() < size.getY()
            && p.getZ() >= 0 && p.getZ() < size.getZ();
    }

    static String formatPos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    public List<CarriageVariantBlocks.Entry> entries() {
        List<CarriageVariantBlocks.Entry> out = new ArrayList<>(entries.size());
        for (Map.Entry<BlockPos, List<VariantState>> e : entries.entrySet()) {
            out.add(new CarriageVariantBlocks.Entry(e.getKey(), e.getValue()));
        }
        return Collections.unmodifiableList(out);
    }

    public boolean isEmpty() { return entries.isEmpty(); }

    public int size() { return entries.size(); }

    /** Candidate variants at {@code localPos}, or {@code null} if no entry. */
    public List<VariantState> statesAt(BlockPos localPos) {
        return entries.get(localPos);
    }

    /** Replace the candidate list at {@code localPos}. v2 supports block-entity states with optional NBT. */
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
     * no cell exists at {@code localPos}.
     */
    public synchronized void setLockId(BlockPos localPos, int lockId) {
        if (!entries.containsKey(localPos)) {
            throw new IllegalArgumentException("no cell at " + localPos + " — call put first");
        }
        if (lockId < 0) lockId = 0;
        if (lockId == 0) lockIds.remove(localPos);
        else lockIds.put(localPos.immutable(), lockId);
    }

    /** Positions sharing the given lock-id. Empty for {@code lockId == 0}. */
    public synchronized java.util.Set<BlockPos> positionsWithLockId(int lockId) {
        if (lockId <= 0) return java.util.Set.of();
        java.util.Set<BlockPos> out = new java.util.LinkedHashSet<>();
        for (Map.Entry<BlockPos, Integer> e : lockIds.entrySet()) {
            if (e.getValue() == lockId) out.add(e.getKey());
        }
        return out;
    }

    /** Snapshot of every {@code (localPos, lockId)} pair with {@code lockId > 0}. Defensive copy. */
    public synchronized Map<BlockPos, Integer> allLockIds() {
        return new LinkedHashMap<>(lockIds);
    }

    /** Smallest positive integer not currently used as a lock-id. */
    public synchronized int nextFreeLockId() {
        java.util.Set<Integer> used = new java.util.HashSet<>(lockIds.values());
        int n = 1;
        while (used.contains(n)) n++;
        return n;
    }

    /** Deterministic pick — same {@code (worldSeed, carriageIndex, localPos|lockId)} → same state across reloads. */
    public VariantState resolve(BlockPos localPos, long worldSeed, int carriageIndex) {
        List<VariantState> states = entries.get(localPos);
        if (states == null || states.isEmpty()) return null;
        int lockId = lockIdAt(localPos);
        int idx;
        if (lockId > 0) {
            int[] weights = new int[states.size()];
            for (int i = 0; i < states.size(); i++) weights[i] = states.get(i).weight();
            idx = CarriageVariantBlocks.pickIndexFromLockGroup(lockId, worldSeed, carriageIndex, weights);
        } else {
            idx = CarriageVariantBlocks.pickIndexWeighted(localPos, worldSeed, carriageIndex, states);
        }
        return states.get(idx);
    }

    public synchronized void save(CarriagePartKind kind, String name) throws IOException {
        Path file = configPathFor(kind, name);
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(toJsonText());
        }
        CACHE.put(cacheKey(kind, name), this);
        LOGGER.info("[DungeonTrain] Saved part variant sidecar for {}:{} ({} entries) to {}",
            kind.id(), name, entries.size(), file);
    }

    /**
     * Dev-mode write-through: copy the sidecar into the project source tree
     * so it ships with the next build. Mirrors
     * {@link CarriageContentsVariantBlocks#saveToSource}. An empty sidecar
     * deletes the source file so removing every entry doesn't leave a stale
     * bundled resource.
     */
    public synchronized void saveToSource(CarriagePartKind kind, String name) throws IOException {
        Path file = sourcePathFor(kind, name);
        if (file == null) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        if (entries.isEmpty()) {
            Files.deleteIfExists(file);
            LOGGER.info("[DungeonTrain] Cleared bundled part variant sidecar for {}:{} (no entries)",
                kind.id(), name);
            return;
        }
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(toJsonText());
        }
        LOGGER.info("[DungeonTrain] Wrote bundled part variant sidecar for {}:{} to {}",
            kind.id(), name, file);
    }

    private String toJsonText() {
        // Hand-written so the v2 mixed-array form (bare strings + objects) stays
        // diff-clean against existing v1 files. Same shape as CarriageVariantBlocks#toJson.
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"schemaVersion\": ").append(CURRENT_SCHEMA_VERSION).append(",\n");
        sb.append("  \"variants\": {");
        boolean first = true;
        for (Map.Entry<BlockPos, List<VariantState>> e : entries.entrySet()) {
            if (!first) sb.append(",");
            int lockId = lockIds.getOrDefault(e.getKey(), 0);
            sb.append("\n    \"").append(formatPos(e.getKey())).append("\": ");
            CarriageVariantBlocks.appendCellJson(sb, e.getValue(), lockId);
            first = false;
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
    }

    /**
     * Resolves the source-tree path for {@code (kind, name)}. Returns null
     * when the source tree isn't present or writable (production install) so
     * callers can soft-fail. Mirrors
     * {@link CarriageContentsVariantBlocks#sourcePathFor}.
     */
    public static Path sourcePathFor(CarriagePartKind kind, String name) {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        if (projectRoot == null) return null;
        Path resources = projectRoot.resolve("src/main/resources");
        if (!Files.isDirectory(resources) || !Files.isWritable(resources)) return null;
        return projectRoot.resolve(SOURCE_REL_PATH).resolve(kind.id()).resolve(name + EXT);
    }

    public static synchronized boolean delete(CarriagePartKind kind, String name) throws IOException {
        Path file = configPathFor(kind, name);
        boolean existed = Files.deleteIfExists(file);
        CACHE.remove(cacheKey(kind, name));
        if (existed) LOGGER.info("[DungeonTrain] Deleted part variant sidecar {}:{} ({})", kind.id(), name, file);
        return existed;
    }

    public static synchronized void invalidate(CarriagePartKind kind, String name) {
        CACHE.remove(cacheKey(kind, name));
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }
}
