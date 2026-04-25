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

    private TrackVariantBlocks(Map<BlockPos, List<VariantState>> entries) {
        this.entries = entries;
    }

    public static TrackVariantBlocks empty() {
        return new TrackVariantBlocks(new LinkedHashMap<>());
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
            if (!field.getValue().isJsonArray()) continue;
            List<VariantState> states = new ArrayList<>();
            for (JsonElement el : field.getValue().getAsJsonArray()) {
                VariantState parsed = CarriageVariantBlocks.parseVariantElement(el, blocks, contextId, pos);
                if (parsed != null) states.add(parsed);
            }
            if (states.size() < MIN_STATES_PER_ENTRY) {
                LOGGER.warn("[DungeonTrain] Track variant sidecar {} pos {}: fewer than {} valid states, dropped.",
                    contextId, pos, MIN_STATES_PER_ENTRY);
                continue;
            }
            out.put(pos.immutable(), List.copyOf(states));
        }
        LOGGER.info("[DungeonTrain] Loaded {} track variant entries for {} from {}",
            out.size(), contextId, origin);
        return new TrackVariantBlocks(out);
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
        return entries.remove(localPos) != null;
    }

    /**
     * Deterministic pick — same {@code (worldSeed, tileIndex, localPos)} →
     * same {@link VariantState} across reloads. Shares the
     * {@link CarriageVariantBlocks#pickIndex} mixer with the carriage path.
     */
    public VariantState resolve(BlockPos localPos, long worldSeed, int tileIndex) {
        List<VariantState> states = entries.get(localPos);
        if (states == null || states.isEmpty()) return null;
        int idx = CarriageVariantBlocks.pickIndex(localPos, worldSeed, tileIndex, states.size());
        return states.get(idx);
    }

    public synchronized void save(TrackKind kind, String name) throws IOException {
        Path file = configPathFor(kind, name);
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"schemaVersion\": ").append(CURRENT_SCHEMA_VERSION).append(",\n");
        sb.append("  \"variants\": {");
        boolean firstEntry = true;
        for (Map.Entry<BlockPos, List<VariantState>> e : entries.entrySet()) {
            if (!firstEntry) sb.append(",");
            firstEntry = false;
            sb.append("\n    \"").append(formatPos(e.getKey())).append("\": [");
            boolean firstState = true;
            for (VariantState s : e.getValue()) {
                if (!firstState) sb.append(", ");
                firstState = false;
                CarriageVariantBlocks.appendVariantJson(sb, s);
            }
            sb.append("]");
        }
        sb.append("\n  }\n}\n");
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
        CACHE.put(cacheKey(kind, name), this);
        LOGGER.info("[DungeonTrain] Saved track variant sidecar for {}:{} ({} entries) to {}",
            kind.id(), name, entries.size(), file);
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
