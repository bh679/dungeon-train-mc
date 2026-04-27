package games.brennan.dungeontrain.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
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
import java.util.Locale;
import java.util.Map;

/**
 * Per-plot sidecar of {@code localPos → ContainerContentsPool}, parallel to
 * the variant block sidecars but stored in its own file so existing variant
 * sidecars stay diff-clean.
 *
 * <p>Keyed by the same plot key system as
 * {@link BlockVariantPlot#key()}: {@code "carriage:<id>"},
 * {@code "contents:<id>"}, {@code "part:<kind>:<name>"},
 * {@code "track:<kind>:<name>"}. The plot key is sanitised into a filename
 * by replacing {@code ':'} with {@code '__'} (colons are illegal on Windows).</p>
 *
 * <p>Storage path: {@code config/dungeontrain/containers/<plotKey>.contents.json}.</p>
 *
 * <p>Schema:
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "pools": {
 *     "x,y,z": [
 *       { "id": "minecraft:diamond", "count": 3, "weight": 5 },
 *       { "id": "minecraft:air", "count": 1, "weight": 20 }
 *     ]
 *   }
 * }
 * }</pre></p>
 */
public final class ContainerContentsStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final String SUBDIR = "dungeontrain/containers";
    private static final String EXT = ".contents.json";

    /** Session cache keyed by plot key. */
    private static final Map<String, ContainerContentsStore> CACHE = new HashMap<>();

    private final String plotKey;
    private final Map<BlockPos, ContainerContentsPool> pools;

    private ContainerContentsStore(String plotKey, Map<BlockPos, ContainerContentsPool> pools) {
        this.plotKey = plotKey;
        this.pools = pools;
    }

    public static synchronized ContainerContentsStore loadFor(String plotKey) {
        ContainerContentsStore cached = CACHE.get(plotKey);
        if (cached != null) return cached;
        ContainerContentsStore loaded = loadFromDisk(plotKey);
        CACHE.put(plotKey, loaded);
        return loaded;
    }

    /** Drop session cache (test / world-unload hook). */
    public static synchronized void clearCache() {
        CACHE.clear();
    }

    public String plotKey() { return plotKey; }

    /** Pool at {@code localPos}; never null — empty pool means no entries authored. */
    public synchronized ContainerContentsPool poolAt(BlockPos localPos) {
        ContainerContentsPool p = pools.get(localPos);
        return p == null ? ContainerContentsPool.empty() : p;
    }

    /** True when an authored pool with at least one entry exists at {@code localPos}. */
    public synchronized boolean hasPoolAt(BlockPos localPos) {
        ContainerContentsPool p = pools.get(localPos);
        return p != null && !p.isEmpty();
    }

    public synchronized void putPool(BlockPos localPos, ContainerContentsPool pool) {
        if (pool == null || pool.isEmpty()) {
            pools.remove(localPos);
        } else {
            pools.put(localPos.immutable(), pool);
        }
    }

    public synchronized boolean removePool(BlockPos localPos) {
        return pools.remove(localPos) != null;
    }

    public synchronized java.util.Set<BlockPos> allPositions() {
        return new java.util.LinkedHashSet<>(pools.keySet());
    }

    public synchronized void save() throws IOException {
        Path file = configPathFor(plotKey);
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(toJsonText());
        }
        LOGGER.info("[DungeonTrain] Saved container contents store for {} ({} cells) to {}",
            plotKey, pools.size(), file);
    }

    private String toJsonText() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"schemaVersion\": ").append(CURRENT_SCHEMA_VERSION).append(",\n");
        sb.append("  \"pools\": {");
        boolean first = true;
        for (Map.Entry<BlockPos, ContainerContentsPool> e : pools.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\n    \"").append(formatPos(e.getKey())).append("\": [");
            boolean firstEntry = true;
            for (ContainerContentsEntry ce : e.getValue().entries()) {
                if (!firstEntry) sb.append(",");
                sb.append("\n      {")
                    .append(" \"id\": \"").append(ce.itemId().toString()).append("\",")
                    .append(" \"count\": ").append(ce.count()).append(",")
                    .append(" \"weight\": ").append(ce.weight())
                    .append(" }");
                firstEntry = false;
            }
            sb.append("\n    ]");
            first = false;
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
    }

    private static ContainerContentsStore loadFromDisk(String plotKey) {
        Path file = configPathFor(plotKey);
        if (!Files.isRegularFile(file)) {
            return new ContainerContentsStore(plotKey, new LinkedHashMap<>());
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Container contents store {} not a JSON object — ignoring.", file);
                return new ContainerContentsStore(plotKey, new LinkedHashMap<>());
            }
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("schemaVersion") && obj.get("schemaVersion").getAsInt() > CURRENT_SCHEMA_VERSION) {
                LOGGER.warn("[DungeonTrain] Container contents store {} schemaVersion {} > {} — best-effort.",
                    file, obj.get("schemaVersion").getAsInt(), CURRENT_SCHEMA_VERSION);
            }
            Map<BlockPos, ContainerContentsPool> out = new LinkedHashMap<>();
            if (obj.has("pools") && obj.get("pools").isJsonObject()) {
                JsonObject pools = obj.getAsJsonObject("pools");
                for (Map.Entry<String, JsonElement> e : pools.entrySet()) {
                    BlockPos pos = parsePos(e.getKey());
                    if (pos == null) continue;
                    if (!e.getValue().isJsonArray()) continue;
                    JsonArray arr = e.getValue().getAsJsonArray();
                    List<ContainerContentsEntry> entries = new ArrayList<>(arr.size());
                    for (JsonElement el : arr) {
                        if (!el.isJsonObject()) continue;
                        JsonObject eo = el.getAsJsonObject();
                        if (!eo.has("id")) continue;
                        ResourceLocation id = ResourceLocation.tryParse(eo.get("id").getAsString());
                        if (id == null) continue;
                        int count = eo.has("count") ? eo.get("count").getAsInt() : 1;
                        int weight = eo.has("weight") ? eo.get("weight").getAsInt() : 1;
                        entries.add(new ContainerContentsEntry(id, count, weight));
                    }
                    if (!entries.isEmpty()) {
                        out.put(pos.immutable(), new ContainerContentsPool(entries));
                    }
                }
            }
            LOGGER.info("[DungeonTrain] Loaded container contents store {} ({} cells) from {}",
                plotKey, out.size(), file);
            return new ContainerContentsStore(plotKey, out);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read container contents store {}: {}", file, e.toString());
            return new ContainerContentsStore(plotKey, new LinkedHashMap<>());
        }
    }

    private static Path configPathFor(String plotKey) {
        String safe = plotKey.replace(':', '_').replace('/', '_').toLowerCase(Locale.ROOT);
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR).resolve(safe + EXT);
    }

    @Nullable
    private static BlockPos parsePos(String key) {
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

    private static String formatPos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
