package games.brennan.dungeontrain.train;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-id weight map biasing the seeded random pick in
 * {@link CarriageContentsRegistry#pick}. Mirrors {@link CarriageWeights}
 * but for carriage <em>interior</em> contents rather than the carriage
 * shell — same MIN/MAX/DEFAULT contract, same two-tier load (bundled
 * resource + per-install config override), same flat JSON schema.
 *
 * <p>Loaded from two tiers at {@link ServerStartingEvent}:
 * <ol>
 *   <li><b>Bundled default</b> — {@code /data/dungeontrain/contents/weights.json}
 *       on the classpath. Ships with the mod jar.</li>
 *   <li><b>Per-install override</b> — {@code config/dungeontrain/contents/weights.json}.
 *       Per-id entries in this file replace entries from the bundled copy.</li>
 * </ol>
 *
 * <p>Schema: a flat JSON object mapping contents id to a non-negative integer.
 * Weight 0 excludes the contents from the spawn pool entirely; missing ids
 * resolve to {@link #DEFAULT} (= 1) which matches the pre-feature uniform
 * pick when every contents is at default weight.</p>
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public record CarriageContentsWeights(Map<String, Integer> byId) {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Minimum allowed weight. 0 excludes a contents from the pool. */
    public static final int MIN = 0;
    /** Maximum allowed weight. Bounds the cumulative sum and keeps the distribution readable. */
    public static final int MAX = 100;
    /** Weight assumed for any contents not mentioned in the map. 1 matches uniform behaviour when every entry defaults. */
    public static final int DEFAULT = 1;

    /** Shared empty instance — identity weight (1) for every id. */
    public static final CarriageContentsWeights EMPTY = new CarriageContentsWeights(Map.of());

    static final String BUNDLED_RESOURCE = "/data/dungeontrain/contents/weights.json";
    static final String CONFIG_SUBDIR = "dungeontrain/contents";
    static final String CONFIG_FILE = "weights.json";

    /** Cached weights for the active server. Loaded on ServerStartingEvent, cleared on stop. */
    private static volatile CarriageContentsWeights current = EMPTY;

    public CarriageContentsWeights {
        byId = Map.copyOf(byId);
    }

    /**
     * Return the clamped weight for {@code id}, or {@link #DEFAULT} if not in the map.
     * Always in {@code [MIN, MAX]}.
     */
    public int weightFor(String id) {
        Integer w = byId.get(id);
        if (w == null) return DEFAULT;
        return clamp(w);
    }

    public static int clamp(int value) {
        if (value < MIN) return MIN;
        if (value > MAX) return MAX;
        return value;
    }

    /** The active server's weights. {@link #EMPTY} outside a server session. */
    public static CarriageContentsWeights current() {
        return current;
    }

    /** Reload bundled + config weights into {@link #current}. Safe to call outside event handlers. */
    public static synchronized void reload() {
        Map<String, Integer> merged = new HashMap<>();
        int bundled = loadInto(BUNDLED_RESOURCE, merged, true);
        int config = loadInto(null, merged, false);
        current = new CarriageContentsWeights(merged);
        LOGGER.info("[DungeonTrain] Carriage contents weights loaded — {} entries ({} bundled, {} config overlays).",
                merged.size(), bundled, config);
    }

    public static synchronized void clear() {
        current = EMPTY;
    }

    /**
     * Update the weight for {@code id} in-memory and persist the full weights
     * map to {@code config/dungeontrain/contents/weights.json}. The new weight
     * takes effect for the next carriage that spawns. Returns the clamped
     * value that was stored.
     */
    public static synchronized int set(String id, int value) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        int clamped = clamp(value);
        Map<String, Integer> next = new HashMap<>(current.byId());
        next.put(key, clamped);
        current = new CarriageContentsWeights(next);
        writeConfig(current);
        LOGGER.info("[DungeonTrain] Set carriage contents weight {}={} (persisted to {}).",
                key, clamped, configPath());
        return clamped;
    }

    /**
     * Remove an override for {@code id} (reverts it to {@link #DEFAULT} on the
     * next lookup). Persists immediately. Returns true if an entry was removed.
     */
    public static synchronized boolean unset(String id) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        if (!current.byId().containsKey(key)) return false;
        Map<String, Integer> next = new HashMap<>(current.byId());
        next.remove(key);
        current = new CarriageContentsWeights(next);
        writeConfig(current);
        LOGGER.info("[DungeonTrain] Cleared carriage contents weight override for {} (persisted to {}).",
                key, configPath());
        return true;
    }

    private static void writeConfig(CarriageContentsWeights weights) throws IOException {
        Path file = configPath();
        Files.createDirectories(file.getParent());
        Map<String, Integer> sorted = new TreeMap<>(weights.byId());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(sorted, w);
        }
    }

    private static int loadInto(String source, Map<String, Integer> into, boolean fromResource) {
        try (Reader reader = fromResource ? openResource(source) : openConfig()) {
            if (reader == null) return 0;
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Contents weights {} is not a JSON object — ignoring.",
                        fromResource ? source : configPath());
                return 0;
            }
            JsonObject obj = root.getAsJsonObject();
            int added = 0;
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String id = e.getKey().toLowerCase(Locale.ROOT);
                Integer parsed = parseWeight(id, e.getValue(), fromResource);
                if (parsed == null) continue;
                into.put(id, parsed);
                added++;
            }
            return added;
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read contents weights from {}: {}",
                    fromResource ? source : configPath(), e.toString());
            return 0;
        }
    }

    private static Reader openResource(String resource) {
        InputStream in = CarriageContentsWeights.class.getResourceAsStream(resource);
        if (in == null) return null;
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static Reader openConfig() {
        Path file = configPath();
        if (!Files.isRegularFile(file)) return null;
        try {
            return Files.newBufferedReader(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Could not open contents weights file {}: {}", file, e.toString());
            return null;
        }
    }

    public static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve(CONFIG_SUBDIR).resolve(CONFIG_FILE);
    }

    private static Integer parseWeight(String id, JsonElement el, boolean fromResource) {
        String origin = fromResource ? "bundled" : "config";
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            LOGGER.warn("[DungeonTrain] Contents weight for '{}' in {} is not a number — ignoring.", id, origin);
            return null;
        }
        double raw;
        try {
            raw = el.getAsDouble();
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Contents weight for '{}' in {} failed to parse: {}", id, origin, e.toString());
            return null;
        }
        if (Double.isNaN(raw) || Double.isInfinite(raw)) {
            LOGGER.warn("[DungeonTrain] Contents weight for '{}' in {} is not finite — ignoring.", id, origin);
            return null;
        }
        int rounded = (int) Math.round(raw);
        int clamped = clamp(rounded);
        if (clamped != rounded) {
            LOGGER.warn("[DungeonTrain] Contents weight for '{}' in {} clamped from {} to {} (range {}..{}).",
                    id, origin, rounded, clamped, MIN, MAX);
        }
        return clamped;
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
