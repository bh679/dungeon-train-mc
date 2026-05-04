package games.brennan.dungeontrain.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageContents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-contents sidecar of {@code localPos → [VariantState, ...]} candidate
 * lists — the interior-layout analogue of {@link CarriageVariantBlocks} and
 * {@link CarriagePartVariantBlocks}. Enables {@code shift-right-click} variant
 * authoring inside contents editor plots and deterministic per-carriage-index
 * randomisation when
 * {@link games.brennan.dungeontrain.train.CarriageContentsPlacer#placeAt(
 * net.minecraft.server.level.ServerLevel, BlockPos, CarriageContents,
 * games.brennan.dungeontrain.train.CarriageDims, long, int)} stamps the
 * contents at spawn time.
 *
 * <p>Storage: {@code config/dungeontrain/contents/<id>.variants.json} alongside
 * the contents NBT. Schema mirrors {@link CarriageVariantBlocks} (v2 —
 * candidates can be bare BlockState strings or {@code {state, nbt?}} objects).
 * The {@link CarriageVariantBlocks#isEmptyPlaceholder} sentinel and
 * {@link CarriageVariantBlocks#pickIndex} deterministic mixer are shared so a
 * contents pick matches the parts/shell determinism on the same
 * {@code (worldSeed, carriageIndex, localPos)} basis.</p>
 *
 * <p>Local coordinates are <b>interior-local</b> — the position is
 * {@code clicked - (carriageOrigin + (1,1,1))}, so x ∈ [0, length-2),
 * y ∈ [0, height-2), z ∈ [0, width-2). Anything outside the interior box is
 * dropped at load time.</p>
 */
public final class CarriageContentsVariantBlocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int CURRENT_SCHEMA_VERSION = CarriageVariantBlocks.CURRENT_SCHEMA_VERSION;
    public static final int MIN_STATES_PER_ENTRY = CarriageVariantBlocks.MIN_STATES_PER_ENTRY;

    private static final String SUBDIR = "dungeontrain/contents";
    private static final String EXT = ".variants.json";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/contents/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/contents";

    /** Session cache keyed on contents id. Invalidated on save and on registry unregister. */
    private static final Map<String, CarriageContentsVariantBlocks> CACHE = new HashMap<>();

    private final Map<BlockPos, List<VariantState>> entries;

    /** pos → lock-id (≥1 = locked, 0/missing = unlocked). See {@link CarriageVariantBlocks#lockIdAt}. */
    private final Map<BlockPos, Integer> lockIds;

    private CarriageContentsVariantBlocks(Map<BlockPos, List<VariantState>> entries, Map<BlockPos, Integer> lockIds) {
        this.entries = entries;
        this.lockIds = lockIds;
    }

    public static CarriageContentsVariantBlocks empty() {
        return new CarriageContentsVariantBlocks(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    public static Path configPathFor(CarriageContents contents) {
        return configPathForId(contents.id());
    }

    public static Path configPathForId(String id) {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR).resolve(id + EXT);
    }

    public static String bundledResourceFor(CarriageContents contents) {
        return RESOURCE_PREFIX + contents.id() + EXT;
    }

    /**
     * Load the sidecar for {@code contents} — config dir first, then bundled
     * resource. Returns {@link #empty} if neither exists. Entries outside the
     * {@code interiorSize} are dropped with a warning so a stale sidecar from
     * an earlier dims doesn't paint blocks into the shell.
     */
    public static synchronized CarriageContentsVariantBlocks loadFor(CarriageContents contents, Vec3i interiorSize) {
        String key = contents.id();
        CarriageContentsVariantBlocks cached = CACHE.get(key);
        if (cached != null) return cached;
        CarriageContentsVariantBlocks loaded = loadFromDisk(contents, interiorSize);
        CACHE.put(key, loaded);
        return loaded;
    }

    private static CarriageContentsVariantBlocks loadFromDisk(CarriageContents contents, Vec3i interiorSize) {
        Path cfg = configPathFor(contents);
        if (Files.isRegularFile(cfg)) {
            try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
                return parse(r, contents, "config " + cfg, interiorSize);
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Failed to read contents variant sidecar {}: {}", cfg, e.toString());
            }
        }
        String resource = bundledResourceFor(contents);
        try (InputStream in = CarriageContentsVariantBlocks.class.getResourceAsStream(resource)) {
            if (in == null) return empty();
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(r, contents, "bundled " + resource, interiorSize);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled contents variant sidecar {}: {}", resource, e.toString());
            return empty();
        }
    }

    private static CarriageContentsVariantBlocks parse(Reader reader, CarriageContents contents,
                                                        String origin, Vec3i interiorSize) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            LOGGER.warn("[DungeonTrain] Contents variant sidecar {} ({}) is not a JSON object — ignoring.",
                contents.id(), origin);
            return empty();
        }
        JsonObject obj = root.getAsJsonObject();
        if (obj.has("schemaVersion")) {
            int v = obj.get("schemaVersion").getAsInt();
            if (v > CURRENT_SCHEMA_VERSION) {
                LOGGER.warn("[DungeonTrain] Contents variant sidecar {} ({}) schemaVersion {} (newer than {}) — best-effort parse.",
                    contents.id(), origin, v, CURRENT_SCHEMA_VERSION);
            }
        }
        if (!obj.has("variants") || !obj.get("variants").isJsonObject()) return empty();

        HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        JsonObject variants = obj.getAsJsonObject("variants");
        Map<BlockPos, List<VariantState>> out = new LinkedHashMap<>();
        Map<BlockPos, Integer> outLocks = new LinkedHashMap<>();
        String contextId = contents.id();
        for (Map.Entry<String, JsonElement> field : variants.entrySet()) {
            BlockPos pos = parsePos(field.getKey());
            if (pos == null) {
                LOGGER.warn("[DungeonTrain] Contents variant sidecar {}: bad pos '{}', skipping.",
                    contextId, field.getKey());
                continue;
            }
            if (!inBounds(pos, interiorSize)) {
                LOGGER.warn("[DungeonTrain] Contents variant sidecar {}: pos {} outside interior {}x{}x{}, skipping.",
                    contextId, pos, interiorSize.getX(), interiorSize.getY(), interiorSize.getZ());
                continue;
            }
            CarriageVariantBlocks.ParsedCell cell = CarriageVariantBlocks.parseCellValue(
                field.getValue(), blocks, contextId, pos);
            if (cell == null) continue;
            if (cell.states().size() < MIN_STATES_PER_ENTRY) {
                LOGGER.warn("[DungeonTrain] Contents variant sidecar {} pos {}: fewer than {} valid states, dropped.",
                    contextId, pos, MIN_STATES_PER_ENTRY);
                continue;
            }
            BlockPos posI = pos.immutable();
            out.put(posI, List.copyOf(cell.states()));
            if (cell.lockId() > 0) outLocks.put(posI, cell.lockId());
        }
        LOGGER.info("[DungeonTrain] Loaded {} contents variant entries for {} from {}",
            out.size(), contextId, origin);
        return new CarriageContentsVariantBlocks(out, outLocks);
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

    public synchronized void setLockId(BlockPos localPos, int lockId) {
        if (!entries.containsKey(localPos)) {
            throw new IllegalArgumentException("no cell at " + localPos + " — call put first");
        }
        if (lockId < 0) lockId = 0;
        if (lockId == 0) lockIds.remove(localPos);
        else lockIds.put(localPos.immutable(), lockId);
    }

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

    public synchronized int nextFreeLockId() {
        java.util.Set<Integer> used = new java.util.HashSet<>(lockIds.values());
        int n = 1;
        while (used.contains(n)) n++;
        return n;
    }

    /** Deterministic pick — locked cells share a single roll across the group. */
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

    public synchronized void save(CarriageContents contents) throws IOException {
        Path file = configPathFor(contents);
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(toJsonText());
        }
        CACHE.put(contents.id(), this);
        LOGGER.info("[DungeonTrain] Saved contents variant sidecar for {} ({} entries) to {}",
            contents.id(), entries.size(), file);
    }

    /**
     * Write the sidecar to the source-tree resources directory so it ships
     * with the next build. Only meaningful in a dev checkout. Mirrors the
     * {@code saveToSource} behaviour on {@link CarriageContentsStore}.
     */
    public synchronized void saveToSource(CarriageContents contents) throws IOException {
        Path file = sourcePathFor(contents);
        if (file == null) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(toJsonText());
        }
        LOGGER.info("[DungeonTrain] Wrote bundled contents variant sidecar for {} to {}", contents.id(), file);
    }

    private String toJsonText() {
        // Hand-written to keep the v2 mixed-array form (bare strings + objects)
        // diff-clean against existing files. Same shape as
        // CarriagePartVariantBlocks#save / CarriageVariantBlocks#toJson.
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

    public static synchronized boolean delete(CarriageContents contents) throws IOException {
        Path file = configPathFor(contents);
        boolean existed = Files.deleteIfExists(file);
        CACHE.remove(contents.id());
        if (existed) LOGGER.info("[DungeonTrain] Deleted contents variant sidecar {} ({})", contents.id(), file);
        return existed;
    }

    /**
     * Move the sidecar file from {@code sourceId} to {@code targetId} so a
     * contents save-as / rename keeps its variants. Returns false if no file
     * existed at the source.
     */
    public static synchronized boolean rename(String sourceId, String targetId) throws IOException {
        Path src = configPathForId(sourceId);
        Path dst = configPathForId(targetId);
        if (!Files.isRegularFile(src)) {
            CACHE.remove(sourceId.toLowerCase(java.util.Locale.ROOT));
            return false;
        }
        Files.createDirectories(dst.getParent());
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        CarriageContentsVariantBlocks cached = CACHE.remove(sourceId);
        if (cached != null) CACHE.put(targetId, cached);
        LOGGER.info("[DungeonTrain] Renamed contents variant sidecar {} -> {}", src, dst);
        return true;
    }

    public static synchronized void invalidate(String id) {
        CACHE.remove(id);
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    public static Path sourcePathFor(CarriageContents contents) {
        return sourcePathForId(contents.id());
    }

    /**
     * Like {@link #sourcePathFor(CarriageContents)} but takes a raw id string
     * — used by saveAs / rename flows to locate (and delete) the source-tree
     * file under an outgoing name.
     */
    public static Path sourcePathForId(String id) {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        if (projectRoot == null) return null;
        Path resources = projectRoot.resolve("src/main/resources");
        if (!Files.isDirectory(resources) || !Files.isWritable(resources)) return null;
        return projectRoot.resolve(SOURCE_REL_PATH).resolve(id + EXT);
    }
}
