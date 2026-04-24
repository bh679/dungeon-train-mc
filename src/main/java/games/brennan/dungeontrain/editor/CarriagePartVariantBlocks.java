package games.brennan.dungeontrain.editor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriagePartKind;
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
 * Per-part sidecar of {@code localPos → [blockState, ...]} candidate lists —
 * the part template's local analogue of {@link CarriageVariantBlocks}. Enables
 * {@code shift-right-click} variant authoring inside part editor plots and
 * deterministic per-carriage-index randomisation when
 * {@link games.brennan.dungeontrain.train.CarriagePartTemplate#placeAt}
 * stamps the part at spawn time.
 *
 * <p>Storage: {@code config/dungeontrain/parts/<kind>/<name>.variants.json}
 * alongside the part NBT. Schema mirrors the carriage sidecar (v1) —
 * {@code {"schemaVersion": 1, "variants": {"x,y,z": ["minecraft:oak_log",
 * "minecraft:birch_log"]}}}. The {@link CarriageVariantBlocks#isEmptyPlaceholder}
 * sentinel (command-block states) and {@link CarriageVariantBlocks#pickIndex}
 * deterministic mixer are shared — a part's random pick seeds on the same
 * {@code (worldSeed, carriageIndex, localPos)} basis so a parts-backed
 * carriage's rolling-window re-render stays stable.</p>
 *
 * <p>Local coordinates are clamped to the part's footprint
 * {@code kind.dims(worldDims)} rather than the whole carriage shell — walls
 * only have Y and X to play with, doors only X and Z, etc.</p>
 */
public final class CarriagePartVariantBlocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MIN_STATES_PER_ENTRY = CarriageVariantBlocks.MIN_STATES_PER_ENTRY;

    private static final String SUBDIR_BASE = "dungeontrain/parts";
    private static final String EXT = ".variants.json";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/parts/";

    /** Session cache keyed on {@code <kind>:<name>}. Invalidated on save and on editor enter. */
    private static final Map<String, CarriagePartVariantBlocks> CACHE = new HashMap<>();

    private final Map<BlockPos, List<BlockState>> entries;

    private CarriagePartVariantBlocks(Map<BlockPos, List<BlockState>> entries) {
        this.entries = entries;
    }

    public static CarriagePartVariantBlocks empty() {
        return new CarriagePartVariantBlocks(new LinkedHashMap<>());
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
            if (v != CURRENT_SCHEMA_VERSION) {
                LOGGER.warn("[DungeonTrain] Part variant sidecar {}:{} ({}) schemaVersion {} (expected {}) — best-effort parse.",
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
                LOGGER.warn("[DungeonTrain] Part variant sidecar {}:{}: bad pos '{}', skipping.",
                    kind.id(), name, field.getKey());
                continue;
            }
            if (!inBounds(pos, partSize)) {
                LOGGER.warn("[DungeonTrain] Part variant sidecar {}:{}: pos {} outside part footprint {}x{}x{}, skipping.",
                    kind.id(), name, pos, partSize.getX(), partSize.getY(), partSize.getZ());
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
                    LOGGER.warn("[DungeonTrain] Part variant sidecar {}:{} pos {}: bad state '{}' ({}).",
                        kind.id(), name, pos, el.getAsString(), e.getMessage());
                }
            }
            if (states.size() < MIN_STATES_PER_ENTRY) {
                LOGGER.warn("[DungeonTrain] Part variant sidecar {}:{} pos {}: fewer than {} valid states, dropped.",
                    kind.id(), name, pos, MIN_STATES_PER_ENTRY);
                continue;
            }
            out.put(pos.immutable(), List.copyOf(states));
        }
        LOGGER.info("[DungeonTrain] Loaded {} part variant entries for {}:{} from {}",
            out.size(), kind.id(), name, origin);
        return new CarriagePartVariantBlocks(out);
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

    /** Candidate states at {@code localPos}, or {@code null} if no entry. */
    public List<BlockState> statesAt(BlockPos localPos) {
        return entries.get(localPos);
    }

    /** Replace the candidate list at {@code localPos}. Rejects block-entity states that aren't the empty-placeholder sentinel. */
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

    /** Deterministic pick — same {@code (worldSeed, carriageIndex, localPos)} → same state across reloads. */
    public BlockState resolve(BlockPos localPos, long worldSeed, int carriageIndex) {
        List<BlockState> states = entries.get(localPos);
        if (states == null || states.isEmpty()) return null;
        int idx = CarriageVariantBlocks.pickIndex(localPos, worldSeed, carriageIndex, states.size());
        return states.get(idx);
    }

    public synchronized void save(CarriagePartKind kind, String name) throws IOException {
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
        LOGGER.info("[DungeonTrain] Saved part variant sidecar for {}:{} ({} entries) to {}",
            kind.id(), name, entries.size(), file);
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
