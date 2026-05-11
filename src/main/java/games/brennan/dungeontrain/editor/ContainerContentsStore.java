package games.brennan.dungeontrain.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import javax.annotation.Nullable;
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
 * <p>Storage path: {@code config/dungeontrain/user/containers/<plotKey>.contents.json}.</p>
 *
 * <p><b>Two states per position:</b>
 * <ul>
 *   <li><b>AUTHORED</b> — the local pool stored directly in {@code pools}.
 *       Read/write is local.</li>
 *   <li><b>LINKED</b> — only the link is stored in {@code links}; the pool
 *       is fetched from {@link LootPrefabStore} on every {@link #poolAt} call
 *       so template edits propagate automatically.</li>
 * </ul>
 * The two states are mutually exclusive: {@link #setLink} drops the local
 * pool, {@link #clearLink} snapshots the resolved pool back to local.</p>
 *
 * <p>Schema (v2):
 * <pre>{@code
 * {
 *   "schemaVersion": 2,
 *   "pools": {
 *     "x,y,z": {
 *       "fillMin": 1,
 *       "fillMax": 10,
 *       "entries": [
 *         { "id": "minecraft:diamond", "count": 3, "weight": 5 },
 *         { "id": "minecraft:air", "count": 1, "weight": 20 }
 *       ]
 *     }
 *   },
 *   "links": {
 *     "x,y,z": "loot_prefab_id"
 *   }
 * }
 * }</pre>
 *
 * <p>v1 files (no {@code links} block) load with no links. Older v2 files
 * that ship both pool data <i>and</i> a link for the same position (written
 * before the live-reference refactor) load with the link winning — the local
 * pool data is silently dropped on first load.</p>
 */
public final class ContainerContentsStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int CURRENT_SCHEMA_VERSION = 2;

    static final String SUBDIR = "containers";
    private static final String EXT = ".contents.json";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/containers/";

    /** Session cache keyed by plot key. */
    private static final Map<String, ContainerContentsStore> CACHE = new HashMap<>();

    private final String plotKey;
    private final Map<BlockPos, ContainerContentsPool> pools;
    private final Map<BlockPos, String> links;

    private ContainerContentsStore(String plotKey,
                                   Map<BlockPos, ContainerContentsPool> pools,
                                   Map<BlockPos, String> links) {
        this.plotKey = plotKey;
        this.pools = pools;
        this.links = links;
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

    /**
     * Pool at {@code localPos}; never null — empty pool means no entries authored.
     *
     * <p>Resolution order: if the position is LINKED, fetch from
     * {@link LootPrefabStore}; else return the local AUTHORED pool; else
     * return {@link ContainerContentsPool#empty()}. This is the single
     * read-through that lets template edits flow to all linked containers.</p>
     */
    public synchronized ContainerContentsPool poolAt(BlockPos localPos) {
        String linkId = links.get(localPos);
        if (linkId != null) {
            return LootPrefabStore.load(linkId)
                .map(LootPrefabStore.Data::pool)
                .orElseGet(ContainerContentsPool::empty);
        }
        ContainerContentsPool p = pools.get(localPos);
        return p == null ? ContainerContentsPool.empty() : p;
    }

    /** True when {@link #poolAt} would return a non-empty pool. */
    public synchronized boolean hasPoolAt(BlockPos localPos) {
        ContainerContentsPool p = poolAt(localPos);
        return !p.isEmpty();
    }

    /**
     * Write a pool to a position. Drops any link at that position — the
     * caller is taking ownership of the pool data locally.
     */
    public synchronized void putPool(BlockPos localPos, ContainerContentsPool pool) {
        // Drop only when truly default (no entries AND default fill range) so
        // a player who sets Fill before adding any items doesn't lose the
        // setting between clicks.
        if (pool == null || (pool.isEmpty() && pool.isDefaultRange())) {
            pools.remove(localPos);
            links.remove(localPos);
        } else {
            pools.put(localPos.immutable(), pool);
            // Writing a local pool implies the position is no longer a pure
            // link — clear any link so we don't keep both states.
            links.remove(localPos);
        }
    }

    public synchronized boolean removePool(BlockPos localPos) {
        boolean dropped = pools.remove(localPos) != null;
        // Drop the link too — a pool with no entries cannot meaningfully be linked.
        links.remove(localPos);
        return dropped;
    }

    public synchronized java.util.Set<BlockPos> allPositions() {
        java.util.LinkedHashSet<BlockPos> out = new java.util.LinkedHashSet<>(pools.keySet());
        out.addAll(links.keySet());
        return out;
    }

    /** Loot-prefab id this container is linked to, or {@code null} if unlinked. */
    @Nullable
    public synchronized String linkAt(BlockPos localPos) {
        return links.get(localPos);
    }

    /**
     * Establish or replace the link. Drops any local pool at this position —
     * linked containers don't store pool data; they read through to the
     * template via {@link LootPrefabStore}.
     */
    public synchronized void setLink(BlockPos localPos, String prefabId) {
        if (prefabId == null || prefabId.isEmpty()) {
            links.remove(localPos);
            return;
        }
        BlockPos key = localPos.immutable();
        links.put(key, prefabId);
        // Drop the local pool — the template is now the source of truth.
        pools.remove(key);
    }

    /**
     * Drop the link. Snapshots the currently-resolved pool (read-through to
     * the template) into the local pool first, so the user sees the same
     * entries they were viewing before the unlink.
     *
     * <p>Returns true if a link existed.</p>
     */
    public synchronized boolean clearLink(BlockPos localPos) {
        String prev = links.get(localPos);
        if (prev == null) return false;
        // Snapshot the template's current pool to the local store before
        // dropping the link, so the user keeps what they were seeing.
        ContainerContentsPool snapshot = LootPrefabStore.load(prev)
            .map(LootPrefabStore.Data::pool)
            .orElse(null);
        BlockPos key = localPos.immutable();
        links.remove(key);
        if (snapshot != null && !snapshot.isEmpty()) {
            pools.put(key, snapshot);
        }
        return true;
    }

    /** All positions in this store currently linked to {@code prefabId}. */
    public synchronized java.util.List<BlockPos> positionsLinkedTo(String prefabId) {
        if (prefabId == null) return java.util.Collections.emptyList();
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (Map.Entry<BlockPos, String> e : links.entrySet()) {
            if (prefabId.equals(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /**
     * Snapshot of every plot key with a stored sidecar on disk under the
     * config dir. Used by {@link ContainerContentsLinkPropagator} to find
     * stores it hasn't loaded into the session cache yet.
     */
    public static java.util.List<String> allKnownPlotKeys() {
        Path dir = UserContentPaths.dir(SUBDIR);
        if (!Files.isDirectory(dir)) return java.util.Collections.emptyList();
        java.util.List<String> keys = new java.util.ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + EXT)) {
            for (Path p : stream) {
                String filename = p.getFileName().toString();
                if (!filename.endsWith(EXT)) continue;
                String safe = filename.substring(0, filename.length() - EXT.length());
                String key = unsafeFilenameToKey(safe);
                if (key != null) keys.add(key);
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to enumerate container stores: {}", e.toString());
        }
        return keys;
    }

    /**
     * Inverse of {@link #safeFilename}. The filename uses {@code '_'} for
     * both colons and slashes so the round-trip isn't perfect — luckily our
     * plot keys never contain underscores in legitimate ids, so we use
     * heuristics by recognising the prefix:
     * {@code carriage_*}, {@code contents_*}, {@code part_*}, {@code track_*}.
     */
    @Nullable
    private static String unsafeFilenameToKey(String safe) {
        // Restore the first '_' after a known prefix back to ':'. For
        // part_/track_ we restore the second '_' too (kind:name pattern).
        if (safe.startsWith("carriage_")) return "carriage:" + safe.substring("carriage_".length());
        if (safe.startsWith("contents_")) return "contents:" + safe.substring("contents_".length());
        if (safe.startsWith("part_")) {
            String rest = safe.substring("part_".length());
            int sep = rest.indexOf('_');
            if (sep < 0) return null;
            return "part:" + rest.substring(0, sep) + ":" + rest.substring(sep + 1);
        }
        if (safe.startsWith("track_")) {
            String rest = safe.substring("track_".length());
            int sep = rest.indexOf('_');
            if (sep < 0) return null;
            // TrackKind ids contain underscores (e.g. "pillar_top"). Restore
            // the kind portion by checking against TrackKind.fromId.
            // Strategy: find the LONGEST prefix of `rest` that maps to a
            // valid TrackKind, then split there.
            String matchedKind = null;
            int matchedLen = -1;
            for (games.brennan.dungeontrain.track.variant.TrackKind k :
                    games.brennan.dungeontrain.track.variant.TrackKind.values()) {
                String kid = k.id();
                if (rest.length() > kid.length()
                    && rest.startsWith(kid)
                    && rest.charAt(kid.length()) == '_'
                    && kid.length() > matchedLen) {
                    matchedKind = kid;
                    matchedLen = kid.length();
                }
            }
            if (matchedKind == null) return null;
            return "track:" + matchedKind + ":" + rest.substring(matchedLen + 1);
        }
        return null;
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
            ContainerContentsPool pool = e.getValue();
            sb.append("\n    \"").append(formatPos(e.getKey())).append("\": {");
            if (pool.fillMin() != 0) {
                sb.append("\n      \"fillMin\": ").append(pool.fillMin()).append(",");
            }
            if (pool.fillMax() != ContainerContentsPool.FILL_ALL) {
                sb.append("\n      \"fillMax\": ").append(pool.fillMax()).append(",");
            }
            sb.append("\n      \"entries\": [");
            boolean firstEntry = true;
            for (ContainerContentsEntry ce : pool.entries()) {
                if (!firstEntry) sb.append(",");
                sb.append("\n        {")
                    .append(" \"id\": \"").append(ce.itemId().toString()).append("\",")
                    .append(" \"count\": ").append(ce.count()).append(",")
                    .append(" \"weight\": ").append(ce.weight())
                    .append(" }");
                firstEntry = false;
            }
            sb.append("\n      ]");
            sb.append("\n    }");
            first = false;
        }
        sb.append("\n  }");
        if (!links.isEmpty()) {
            sb.append(",\n  \"links\": {");
            boolean firstLink = true;
            for (Map.Entry<BlockPos, String> e : links.entrySet()) {
                if (!firstLink) sb.append(",");
                sb.append("\n    \"").append(formatPos(e.getKey())).append("\": \"")
                    .append(e.getValue()).append("\"");
                firstLink = false;
            }
            sb.append("\n  }");
        }
        sb.append("\n}\n");
        return sb.toString();
    }

    private static ContainerContentsStore loadFromDisk(String plotKey) {
        Path file = configPathFor(plotKey);
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return parseFromReader(r, plotKey, file.toString());
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Failed to read container contents store {}: {}", file, e.toString());
                return new ContainerContentsStore(plotKey, new LinkedHashMap<>(), new LinkedHashMap<>());
            }
        }
        // Fallback to bundled resource so authored pools ship with the mod.
        // Mirrors CarriageContentsVariantBlocks's two-tier load path.
        String resource = RESOURCE_PREFIX + safeFilename(plotKey) + EXT;
        try (InputStream in = ContainerContentsStore.class.getResourceAsStream(resource)) {
            if (in == null) return new ContainerContentsStore(plotKey, new LinkedHashMap<>(), new LinkedHashMap<>());
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parseFromReader(r, plotKey, "bundled " + resource);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled container contents store {}: {}", resource, e.toString());
            return new ContainerContentsStore(plotKey, new LinkedHashMap<>(), new LinkedHashMap<>());
        }
    }

    private static ContainerContentsStore parseFromReader(Reader r, String plotKey, String origin) {
        try {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Container contents store {} not a JSON object — ignoring.", origin);
                return new ContainerContentsStore(plotKey, new LinkedHashMap<>(), new LinkedHashMap<>());
            }
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("schemaVersion") && obj.get("schemaVersion").getAsInt() > CURRENT_SCHEMA_VERSION) {
                LOGGER.warn("[DungeonTrain] Container contents store {} schemaVersion {} > {} — best-effort.",
                    origin, obj.get("schemaVersion").getAsInt(), CURRENT_SCHEMA_VERSION);
            }
            Map<BlockPos, ContainerContentsPool> out = new LinkedHashMap<>();
            if (obj.has("pools") && obj.get("pools").isJsonObject()) {
                JsonObject pools = obj.getAsJsonObject("pools");
                for (Map.Entry<String, JsonElement> e : pools.entrySet()) {
                    BlockPos pos = parsePos(e.getKey());
                    if (pos == null) continue;
                    JsonElement value = e.getValue();
                    JsonArray arr;
                    int fillMin = 0;
                    int fillMax = ContainerContentsPool.FILL_ALL;
                    if (value.isJsonArray()) {
                        // Legacy form (no fill fields).
                        arr = value.getAsJsonArray();
                    } else if (value.isJsonObject()) {
                        JsonObject po = value.getAsJsonObject();
                        // v1 stored fillCount (single value); promote to both
                        // min and max so legacy data round-trips with fixed K.
                        if (po.has("fillCount")) {
                            int legacy = po.get("fillCount").getAsInt();
                            fillMin = legacy == ContainerContentsPool.FILL_ALL ? 0 : legacy;
                            fillMax = legacy;
                        }
                        if (po.has("fillMin")) fillMin = po.get("fillMin").getAsInt();
                        if (po.has("fillMax")) fillMax = po.get("fillMax").getAsInt();
                        if (!po.has("entries") || !po.get("entries").isJsonArray()) continue;
                        arr = po.getAsJsonArray("entries");
                    } else {
                        continue;
                    }
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
                        out.put(pos.immutable(), new ContainerContentsPool(entries, fillMin, fillMax));
                    }
                }
            }
            Map<BlockPos, String> linksOut = new LinkedHashMap<>();
            if (obj.has("links") && obj.get("links").isJsonObject()) {
                JsonObject linksObj = obj.getAsJsonObject("links");
                for (Map.Entry<String, JsonElement> e : linksObj.entrySet()) {
                    BlockPos pos = parsePos(e.getKey());
                    if (pos == null) continue;
                    if (!e.getValue().isJsonPrimitive()) continue;
                    String linkId = e.getValue().getAsString();
                    if (linkId == null || linkId.isEmpty()) continue;
                    linksOut.put(pos.immutable(), linkId);
                }
            }
            // If a position appears in both pools and links (legacy v2 file
            // written before the live-reference refactor), the link wins —
            // drop the duplicate local pool data so we don't accidentally
            // serve stale entries.
            for (BlockPos linked : linksOut.keySet()) {
                out.remove(linked);
            }
            LOGGER.info("[DungeonTrain] Loaded container contents store {} ({} cells, {} links) from {}",
                plotKey, out.size(), linksOut.size(), origin);
            return new ContainerContentsStore(plotKey, out, linksOut);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to parse container contents store {}: {}", origin, e.toString());
            return new ContainerContentsStore(plotKey, new LinkedHashMap<>(), new LinkedHashMap<>());
        }
    }

    private static String safeFilename(String plotKey) {
        return plotKey.replace(':', '_').replace('/', '_').toLowerCase(Locale.ROOT);
    }

    private static Path configPathFor(String plotKey) {
        return UserContentPaths.dir(SUBDIR).resolve(safeFilename(plotKey) + EXT);
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
