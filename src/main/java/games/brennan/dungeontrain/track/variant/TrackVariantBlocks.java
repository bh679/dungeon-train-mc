package games.brennan.dungeontrain.track.variant;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
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
 * Per-{@code (TrackKind, name)} sidecar of {@code localPos → [blockState, ...]}
 * candidate lists — track-side analogue of
 * {@link games.brennan.dungeontrain.editor.CarriagePartVariantBlocks}. Enables
 * shift-right-click variant authoring inside track-side editor plots and
 * deterministic per-tile randomization at stamp time.
 *
 * <p>Storage: {@code config/dungeontrain/<kind.subdir>/<name>.variants.json}
 * alongside the template NBT. Schema mirrors the carriage sidecar (v1) —
 * {@code {"schemaVersion": 1, "variants": {"x,y,z": ["minecraft:oak_log",
 * "minecraft:birch_log"]}}}. The
 * {@link CarriageVariantBlocks#isEmptyPlaceholder} sentinel and
 * {@link CarriageVariantBlocks#pickIndex} deterministic mixer are shared so a
 * track tile's per-block randomization seeds on the same
 * {@code (worldSeed, tileIndex, localPos)} basis as the rest of the world.</p>
 *
 * <p>Local coordinates are clamped to the kind's footprint
 * {@link TrackKind#dims} — entries outside that box are dropped on load with
 * a warning so a kind-renamed template doesn't poison neighbouring blocks.</p>
 */
public final class TrackVariantBlocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MIN_STATES_PER_ENTRY = CarriageVariantBlocks.MIN_STATES_PER_ENTRY;

    /** Session cache keyed on {@code <kind>:<name>}. Invalidated on save and on editor enter. */
    private static final Map<String, TrackVariantBlocks> CACHE = new HashMap<>();

    private final Map<BlockPos, List<BlockState>> entries;

    private TrackVariantBlocks(Map<BlockPos, List<BlockState>> entries) {
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
            if (v != CURRENT_SCHEMA_VERSION) {
                LOGGER.warn("[DungeonTrain] Track variant sidecar {}:{} ({}) schemaVersion {} (expected {}) — best-effort parse.",
                    kind.id(), name, origin, v, CURRENT_SCHEMA_VERSION);
            }
        }
        if (!obj.has("variants") || !obj.get("variants").isJsonObject()) return empty();

        HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
        JsonObject variants = obj.getAsJsonObject("variants");
        Map<BlockPos, List<BlockState>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> field : variants.entrySet()) {
            BlockPos pos = parsePos(field.getKey());
            if (pos == null) {
                LOGGER.warn("[DungeonTrain] Track variant sidecar {}:{}: bad pos '{}', skipping.",
                    kind.id(), name, field.getKey());
                continue;
            }
            if (!inBounds(pos, size)) {
                LOGGER.warn("[DungeonTrain] Track variant sidecar {}:{}: pos {} outside footprint {}x{}x{}, skipping.",
                    kind.id(), name, pos, size.getX(), size.getY(), size.getZ());
                continue;
            }
            if (!field.getValue().isJsonArray()) continue;
            JsonArray arr = field.getValue().getAsJsonArray();
            List<BlockState> states = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) continue;
                try {
                    BlockStateParser.BlockResult parsed = BlockStateParser.parseForBlock(
                        blocks, el.getAsString(), false);
                    states.add(parsed.blockState());
                } catch (Exception e) {
                    LOGGER.warn("[DungeonTrain] Track variant sidecar {}:{} pos {}: bad state '{}' ({}).",
                        kind.id(), name, pos, el.getAsString(), e.getMessage());
                }
            }
            if (states.size() < MIN_STATES_PER_ENTRY) {
                LOGGER.warn("[DungeonTrain] Track variant sidecar {}:{} pos {}: fewer than {} valid states, dropped.",
                    kind.id(), name, pos, MIN_STATES_PER_ENTRY);
                continue;
            }
            out.put(pos.immutable(), List.copyOf(states));
        }
        LOGGER.info("[DungeonTrain] Loaded {} track variant entries for {}:{} from {}",
            out.size(), kind.id(), name, origin);
        return new TrackVariantBlocks(out);
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
        for (Map.Entry<BlockPos, List<BlockState>> e : entries.entrySet()) {
            out.add(new CarriageVariantBlocks.Entry(e.getKey(), e.getValue()));
        }
        return Collections.unmodifiableList(out);
    }

    public boolean isEmpty() { return entries.isEmpty(); }

    public int size() { return entries.size(); }

    public List<BlockState> statesAt(BlockPos localPos) {
        return entries.get(localPos);
    }

    public synchronized void put(BlockPos localPos, List<BlockState> states) {
        if (states == null || states.size() < MIN_STATES_PER_ENTRY) {
            throw new IllegalArgumentException(
                "need at least " + MIN_STATES_PER_ENTRY + " states, got "
                    + (states == null ? 0 : states.size()));
        }
        for (BlockState s : states) {
            if (s == null) throw new IllegalArgumentException("null state");
            if (s.hasBlockEntity() && !CarriageVariantBlocks.isEmptyPlaceholder(s)) {
                throw new IllegalArgumentException(
                    "block-entity state " + s + " rejected — sidecar cannot preserve BE data");
            }
        }
        entries.put(localPos.immutable(), List.copyOf(states));
    }

    public synchronized boolean remove(BlockPos localPos) {
        return entries.remove(localPos) != null;
    }

    /**
     * Deterministic pick — same {@code (worldSeed, tileIndex, localPos)} →
     * same state across reloads. Same hash mixer as
     * {@link CarriageVariantBlocks#pickIndex} so the carriage and track-side
     * sidecars stay in lockstep.
     */
    public BlockState resolve(BlockPos localPos, long worldSeed, int tileIndex) {
        List<BlockState> states = entries.get(localPos);
        if (states == null || states.isEmpty()) return null;
        int idx = CarriageVariantBlocks.pickIndex(localPos, worldSeed, tileIndex, states.size());
        return states.get(idx);
    }

    public synchronized void save(TrackKind kind, String name) throws IOException {
        Path file = configPathFor(kind, name);
        Files.createDirectories(file.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
        JsonObject variants = new JsonObject();
        for (Map.Entry<BlockPos, List<BlockState>> e : entries.entrySet()) {
            JsonArray arr = new JsonArray();
            for (BlockState s : e.getValue()) {
                arr.add(BlockStateParser.serialize(s));
            }
            variants.add(formatPos(e.getKey()), arr);
        }
        root.add("variants", variants);
        String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(pretty);
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
