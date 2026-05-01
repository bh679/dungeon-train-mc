package games.brennan.dungeontrain.track.variant;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.VariantState;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-{@code (TrackKind, name)} sidecar of {@code localPos → [VariantState, ...]}
 * candidate lists — track-side analogue of
 * {@link games.brennan.dungeontrain.editor.CarriagePartVariantBlocks}. Enables
 * shift-right-click variant authoring inside track-side editor plots and
 * deterministic per-tile randomization at stamp time.
 *
 * <p>Storage: {@code config/dungeontrain/<kind.subdir>/<name>.variants.json}
 * alongside the template NBT. Schema mirrors the carriage sidecar — the v2
 * object form supports a per-entry {@code nbt} payload for block-entity
 * round-trip (chests, signs, banners). Schema parsing delegates to
 * {@link CarriageVariantBlocks#parseVariantElement} so both code paths stay
 * in lockstep.</p>
 *
 * <p>Local coordinates are clamped to the kind's footprint
 * {@link TrackKind#dims} — entries outside that box are dropped on load with
 * a warning so a kind-renamed template doesn't poison neighbouring blocks.</p>
 */
public final class TrackVariantBlocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int CURRENT_SCHEMA_VERSION = CarriageVariantBlocks.CURRENT_SCHEMA_VERSION;
    public static final int MIN_STATES_PER_ENTRY = CarriageVariantBlocks.MIN_STATES_PER_ENTRY;

    /** Session cache keyed on {@code <kind>:<name>}. Invalidated on save and on editor enter. */
    private static final Map<String, TrackVariantBlocks> CACHE = new HashMap<>();

    private final Map<BlockPos, List<VariantState>> entries;

    /** pos → lock-id (≥1 = locked, 0/missing = unlocked). See {@link CarriageVariantBlocks#lockIdAt}. */
    private final Map<BlockPos, Integer> lockIds;

    private TrackVariantBlocks(Map<BlockPos, List<VariantState>> entries, Map<BlockPos, Integer> lockIds) {
        this.entries = entries;
        this.lockIds = lockIds;
    }

    public static TrackVariantBlocks empty() {
        return new TrackVariantBlocks(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    public static Path configPathFor(TrackKind kind, String name) {
        return FMLPaths.CONFIGDIR.get().resolve(kind.configSubdir()).resolve(name + TrackKind.VARIANTS_EXT);
    }

    public static String bundledResourceFor(TrackKind kind, String name) {
        return kind.bundledResourcePrefix() + name + TrackKind.VARIANTS_EXT;
    }

    private static String cacheKey(TrackKind kind, String name) {
        return kind.id() + ":" + name;
    }

    /**
     * Load the sidecar for {@code (kind, name)} — config first, then bundled.
     * Returns {@link #empty} if neither exists. Entries outside
     * {@code expectedSize} are dropped with a warning.
     */
    public static synchronized TrackVariantBlocks loadFor(TrackKind kind, String name, Vec3i expectedSize) {
        String key = cacheKey(kind, name);
        TrackVariantBlocks cached = CACHE.get(key);
        if (cached != null) return cached;
        TrackVariantBlocks loaded = loadFromDisk(kind, name, expectedSize);
        CACHE.put(key, loaded);
        return loaded;
    }

    private static TrackVariantBlocks loadFromDisk(TrackKind kind, String name, Vec3i size) {
        Path cfg = configPathFor(kind, name);
        if (Files.isRegularFile(cfg)) {
            try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
                return parse(r, kind, name, "config " + cfg, size);
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Failed to read track variant sidecar {}: {}", cfg, e.toString());
            }
        }
        String resource = bundledResourceFor(kind, name);
        try (InputStream in = TrackVariantBlocks.class.getResourceAsStream(resource)) {
            if (in == null) return empty();
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(r, kind, name, "bundled " + resource, size);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled track variant sidecar {}: {}",
                resource, e.toString());
            return empty();
        }
    }

    private static TrackVariantBlocks parse(Reader reader, TrackKind kind, String name,
                                             String origin, Vec3i size) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            LOGGER.warn("[DungeonTrain] Track variant sidecar {}:{} ({}) is not a JSON object — ignoring.",
                kind.id(), name, origin);
            return empty();
        }
        JsonObject obj = root.getAsJsonObject();
        if (obj.has("schemaVersion")) {
            int v = obj.get("schemaVersion").getAsInt();
            if (v > CURRENT_SCHEMA_VERSION) {
                LOGGER.warn("[DungeonTrain] Track variant sidecar {}:{} ({}) schemaVersion {} (newer than {}) — best-effort parse.",
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
            BlockPos pos = CarriageVariantBlocks.parsePos(field.getKey());
            if (pos == null) {
                LOGGER.warn("[DungeonTrain] Track variant sidecar {}: bad pos '{}', skipping.",
                    contextId, field.getKey());
                continue;
            }
            if (!inBounds(pos, size)) {
                LOGGER.warn("[DungeonTrain] Track variant sidecar {}: pos {} outside footprint {}x{}x{}, skipping.",
                    contextId, pos, size.getX(), size.getY(), size.getZ());
                continue;
            }
            CarriageVariantBlocks.ParsedCell cell = CarriageVariantBlocks.parseCellValue(
                field.getValue(), blocks, contextId, pos);
            if (cell == null) continue;
            if (cell.states().size() < MIN_STATES_PER_ENTRY) {
                LOGGER.warn("[DungeonTrain] Track variant sidecar {} pos {}: fewer than {} valid states, dropped.",
                    contextId, pos, MIN_STATES_PER_ENTRY);
                continue;
            }
            BlockPos posI = pos.immutable();
            out.put(posI, List.copyOf(cell.states()));
            if (cell.lockId() > 0) outLocks.put(posI, cell.lockId());
        }
        LOGGER.info("[DungeonTrain] Loaded {} track variant entries for {} from {}",
            out.size(), contextId, origin);
        return new TrackVariantBlocks(out, outLocks);
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

    public List<VariantState> statesAt(BlockPos localPos) {
        return entries.get(localPos);
    }

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

    /**
     * Deterministic pick — locked cells share a single roll across the group.
     * Shares the {@link CarriageVariantBlocks#pickIndexWeighted} /
     * {@link CarriageVariantBlocks#pickIndexFromLockGroup} mixers with the
     * carriage path.
     */
    public VariantState resolve(BlockPos localPos, long worldSeed, int tileIndex) {
        List<VariantState> states = entries.get(localPos);
        if (states == null || states.isEmpty()) return null;
        int lockId = lockIdAt(localPos);
        int idx;
        if (lockId > 0) {
            int[] weights = new int[states.size()];
            for (int i = 0; i < states.size(); i++) weights[i] = states.get(i).weight();
            idx = CarriageVariantBlocks.pickIndexFromLockGroup(lockId, worldSeed, tileIndex, weights);
        } else {
            idx = CarriageVariantBlocks.pickIndexWeighted(localPos, worldSeed, tileIndex, states);
        }
        return states.get(idx);
    }

    public synchronized void save(TrackKind kind, String name) throws IOException {
        Path file = configPathFor(kind, name);
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(toJsonText());
        }
        CACHE.put(cacheKey(kind, name), this);
        LOGGER.info("[DungeonTrain] Saved track variant sidecar for {}:{} ({} entries) to {}",
            kind.id(), name, entries.size(), file);
    }

    /**
     * Dev-mode write-through: copy the sidecar into the project source tree
     * so it ships with the next build. Mirrors
     * {@link games.brennan.dungeontrain.editor.CarriagePartVariantBlocks#saveToSource}.
     * An empty sidecar deletes the source file so removing every entry doesn't
     * leave a stale bundled resource.
     */
    public synchronized void saveToSource(TrackKind kind, String name) throws IOException {
        Path file = sourcePathFor(kind, name);
        if (file == null) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        if (entries.isEmpty()) {
            Files.deleteIfExists(file);
            LOGGER.info("[DungeonTrain] Cleared bundled track variant sidecar for {}:{} (no entries)",
                kind.id(), name);
            return;
        }
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(toJsonText());
        }
        LOGGER.info("[DungeonTrain] Wrote bundled track variant sidecar for {}:{} to {}",
            kind.id(), name, file);
    }

    private String toJsonText() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"schemaVersion\": ").append(CURRENT_SCHEMA_VERSION).append(",\n");
        sb.append("  \"variants\": {");
        boolean firstEntry = true;
        for (Map.Entry<BlockPos, List<VariantState>> e : entries.entrySet()) {
            if (!firstEntry) sb.append(",");
            firstEntry = false;
            int lockId = lockIds.getOrDefault(e.getKey(), 0);
            sb.append("\n    \"").append(formatPos(e.getKey())).append("\": ");
            CarriageVariantBlocks.appendCellJson(sb, e.getValue(), lockId);
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
    }

    /**
     * Resolves the source-tree path for {@code (kind, name)}. Returns null
     * when the source tree isn't present or writable (production install) so
     * callers can soft-fail. Reuses {@link TrackKind#sourceRelativePath} so
     * the layout matches {@link TrackVariantStore#sourceFileFor}.
     */
    public static Path sourcePathFor(TrackKind kind, String name) {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        if (projectRoot == null) return null;
        Path resources = projectRoot.resolve("src/main/resources");
        if (!Files.isDirectory(resources) || !Files.isWritable(resources)) return null;
        return projectRoot.resolve(kind.sourceRelativePath()).resolve(name + TrackKind.VARIANTS_EXT);
    }

    public static synchronized boolean delete(TrackKind kind, String name) throws IOException {
        Path file = configPathFor(kind, name);
        boolean existed = Files.deleteIfExists(file);
        CACHE.remove(cacheKey(kind, name));
        if (existed) LOGGER.info("[DungeonTrain] Deleted track variant sidecar {}:{} ({})",
            kind.id(), name, file);
        return existed;
    }

    public static synchronized void invalidate(TrackKind kind, String name) {
        CACHE.remove(cacheKey(kind, name));
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }
}
