package games.brennan.dungeontrain.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.WholeKind;
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
 * Per-template sidecar of {@code localPos → [VariantState, ...]} candidate lists for the Whole
 * section — one file per room ({@link WholeKind#ROOM}) or group ({@link WholeKind#GROUP}), the
 * counterpart of {@link CarriageContentsVariantBlocks} for plots that hold a whole build.
 *
 * <p>What the Z menu writes inside a whole plot, and what {@code WholeOverlay} rolls at spawn time
 * after the verbatim stamp: per-cell variant pools, lock groups, and the container prefabs the C
 * menu links (those live in {@link ContainerContentsStore} under the plot key
 * {@code whole:<id>} / {@code whole_group:<id>}).</p>
 *
 * <p>Storage: {@code config/dungeontrain/user/<kind subdir>/<id>.variants.json} beside the template
 * NBT, bundled at {@code /data/dungeontrain/whole/<kind>/<id>.variants.json}. Local coordinates are
 * plot-local from the room's (or the run's) shell corner. Schema is {@link CarriageVariantBlocks}'s.</p>
 */
public final class WholeVariantBlocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int CURRENT_SCHEMA_VERSION = CarriageVariantBlocks.CURRENT_SCHEMA_VERSION;
    public static final int MIN_STATES_PER_ENTRY = CarriageVariantBlocks.MIN_STATES_PER_ENTRY;

    static final String EXT = ".variants.json";

    /** Session cache keyed on {@code kind:id}. Invalidated on save and on registry unregister. */
    private static final Map<String, WholeVariantBlocks> CACHE = new HashMap<>();

    private static String cacheKey(WholeKind kind, String id) {
        return kind.id() + ":" + id;
    }

    private final Map<BlockPos, List<VariantState>> entries;

    /** pos → lock-id (≥1 = locked, 0/missing = unlocked). See {@link CarriageVariantBlocks#lockIdAt}. */
    private final Map<BlockPos, Integer> lockIds;

    /** v9 lock-group reference resolution over {@link #entries} / {@link #lockIds}. */
    private final VariantGroupResolver groupRefs;

    /**
     * Per-template editor mirror axes, applied live and as a save-time backstop
     * by {@link EditorMirror}. Optional top-level {@code "mirror": {x,y,z}}
     * field; all default false (interiors are opt-in — not inherently symmetric).
     */
    private boolean mirrorX;
    private boolean mirrorY;
    private boolean mirrorZ;
    /** Opt-in flag (the "V" toggle): mirror the variant pools, not just structural blocks. */
    private boolean mirrorVariants;

    /**
     * The whole sidecar this instance is a bounded <em>view</em> of, or null when this <em>is</em>
     * the whole sidecar — see
     * {@link games.brennan.dungeontrain.track.variant.TrackVariantBlocks#source}. Mutations and both
     * write paths go through to it, so a bounded read can never become a truncated write.
     */
    private final WholeVariantBlocks source;

    private WholeVariantBlocks(Map<BlockPos, List<VariantState>> entries, Map<BlockPos, Integer> lockIds) {
        this(entries, lockIds, false, false, false, false);
    }

    private WholeVariantBlocks(Map<BlockPos, List<VariantState>> entries, Map<BlockPos, Integer> lockIds,
                                          boolean mirrorX, boolean mirrorY, boolean mirrorZ, boolean mirrorVariants) {
        this(entries, lockIds, mirrorX, mirrorY, mirrorZ, mirrorVariants, null);
    }

    private WholeVariantBlocks(Map<BlockPos, List<VariantState>> entries, Map<BlockPos, Integer> lockIds,
                                          boolean mirrorX, boolean mirrorY, boolean mirrorZ, boolean mirrorVariants,
                                          WholeVariantBlocks source) {
        this.source = source;
        this.entries = entries;
        this.lockIds = lockIds;
        this.groupRefs = new VariantGroupResolver(entries, lockIds);
        this.mirrorX = mirrorX;
        this.mirrorY = mirrorY;
        this.mirrorZ = mirrorZ;
        this.mirrorVariants = mirrorVariants;
    }

    public static WholeVariantBlocks empty() {
        return new WholeVariantBlocks(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    /** Editor mirror X (length) axis. False unless the sidecar sets {@code mirror.x=true}. */
    public boolean mirrorX() { return mirrorX; }

    /** Editor mirror Y (height) axis. False unless the sidecar sets {@code mirror.y=true}. */
    public boolean mirrorY() { return mirrorY; }

    /** Editor mirror Z (width) axis. False unless the sidecar sets {@code mirror.z=true}. */
    public boolean mirrorZ() { return mirrorZ; }

    /** Editor mirror-variants ("V") opt-in. False unless the sidecar sets {@code mirror.v=true}. */
    public boolean mirrorVariants() { return mirrorVariants; }

    /** True when no axis/flag is enabled — the absent-{@code mirror}-field state (contents default). */
    private boolean isDefaultMirror() {
        return !mirrorX && !mirrorY && !mirrorZ && !mirrorVariants;
    }

    /** Set all three editor mirror axes — used by the {@code editor mirror} command before {@link #save}. */
    public synchronized void setMirrorAxes(boolean x, boolean y, boolean z) {
        if (source != null) source.setMirrorAxes(x, y, z);
        this.mirrorX = x;
        this.mirrorY = y;
        this.mirrorZ = z;
    }

    /** Set the mirror-variants ("V") opt-in — used by {@code editor mirror v on|off} before {@link #save}. */
    public synchronized void setMirrorVariants(boolean v) {
        if (source != null) source.setMirrorVariants(v);
        this.mirrorVariants = v;
    }

    public static Path configPathFor(WholeKind kind, String id) {
        return UserContentPaths.dir(kind.userSubdir()).resolve(id + EXT);
    }

    private static String bundledResourceFor(WholeKind kind, String id) {
        return kind.bundledResourcePrefix() + id + EXT;
    }

    /**
     * Load the sidecar for {@code contents} — config dir first, then bundled
     * resource. Returns {@link #empty} if neither exists. Entries outside the
     * {@code interiorSize} are dropped with a warning so a stale sidecar from
     * an earlier dims doesn't paint blocks into the shell.
     */
    public static synchronized WholeVariantBlocks loadFor(WholeKind kind, String id, Vec3i footprint) {
        String key = cacheKey(kind, id);
        WholeVariantBlocks cached = CACHE.get(key);
        if (cached == null) {
            cached = loadFromDisk(kind, id);
            CACHE.put(key, cached);
        }
        return cached.croppedTo(id, footprint);
    }

    /**
     * This sidecar bounded to {@code size} — {@code this} when every cell already fits, otherwise a
     * detached, unsaveable copy without the out-of-bounds cells. The bound is applied per caller so
     * one caller's interior size cannot prune what the rest of the session sees.
     */
    private synchronized WholeVariantBlocks croppedTo(String id, Vec3i size) {
        if (size == null) return this;
        List<BlockPos> outside = null;
        for (BlockPos pos : entries.keySet()) {
            if (inBounds(pos, size)) continue;
            if (outside == null) outside = new ArrayList<>();
            outside.add(pos);
        }
        if (outside == null) return this;

        Map<BlockPos, List<VariantState>> kept = new LinkedHashMap<>(entries);
        Map<BlockPos, Integer> keptLocks = new LinkedHashMap<>(lockIds);
        for (BlockPos pos : outside) {
            kept.remove(pos);
            keptLocks.remove(pos);
            LOGGER.warn("[DungeonTrain] Whole variant sidecar {}: pos {} outside plot {}x{}x{}, skipping.",
                id, pos, size.getX(), size.getY(), size.getZ());
        }
        return new WholeVariantBlocks(kept, keptLocks, mirrorX, mirrorY, mirrorZ,
            mirrorVariants, this);
    }

    /** True when this instance is a bounded view — see {@link #cropped}. */
    public boolean isCropped() { return source != null; }


    private static WholeVariantBlocks loadFromDisk(WholeKind kind, String id) {
        Path cfg = UserContentPaths.findFile(kind.userSubdir(), id + EXT);
        if (cfg != null) {
            try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
                return parse(r, id, "config " + cfg);
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Failed to read whole variant sidecar {}: {}", cfg, e.toString());
            }
        }
        String resource = bundledResourceFor(kind, id);
        try (InputStream in = WholeVariantBlocks.class.getResourceAsStream(resource)) {
            if (in == null) return empty();
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(r, id, "bundled " + resource);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled whole variant sidecar {}: {}", resource, e.toString());
            return empty();
        }
    }

    /**
     * Parse the whole sidecar, keeping every cell however far outside any interior — bounding is
     * {@link #croppedTo}'s job.
     */
    private static WholeVariantBlocks parse(Reader reader, String contextId,
                                                        String origin) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            LOGGER.warn("[DungeonTrain] Whole variant sidecar {} ({}) is not a JSON object — ignoring.",
                contextId, origin);
            return empty();
        }
        JsonObject obj = root.getAsJsonObject();
        if (obj.has("schemaVersion")) {
            int v = obj.get("schemaVersion").getAsInt();
            if (v > CURRENT_SCHEMA_VERSION) {
                LOGGER.warn("[DungeonTrain] Whole variant sidecar {} ({}) schemaVersion {} (newer than {}) — best-effort parse.",
                    contextId, origin, v, CURRENT_SCHEMA_VERSION);
            }
        }
        // Optional top-level editor mirror axes — all default false (interiors are opt-in).
        boolean mirrorX = false;
        boolean mirrorY = false;
        boolean mirrorZ = false;
        boolean mirrorVariants = false;
        if (obj.has("mirror") && obj.get("mirror").isJsonObject()) {
            JsonObject m = obj.getAsJsonObject("mirror");
            if (m.has("x")) mirrorX = m.get("x").getAsBoolean();
            if (m.has("y")) mirrorY = m.get("y").getAsBoolean();
            if (m.has("z")) mirrorZ = m.get("z").getAsBoolean();
            if (m.has("v")) mirrorVariants = m.get("v").getAsBoolean();
        }
        if (!obj.has("variants") || !obj.get("variants").isJsonObject()) {
            return new WholeVariantBlocks(new LinkedHashMap<>(), new LinkedHashMap<>(), mirrorX, mirrorY, mirrorZ, mirrorVariants);
        }

        HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        JsonObject variants = obj.getAsJsonObject("variants");
        Map<BlockPos, List<VariantState>> out = new LinkedHashMap<>();
        Map<BlockPos, Integer> outLocks = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> field : variants.entrySet()) {
            BlockPos pos = parsePos(field.getKey());
            if (pos == null) {
                LOGGER.warn("[DungeonTrain] Whole variant sidecar {}: bad pos '{}', skipping.",
                    contextId, field.getKey());
                continue;
            }
            CarriageVariantBlocks.ParsedCell cell = CarriageVariantBlocks.parseCellValue(
                field.getValue(), blocks, contextId, pos);
            if (cell == null) continue;
            if (cell.states().size() < MIN_STATES_PER_ENTRY) {
                LOGGER.warn("[DungeonTrain] Whole variant sidecar {} pos {}: fewer than {} valid states, dropped.",
                    contextId, pos, MIN_STATES_PER_ENTRY);
                continue;
            }
            BlockPos posI = pos.immutable();
            out.put(posI, List.copyOf(cell.states()));
            if (cell.lockId() > 0) outLocks.put(posI, cell.lockId());
        }
        LOGGER.info("[DungeonTrain] Loaded {} whole variant entries for {} from {}",
            out.size(), contextId, origin);
        return new WholeVariantBlocks(out, outLocks, mirrorX, mirrorY, mirrorZ, mirrorVariants);
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
        if (source != null) source.put(localPos, states);
        entries.put(localPos.immutable(), List.copyOf(states));
        groupRefs.invalidate();
    }

    public synchronized boolean remove(BlockPos localPos) {
        if (source != null) source.remove(localPos);
        lockIds.remove(localPos);
        groupRefs.invalidate();
        return entries.remove(localPos) != null;
    }

    /**
     * Wipe every entry and every lock-id. Returns the count of entries that
     * were present before the call. Used by {@code /editor clear} so a
     * contents wipe doesn't leave orphaned variant metadata pointing at
     * now-air cells.
     */
    public synchronized int clearAll() {
        int n = entries.size();
        entries.clear();
        lockIds.clear();
        groupRefs.invalidate();
        return n;
    }

    /** Lock-id at {@code localPos}; 0 if unlocked or no entry. */
    public synchronized int lockIdAt(BlockPos localPos) {
        return lockIds.getOrDefault(localPos, 0);
    }

    public synchronized void setLockId(BlockPos localPos, int lockId) {
        if (source != null) source.setLockId(localPos, lockId);
        if (!entries.containsKey(localPos)) {
            throw new IllegalArgumentException("no cell at " + localPos + " — call put first");
        }
        if (lockId < 0) lockId = 0;
        if (lockId == 0) lockIds.remove(localPos);
        else lockIds.put(localPos.immutable(), lockId);
        groupRefs.invalidate();
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
        return pickFrom(localPos, worldSeed, carriageIndex, states);
    }

    /**
     * Difficulty-aware pick. Mob (spawn-egg) entries whose {@link VariantState#difficulty}
     * band excludes {@code tier} are dropped from the candidate pool <b>before</b>
     * the weighted roll, so the cell re-rolls among the in-range candidates
     * (another egg or a block). Block entries are always eligible. Returns
     * {@code null} when the cell has no candidate, or when every candidate is an
     * out-of-band mob — in the latter case the block-placement pass
     * ({@code CarriageContentsPlacer#applyVariantBlocks}) clears the cell to air
     * (the mob would have occupied an air cell) and the mob pass spawns nothing.
     *
     * <p>With the default band on every entry the eligible list equals the
     * full list, so the pick is bit-identical to
     * {@link #resolve(BlockPos, long, int)} — pre-v8 sidecars are unaffected.
     * The block pass and the deferred mob pass call this with the same
     * {@code (localPos, seed, carriageIndex, tier)} — {@code tier} derived from
     * the carriage's own index — so they always agree on the result regardless
     * of when each pass runs.</p>
     */
    public VariantState resolve(BlockPos localPos, long worldSeed, int carriageIndex, int tier) {
        List<VariantState> states = entries.get(localPos);
        if (states == null || states.isEmpty()) return null;
        boolean anyGated = false;
        for (VariantState s : states) {
            if (s.isMob() && !s.difficulty().eligible(tier)) { anyGated = true; break; }
        }
        if (!anyGated) return pickFrom(localPos, worldSeed, carriageIndex, states);
        List<VariantState> eligible = new ArrayList<>(states.size());
        for (VariantState s : states) {
            if (!s.isMob() || s.difficulty().eligible(tier)) eligible.add(s);
        }
        if (eligible.isEmpty()) return null;
        return pickFrom(localPos, worldSeed, carriageIndex, eligible);
    }

    /**
     * Shared weighted/locked pick over an explicit (already difficulty-filtered)
     * candidate list. v9 lock-group references are then filtered for liveness
     * and followed by {@link VariantGroupResolver} — the two filters compose:
     * the difficulty band narrows the pool first, the reference check second.
     */
    private VariantState pickFrom(BlockPos localPos, long worldSeed, int carriageIndex, List<VariantState> states) {
        return groupRefs.resolve(localPos, lockIdAt(localPos), states, worldSeed, carriageIndex);
    }

    /** This sidecar's lock groups, for callers that follow references themselves (e.g. the editor preview). */
    public VariantGroupResolver groupRefs() {
        return groupRefs;
    }

    public synchronized void save(WholeKind kind, String id) throws IOException {
        if (source != null) { source.save(kind, id); return; }
        writeConfig(kind, id);
    }

    /** The user-tier write behind {@link #save}, keyed by id so {@link #rename} can carry a sidecar. */
    private void writeConfig(WholeKind kind, String id) throws IOException {
        Path file = configPathFor(kind, id);
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(toJsonText());
        }
        CACHE.put(cacheKey(kind, id), this);
        LOGGER.info("[DungeonTrain] Saved whole variant sidecar for {} ({} entries) to {}",
            id, entries.size(), file);
    }

    /**
     * Write the sidecar to the source-tree resources directory so it ships
     * with the next build. Only meaningful in a dev checkout. Mirrors the
     * {@code saveToSource} behaviour on {@link CarriageContentsStore}.
     */
    public synchronized void saveToSource(WholeKind kind, String id) throws IOException {
        if (source != null) { source.saveToSource(kind, id); return; }
        Path file = sourcePathFor(kind, id);
        if (file == null) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(toJsonText());
        }
        LOGGER.info("[DungeonTrain] Wrote bundled whole variant sidecar for {} to {}", id, file);
    }

    /** Serialised form of this sidecar as {@link #save} would write it. Used by the editor undo history. */
    String toJsonText() {
        // Hand-written to keep the v2 mixed-array form (bare strings + objects)
        // diff-clean against existing files. Same shape as
        // CarriagePartVariantBlocks#save / CarriageVariantBlocks#toJson.
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"schemaVersion\": ").append(CURRENT_SCHEMA_VERSION).append(",\n");
        if (!isDefaultMirror()) {
            sb.append("  \"mirror\": { \"x\": ").append(mirrorX)
              .append(", \"y\": ").append(mirrorY)
              .append(", \"z\": ").append(mirrorZ)
              .append(", \"v\": ").append(mirrorVariants).append(" },\n");
        }
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

    public static synchronized boolean delete(WholeKind kind, String id) throws IOException {
        Path file = configPathFor(kind, id);
        boolean existed = Files.deleteIfExists(file);
        CACHE.remove(cacheKey(kind, id));
        if (existed) LOGGER.info("[DungeonTrain] Deleted whole variant sidecar {} ({})", id, file);
        return existed;
    }

    /**
     * Carry the sidecar from {@code sourceId} to {@code targetId} so a contents save-as / rename
     * keeps its variants. A user-tier file is moved; a sidecar that only exists in the session
     * cache, an imported package or the bundled resource is written out under the new id instead
     * — a save-as of a bundled template used to lose every entry here, because there was no file
     * to move and the cache entry was dropped. Returns false when there was nothing to carry.
     */
    public static synchronized boolean rename(WholeKind kind, String sourceId, String targetId) throws IOException {
        Path src = configPathFor(kind, sourceId);
        Path dst = configPathFor(kind, targetId);
        if (Files.isRegularFile(src)) {
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            WholeVariantBlocks cached = CACHE.remove(cacheKey(kind, sourceId));
            if (cached != null) CACHE.put(cacheKey(kind, targetId), cached);
            LOGGER.info("[DungeonTrain] Renamed whole variant sidecar {} -> {}", src, dst);
            return true;
        }
        WholeVariantBlocks carried = CACHE.remove(cacheKey(kind, sourceId));
        if (carried == null) carried = loadFromDisk(kind, sourceId);
        if (carried.isEmpty() && carried.isDefaultMirror()) return false;
        carried.writeConfig(kind, targetId);
        LOGGER.info("[DungeonTrain] Carried {} whole variant entries {} -> {} (no user-tier file to move)",
            carried.entries.size(), sourceId, targetId);
        return true;
    }

    public static synchronized void invalidate(WholeKind kind, String id) {
        CACHE.remove(cacheKey(kind, id));
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    /** Source-tree path of the bundled sidecar; null outside a writable dev checkout. */
    public static Path sourcePathFor(WholeKind kind, String id) {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        if (projectRoot == null) return null;
        Path resources = projectRoot.resolve("src/main/resources");
        if (!Files.isDirectory(resources) || !Files.isWritable(resources)) return null;
        return resources.resolve(bundledResourceFor(kind, id).substring(1));
    }
}
